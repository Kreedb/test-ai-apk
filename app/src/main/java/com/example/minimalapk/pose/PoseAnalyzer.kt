package com.example.minimalapk.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.core.graphics.createBitmap
import com.example.minimalapk.log.AppLog
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private fun Long.nanosToMillis(): Float = this / 1_000_000f

data class PoseTimings(
    val preprocessMs: Float,
    val inferenceMs: Float,
    val postprocessMs: Float,
) {
    val totalMs: Float get() = preprocessMs + inferenceMs + postprocessMs
}

data class PoseFrame(
    val bitmap: Bitmap,
    val detections: List<PoseDetection>,
    val timings: PoseTimings,
    // The real photo's rectangle inside the padded model input; measurements need these bounds.
    val photoBounds: PhotoBounds,
)

/**
 * Owns a pose backend and runs every backend operation on [executor]. MLDrift's OpenGL path binds
 * its graphics context to the creating thread, so create, analyze, and close must share one
 * long-lived worker rather than the loader/camera/IO threads that happen to call them.
 */
class PoseAnalyzer internal constructor(
    private val backend: PoseBackend,
    private val executor: ExecutorService,
) : AutoCloseable {
    val executionProvider: String = backend.name
    private val lock = Any()
    private var closed = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE)
    // Two buffers because the previous frame may still be drawn while the next one is analyzed.
    private val inputBuffers = arrayOfNulls<Bitmap>(2)
    private var inputIndex = 0
    private var frames = 0

    init {
        lastExecutionProvider = executionProvider
        AppLog.i(TAG, "推理后端就绪：$executionProvider")
    }

    fun analyze(source: Bitmap, rotationDegrees: Int): PoseFrame {
        val future = executor.submit<PoseFrame> { analyzeOnWorker(source, rotationDegrees) }
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Pose analysis interrupted")
        }
    }

    private fun analyzeOnWorker(source: Bitmap, rotationDegrees: Int): PoseFrame = synchronized(lock) {
        check(!closed) { "PoseAnalyzer is closed" }
        val start = System.nanoTime()
        val layout = poseInputLayout(source.width, source.height, rotationDegrees)
        val input = createInput(source, layout)
        val rendered = System.nanoTime()
        input.getPixels(
            pixels,
            0,
            PoseNet.INPUT_SIZE,
            0,
            0,
            PoseNet.INPUT_SIZE,
            PoseNet.INPUT_SIZE,
        )
        val preprocessed = System.nanoTime()
        val raw = backend.analyze(pixels)
        val inferred = System.nanoTime()
        // topDetections selects the 300 best anchors and is CPU postprocessing, not inference.
        val detections = PoseDecoder.decode(PosePostprocess.topDetections(raw))
        val finished = System.nanoTime()
        val timings = PoseTimings(
            preprocessMs = (preprocessed - start).nanosToMillis(),
            inferenceMs = (inferred - preprocessed).nanosToMillis(),
            postprocessMs = (finished - inferred).nanosToMillis(),
        )
        if (frames++ == 0) {
            AppLog.i(
                TAG,
                "首帧耗时（源 ${source.width}x${source.height}，旋转 $rotationDegrees°）：" +
                    "预处理=${timings.preprocessMs.roundToInt()} ms" +
                    "（旋转+letterbox=${(rendered - start).nanosToMillis().roundToInt()} ms，" +
                    "取像素=${(preprocessed - rendered).nanosToMillis().roundToInt()} ms），" +
                    "推理=${timings.inferenceMs.roundToInt()} ms，" +
                    "后处理=${timings.postprocessMs.roundToInt()} ms",
            )
        }
        PoseFrame(
            bitmap = input,
            detections = detections,
            timings = timings,
            photoBounds = layout.photoBounds,
        )
    }

    override fun close() {
        if (executor.isShutdown) return
        runCatching {
            executor.submit {
                synchronized(lock) {
                    if (!closed) {
                        closed = true
                        backend.close()
                    }
                }
            }.get()
        }
        executor.shutdown()
    }

    private fun createInput(source: Bitmap, layout: PoseInputLayout): Bitmap {
        inputIndex = 1 - inputIndex
        val input = inputBuffers[inputIndex]
            ?: createBitmap(PoseNet.INPUT_SIZE, PoseNet.INPUT_SIZE).also {
                inputBuffers[inputIndex] = it
            }
        val canvas = Canvas(input)
        val padding = PoseNet.LETTERBOX_COLOR
        canvas.drawColor(Color.rgb(padding, padding, padding))
        // Rotate around the source center and scale straight into the letterbox rect, so 90/180/270
        // no longer need an intermediate full-frame copy.
        canvas.save()
        canvas.translate(layout.centerX, layout.centerY)
        canvas.rotate(layout.rotationDegrees.toFloat())
        canvas.scale(layout.scale, layout.scale)
        canvas.translate(-source.width / 2f, -source.height / 2f)
        canvas.drawBitmap(source, 0f, 0f, paint)
        canvas.restore()
        return input
    }

    companion object {
        const val TFLITE_MODEL_ASSET = "models/yolo26n_pose.tflite"

        private const val TAG = "PoseAnalyzer"

        // A broken driver can block inside model loading forever, so cap the GPU probe.
        private const val GPU_PROBE_BUDGET_MS = 10_000L

        // NPU is a one-time JIT compile per model open, not a driver probe, so it gets its own
        // budget instead of eating the GPU probes' 10s.
        private const val NPU_PROBE_NAME = "LiteRT NPU"
        private const val NPU_PROBE_BUDGET_MS = 30_000L

        private const val MODEL_STUCK_MESSAGE =
            "模型加载超时：加速器驱动可能已卡住，请重启应用，并在设置中选择 LiteRT (CPU) 后重试"

        // Some OpenCL/OpenGL drivers block inside model loading forever. Skipping a backend that
        // failed for the rest of the process keeps later camera visits from starting another
        // stuck load.
        private val unavailableGpuBackends = mutableSetOf<String>()
        private val openLock = Any()

        // LiteRT serializes native compilation with one process-wide mutex. A timed-out compile
        // that is still native-blocked keeps holding it, so any later open would hang too; refuse
        // further opens until the app restarts.
        @Volatile
        private var nativeCompileTimedOut = false

        @Volatile
        var lastExecutionProvider: String? = null
            private set

        // NPU is preferred over GPU; LiteRT GPU may silently fall back to CPU.
        private val gpuBackends = listOf<Pair<String, (Context) -> PoseBackend?>>(
            NPU_PROBE_NAME to { context -> LiteRtBackend.openNpu(context) },
            "LiteRT GPU" to { context -> LiteRtBackend.open(context.assets, useGpu = true) },
        )
        private val cpuBackends = listOf<Pair<String, (Context) -> PoseBackend?>>(
            "LiteRT CPU" to { context -> LiteRtBackend.open(context.assets, useGpu = false) },
        )

        /** Probe names, exposed so tests can keep [PoseBackendPreference.backendName] in sync. */
        internal val gpuProbeNames: List<String> = gpuBackends.map { it.first }
        internal val cpuProbeNames: List<String> = cpuBackends.map { it.first }

        /**
         * LiteRT NPU requires Android 12+, arm64-v8a, and a Qualcomm SoC. The app bundles only the
         * HTP v81 (SM8850) Skel/Stub, so other SoCs would spend the JIT budget on a compile that
         * cannot succeed. Returns null when supported, otherwise the reason shown in the log.
         */
        internal fun npuUnavailableReason(
            sdkInt: Int,
            abis: Array<String>,
            socManufacturer: String,
            socModel: String,
        ): String? {
            if (sdkInt < Build.VERSION_CODES.S) {
                return "需要 Android 12（API 31）及以上，当前 API $sdkInt"
            }
            if (abis.none { it == "arm64-v8a" }) {
                return "需要 arm64-v8a，当前 ${abis.joinToString()}"
            }
            val manufacturer = socManufacturer.trim()
            if (!manufacturer.equals("Qualcomm", true) && !manufacturer.equals("QTI", true)) {
                return "需要 Qualcomm SoC，当前 ${manufacturer.ifEmpty { "未知" }}"
            }
            if (!socModel.trim().startsWith("SM8850")) {
                return "当前只打包 HTP v81（SM8850）运行库，SoC 型号为 ${socModel.ifEmpty { "未知" }}"
            }
            return null
        }

        private fun npuUnavailableReasonForDevice(): String? {
            val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER
            } else {
                ""
            }
            val model = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else {
                ""
            }
            return npuUnavailableReason(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS, manufacturer, model)
        }

        fun open(
            context: Context,
            preference: PoseBackendPreference = PoseBackendPreference.AUTO,
        ): PoseAnalyzer = synchronized(openLock) {
            if (nativeCompileTimedOut) error(MODEL_STUCK_MESSAGE)
            val manualName = preference.backendName
            val sdkInt = Build.VERSION.SDK_INT
            val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER
            } else {
                ""
            }
            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else {
                ""
            }
            AppLog.i(
                TAG,
                "推理后端偏好：${preference.label}；设备 SDK=$sdkInt，" +
                    "ABI=${Build.SUPPORTED_ABIS.joinToString()}，" +
                    "SoC 厂商=${manufacturer.ifBlank { "未知" }}，" +
                    "SoC 型号=${socModel.ifBlank { "未知" }}，" +
                    "型号=${Build.MODEL}",
            )
            // A manual choice probes only that accelerator; a failed manual GPU choice must not
            // silently become an NPU run. Automatic selection keeps the full preference order.
            val gpuCandidates = when {
                manualName != null && !preference.gpu -> emptyList()
                manualName != null -> gpuBackends.filter { it.first == manualName }
                else -> gpuBackends
            }
            val worker = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PoseBackend").apply { isDaemon = true }
            }
            try {
                // Started lazily so a slow NPU JIT compile cannot consume the GPU probes' budget.
                var gpuDeadline = 0L
                for ((name, load) in gpuCandidates) {
                    if (name == NPU_PROBE_NAME) {
                        val reason = npuUnavailableReasonForDevice()
                        if (reason != null) {
                            AppLog.i(TAG, "跳过 $NPU_PROBE_NAME：$reason")
                            continue
                        }
                    }
                    if (name in unavailableGpuBackends) {
                        if (name == manualName) AppLog.w(TAG, "$name 本次运行已加载失败，跳过")
                        continue
                    }
                    val remainingMs = if (name == NPU_PROBE_NAME) {
                        NPU_PROBE_BUDGET_MS
                    } else {
                        if (gpuDeadline == 0L) {
                            gpuDeadline = System.nanoTime() + GPU_PROBE_BUDGET_MS * 1_000_000L
                        }
                        (gpuDeadline - System.nanoTime()) / 1_000_000L
                    }
                    if (remainingMs <= 0L) break
                    val probeStarted = System.nanoTime()
                    val backend = try {
                        loadWithTimeout(name, remainingMs, worker) { load(context) }
                    } catch (e: TimeoutException) {
                        // The native call keeps holding LiteRT's compile mutex; block every later
                        // open until the process restarts instead of hanging on it.
                        nativeCompileTimedOut = true
                        // Surface the restart guidance instead of TimeoutException's empty message.
                        throw IllegalStateException(MODEL_STUCK_MESSAGE, e)
                    }
                    if (name == NPU_PROBE_NAME && gpuDeadline != 0L) {
                        // The NPU JIT does not touch the shared GPU driver, and in manual mode its
                        // probe runs after gpuDeadline started; give that time back so a slow
                        // compile cannot exhaust the budget the GPU backends are entitled to.
                        gpuDeadline += System.nanoTime() - probeStarted
                    }
                    if (backend != null) return PoseAnalyzer(backend, worker)
                    unavailableGpuBackends += name
                }
                for ((name, load) in cpuBackends.sortedByDescending { it.first == manualName }) {
                    val started = System.nanoTime()
                    val backend = loadOnWorker(name, worker) { load(context) }
                    if (backend != null) {
                        val elapsedMs = (System.nanoTime() - started).nanosToMillis().roundToInt()
                        AppLog.i(TAG, "$name 加载成功：$elapsedMs ms")
                        return PoseAnalyzer(backend, worker)
                    }
                }
                error("无法加载姿态模型")
            } catch (t: Throwable) {
                // Graceful shutdown so a close task queued for a backend the worker is still
                // finishing (see loadWithTimeout) can still run instead of being discarded.
                worker.shutdown()
                throw t
            }
        }

        /**
         * Runs a timed probe on [worker]. On timeout the native call cannot be interrupted and
         * keeps holding LiteRT's compile mutex, so the caller must stop opening models for this
         * process run.
         */
        internal fun loadWithTimeout(
            name: String,
            timeoutMs: Long,
            worker: ExecutorService,
            load: () -> PoseBackend?,
        ): PoseBackend? {
            val abandoned = AtomicBoolean(false)
            val result = AtomicReference<PoseBackend?>(null)
            val future = worker.submit<PoseBackend?> {
                val backend = load()
                result.set(backend)
                if (abandoned.get()) {
                    // The caller already timed out; whoever wins the CAS closes the late result.
                    if (result.compareAndSet(backend, null)) backend?.close()
                    null
                } else {
                    backend
                }
            }
            val started = System.nanoTime()
            return try {
                val backend = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                if (backend == null) {
                    AppLog.w(TAG, "$name 不可用")
                } else {
                    val elapsedMs = (System.nanoTime() - started).nanosToMillis().roundToInt()
                    AppLog.i(TAG, "$name 加载成功：$elapsedMs ms")
                }
                backend
            } catch (e: TimeoutException) {
                // The blocked call cannot be interrupted; whoever wins the CAS closes the result.
                // Hand a late result back to the worker so accelerator teardown stays on the
                // thread that created the graphics context.
                abandoned.set(true)
                closeOnWorker(worker, result.getAndSet(null))
                AppLog.w(TAG, "$name 加载超时（$timeoutMs ms）")
                throw e
            } catch (e: InterruptedException) {
                // Not a timeout, but the worker may still publish a backend after we stop waiting;
                // close it on the worker and restore the caller's interrupt flag.
                abandoned.set(true)
                closeOnWorker(worker, result.getAndSet(null))
                Thread.currentThread().interrupt()
                AppLog.w(TAG, "$name 加载被中断")
                null
            } catch (e: Exception) {
                AppLog.w(TAG, "$name 加载失败", e)
                null
            }
        }

        /**
         * Closes [backend] on [worker] so MLDrift's OpenGL teardown runs on the thread that created
         * its context. Only called once the result reference was claimed here, which means the
         * worker has already returned from the native load and is about to finish its task, so
         * waiting cannot block on a stuck compile.
         */
        private fun closeOnWorker(worker: ExecutorService, backend: PoseBackend?) {
            if (backend == null) return
            try {
                worker.submit { backend.close() }.get()
            } catch (e: InterruptedException) {
                // The caller is already being interrupted; queue the close instead of blocking.
                Thread.currentThread().interrupt()
                runCatching { worker.submit { backend.close() } }
            } catch (e: Exception) {
                AppLog.w(TAG, "关闭迟到模型失败", e)
                runCatching { backend.close() }
            }
        }

        private fun loadOnWorker(
            name: String,
            worker: ExecutorService,
            load: () -> PoseBackend?,
        ): PoseBackend? =
            try {
                val backend = worker.submit<PoseBackend?> { load() }.get()
                if (backend == null) AppLog.w(TAG, "$name 不可用")
                backend
            } catch (e: ExecutionException) {
                AppLog.w(TAG, "$name 加载失败", e.cause ?: e)
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                AppLog.w(TAG, "$name 加载被中断")
                null
            }
    }
}
