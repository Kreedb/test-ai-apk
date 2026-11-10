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

data class ModelResult(
    val spec: ModelSpec,
    /** Top detection after debounce; null when the model found nobody or the debounce holds it off. */
    val detection: PoseDetection?,
    val timings: PoseTimings,
)

data class PoseFrame(
    val bitmap: Bitmap,
    /** Enabled models in display order; empty when every model is disabled. */
    val results: List<ModelResult>,
    // The real photo's rectangle inside the padded model input; measurements need these bounds.
    val photoBounds: PhotoBounds,
) {
    val primary: ModelResult? get() = results.firstOrNull()
}

/**
 * Owns one backend per enabled model and runs every backend operation on [executor]. MLDrift's
 * OpenGL path binds its graphics context to the creating thread, so create, analyze, and close must
 * share one long-lived worker rather than the loader/camera/IO threads that happen to call them.
 *
 * Enabled models run serially on the same frame: one preview bitmap is built per model size, each
 * model runs in turn, and only the first model's input is kept for display.
 */
class PoseAnalyzer internal constructor(
    private val runners: List<Runner>,
    private val previewSpec: ModelSpec,
    private val executor: ExecutorService,
) : AutoCloseable {
    val executionProvider: String = runners.firstOrNull()?.backend?.name ?: NO_MODEL_PROVIDER
    private var closed = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var frames = 0

    internal class Runner(val spec: ModelSpec, val backend: PoseBackend) {
        val pixels = IntArray(spec.inputSize * spec.inputSize)
        private val debounce = PoseDebounce(spec.debounceFrames)
        private var held: PoseDetection? = null

        // The non-primary model's input is never displayed, so one bitmap can be redrawn per frame.
        var scratch: Bitmap? = null

        fun publish(detection: PoseDetection?): PoseDetection? {
            val visible = debounce.accept(detection != null)
            if (!visible) {
                held = null
                return null
            }
            // Hold the last real detection through a brief dropout so the overlay does not flicker.
            if (detection != null) held = detection
            return held
        }
    }

    init {
        lastExecutionProvider = runners.firstOrNull()?.backend?.name
        AppLog.i(
            TAG,
            "推理后端就绪：$executionProvider，启用模型=${runners.joinToString { it.spec.name }}",
        )
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

    private fun analyzeOnWorker(source: Bitmap, rotationDegrees: Int): PoseFrame {
        check(!closed) { "PoseAnalyzer is closed" }
        var preview: Bitmap? = null
        var photoBounds: PhotoBounds? = null
        val results = ArrayList<ModelResult>(runners.size)
        for ((index, runner) in runners.withIndex()) {
            val spec = runner.spec
            val start = System.nanoTime()
            val layout = poseInputLayout(source.width, source.height, rotationDegrees, spec.inputSize)
            val input = renderInput(
                source = source,
                layout = layout,
                size = spec.inputSize,
                // The primary bitmap is published to the display, so Compose may still draw an
                // older frame; it must never be reused. Others are consumed within this loop.
                reuse = if (index == 0) null else runner.scratch,
            )
            val rendered = System.nanoTime()
            input.getPixels(runner.pixels, 0, spec.inputSize, 0, 0, spec.inputSize, spec.inputSize)
            val preprocessed = System.nanoTime()
            val raw = runner.backend.analyze(runner.pixels)
            val inferred = System.nanoTime()
            // Selecting and decoding anchors is CPU postprocessing, not inference. Only the top
            // detection is kept: this build does single-person measurement, not multi-person tracking.
            val published = runner.publish(PoseDecoder.decode(raw, spec).firstOrNull())
            val finished = System.nanoTime()
            if (index == 0) preview = input else runner.scratch = input
            if (photoBounds == null) photoBounds = layout.photoBounds
            results += ModelResult(
                spec = spec,
                detection = published,
                timings = PoseTimings(
                    preprocessMs = (preprocessed - start).nanosToMillis(),
                    inferenceMs = (inferred - preprocessed).nanosToMillis(),
                    postprocessMs = (finished - inferred).nanosToMillis(),
                ),
            )
        }
        if (preview == null) {
            // No model is enabled, but the preview still needs a bitmap: the camera page has no
            // CameraX Preview use case, so a frame without an input bitmap would go blank.
            val layout = poseInputLayout(
                source.width,
                source.height,
                rotationDegrees,
                previewSpec.inputSize,
            )
            preview = renderInput(source, layout, previewSpec.inputSize, reuse = null)
            photoBounds = layout.photoBounds
        }
        val frameBitmap = checkNotNull(preview)
        val frameBounds = checkNotNull(photoBounds)
        if (frames++ == 0) {
            AppLog.i(
                TAG,
                "首帧耗时（源 ${source.width}x${source.height}，旋转 $rotationDegrees°）：" +
                    results.joinToString("；") { result ->
                        "${result.spec.name} " +
                            "预处理=${result.timings.preprocessMs.roundToInt()} ms，" +
                            "推理=${result.timings.inferenceMs.roundToInt()} ms，" +
                            "后处理=${result.timings.postprocessMs.roundToInt()} ms"
                    },
            )
        }
        return PoseFrame(bitmap = frameBitmap, results = results, photoBounds = frameBounds)
    }

    override fun close() {
        if (executor.isShutdown) return
        runCatching {
            executor.submit {
                if (!closed) {
                    closed = true
                    runners.forEach { runner ->
                        runCatching { runner.backend.close() }.onFailure {
                            AppLog.w(TAG, "关闭 ${runner.spec.name} 失败", it)
                        }
                    }
                }
            }.get()
        }.onFailure { AppLog.w(TAG, "关闭推理后端失败", it) }
        executor.shutdown()
    }

    private fun renderInput(
        source: Bitmap,
        layout: PoseInputLayout,
        size: Int,
        reuse: Bitmap?,
    ): Bitmap {
        val input = reuse ?: createBitmap(size, size)
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
        private const val TAG = "PoseAnalyzer"
        internal const val NO_MODEL_PROVIDER = "未启用推理"

        // A broken driver can block inside model loading forever, so cap every probe.
        private const val PROBE_BUDGET_MS = 10_000L

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
        private val gpuBackends = listOf<Pair<String, (Context, ModelSpec) -> PoseBackend?>>(
            NPU_PROBE_NAME to { context, spec -> LiteRtBackend.openNpu(context, spec) },
            "LiteRT GPU" to { context, spec -> LiteRtBackend.open(context, spec, useGpu = true) },
        )
        private val cpuBackends = listOf<Pair<String, (Context, ModelSpec) -> PoseBackend?>>(
            "LiteRT CPU" to { context, spec -> LiteRtBackend.open(context, spec, useGpu = false) },
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
            specs: List<ModelSpec> = ModelStore.load(context),
        ): PoseAnalyzer = synchronized(openLock) {
            val previewSpec = specs.firstOrNull() ?: ModelStore.builtIn.first()
            val enabled = specs.filter { it.enabled }.take(ModelStore.MAX_ENABLED)
            val worker = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PoseBackend").apply { isDaemon = true }
            }
            if (enabled.isEmpty()) {
                // No backend is opened, so a stuck-compilation latch from an earlier visit does
                // not matter: the preview-only path must keep working.
                AppLog.i(TAG, "没有启用的模型，仅显示预览画面")
                return PoseAnalyzer(emptyList(), previewSpec, worker)
            }
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
                "推理后端偏好：${preference.label}；启用模型=${enabled.joinToString { it.name }}；" +
                    "设备 SDK=$sdkInt，ABI=${Build.SUPPORTED_ABIS.joinToString()}，" +
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
            try {
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
                    val budgetMs = if (name == NPU_PROBE_NAME) NPU_PROBE_BUDGET_MS else PROBE_BUDGET_MS
                    val backend = loadCandidate(name, budgetMs, worker) { load(context, enabled.first()) }
                    if (backend != null) {
                        return PoseAnalyzer(
                            buildRunners(context, worker, name, load, enabled, backend),
                            previewSpec,
                            worker,
                        )
                    }
                    unavailableGpuBackends += name
                }
                for ((name, load) in cpuBackends) {
                    val backend = loadCandidate(name, PROBE_BUDGET_MS, worker) { load(context, enabled.first()) }
                    if (backend != null) {
                        return PoseAnalyzer(
                            buildRunners(context, worker, name, load, enabled, backend),
                            previewSpec,
                            worker,
                        )
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
         * Adds the remaining enabled models on the accelerator that already compiled the first
         * model. A model that fails to load is dropped instead of failing the whole camera; a
         * timeout fails the open, because the shared worker would never serve another frame.
         */
        private fun buildRunners(
            context: Context,
            worker: ExecutorService,
            backendName: String,
            load: (Context, ModelSpec) -> PoseBackend?,
            specs: List<ModelSpec>,
            primary: PoseBackend,
        ): List<Runner> {
            val runners = mutableListOf(Runner(specs.first(), primary))
            for (spec in specs.drop(1)) {
                val budgetMs = if (backendName == NPU_PROBE_NAME) {
                    NPU_PROBE_BUDGET_MS
                } else {
                    PROBE_BUDGET_MS
                }
                val backend = try {
                    loadWithTimeout(backendName, budgetMs, worker) { load(context, spec) }
                } catch (e: TimeoutException) {
                    // The worker is blocked inside native compilation, so it can neither serve
                    // frames nor run a queued close. Returning an analyzer now would freeze the
                    // camera on its first analyze(); fail the open like the primary probe does.
                    nativeCompileTimedOut = true
                    throw IllegalStateException(MODEL_STUCK_MESSAGE, e)
                } catch (e: Exception) {
                    AppLog.w(TAG, "${spec.name} 加载失败，本次跳过该模型", e)
                    null
                }
                // The scratch bitmap is allocated on the worker on the first frame and reused;
                // allocating it here would leak a backend if the allocation throws.
                if (backend != null) runners += Runner(spec, backend)
            }
            return runners
        }

        /** Turns a stuck native load into the same restart guidance used by every backend. */
        private fun loadCandidate(
            name: String,
            timeoutMs: Long,
            worker: ExecutorService,
            load: () -> PoseBackend?,
        ): PoseBackend? =
            try {
                loadWithTimeout(name, timeoutMs, worker, load)
            } catch (e: TimeoutException) {
                // The native call keeps holding LiteRT's compile mutex; block every later open
                // until the process restarts instead of hanging on it.
                nativeCompileTimedOut = true
                throw IllegalStateException(MODEL_STUCK_MESSAGE, e)
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
            val task = try {
                worker.submit { backend.close() }
            } catch (e: Exception) {
                AppLog.w(TAG, "关闭迟到模型失败", e)
                return
            }
            try {
                task.get()
            } catch (e: InterruptedException) {
                // The task already queued will run when the worker drains; queueing another close
                // would close the same backend twice.
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                AppLog.w(TAG, "关闭迟到模型失败", e)
            }
        }
    }
}
