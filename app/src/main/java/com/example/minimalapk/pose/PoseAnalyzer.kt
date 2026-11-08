package com.example.minimalapk.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import com.example.minimalapk.log.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
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
)

class PoseAnalyzer private constructor(private val backend: PoseBackend) : AutoCloseable {
    val executionProvider: String = backend.name
    private val lock = Any()
    private var closed = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE)
    private var frames = 0

    init {
        lastExecutionProvider = executionProvider
        AppLog.i(TAG, "推理后端就绪：$executionProvider")
    }

    fun analyze(source: Bitmap, rotationDegrees: Int): PoseFrame = synchronized(lock) {
        check(!closed) { "PoseAnalyzer is closed" }
        val start = System.nanoTime()
        val input = createInput(source, rotationDegrees)
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
                "首帧耗时：预处理=${timings.preprocessMs.roundToInt()} ms，" +
                    "推理=${timings.inferenceMs.roundToInt()} ms，" +
                    "后处理=${timings.postprocessMs.roundToInt()} ms",
            )
        }
        PoseFrame(
            bitmap = input,
            detections = detections,
            timings = timings,
        )
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            backend.close()
        }
    }

    private fun createInput(source: Bitmap, rotationDegrees: Int): Bitmap {
        val upright = if (rotationDegrees % 360 == 0) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                true,
            )
        }
        val scale = min(
            PoseNet.INPUT_SIZE.toFloat() / upright.width,
            PoseNet.INPUT_SIZE.toFloat() / upright.height,
        )
        val targetWidth = (upright.width * scale).roundToInt()
        val targetHeight = (upright.height * scale).roundToInt()
        val left = (PoseNet.INPUT_SIZE - targetWidth) / 2
        val top = (PoseNet.INPUT_SIZE - targetHeight) / 2
        val input = createBitmap(PoseNet.INPUT_SIZE, PoseNet.INPUT_SIZE)
        val canvas = Canvas(input)
        val padding = PoseNet.LETTERBOX_COLOR
        canvas.drawColor(Color.rgb(padding, padding, padding))
        canvas.drawBitmap(
            upright,
            null,
            Rect(left, top, left + targetWidth, top + targetHeight),
            paint,
        )
        if (upright !== source) upright.recycle()
        return input
    }

    companion object {
        const val PARAM_ASSET = "models/yolo26n_pose.ncnn.param"
        const val MODEL_ASSET = "models/yolo26n_pose.ncnn.bin"
        const val MNN_MODEL_ASSET = "models/yolo26n_pose.mnn"
        const val TFLITE_MODEL_ASSET = "models/yolo26n_pose.tflite"

        private const val TAG = "PoseAnalyzer"

        // The accelerator probes other than NPU share one budget: they all sit on the same
        // OpenCL/Vulkan driver, so one blocked probe usually predicts the others. A single budget
        // keeps a blocking driver from delaying the camera preview by one timeout per backend.
        private const val GPU_PROBE_BUDGET_MS = 10_000L

        // NPU is a one-time JIT compile per model open, not a driver probe, so it gets its own
        // budget instead of eating the GPU probes' 10s.
        private const val NPU_PROBE_NAME = "LiteRT NPU"
        private const val NPU_PROBE_BUDGET_MS = 30_000L

        // Some Vulkan/OpenCL drivers block inside model loading forever. Skipping a backend that
        // failed for the rest of the process keeps later camera visits from starting another
        // stuck load.
        private val unavailableGpuBackends = mutableSetOf<String>()
        private val openLock = Any()

        @Volatile
        var lastExecutionProvider: String? = null
            private set

        // NPU is preferred over the GPU backends; ncnn's Vulkan backend is unstable on Adreno 840
        // (SM8850), and MNN goes before LiteRT GPU because its session reports the real primary
        // backend while LiteRT may silently fall back to CPU.
        private val gpuBackends = listOf<Pair<String, (Context) -> PoseBackend?>>(
            NPU_PROBE_NAME to { context -> LiteRtBackend.openNpu(context) },
            "MNN GPU" to { context -> MnnBackend.open(context.assets, useGpu = true) },
            "LiteRT GPU" to { context -> LiteRtBackend.open(context.assets, useGpu = true) },
            "ncnn Vulkan" to { context -> NcnnBackend.open(context.assets, useGpu = true) },
        )
        private val cpuBackends = listOf<Pair<String, (Context) -> PoseBackend?>>(
            "LiteRT CPU" to { context -> LiteRtBackend.open(context.assets, useGpu = false) },
            "MNN CPU" to { context -> MnnBackend.open(context.assets, useGpu = false) },
            "ncnn CPU" to { context -> NcnnBackend.open(context.assets, useGpu = false) },
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
            // A manual CPU choice skips the accelerator probes entirely, so it also skips
            // their budgets.
            val gpuCandidates = if (manualName != null && !preference.gpu) {
                emptyList()
            } else {
                gpuBackends.sortedByDescending { it.first == manualName }
            }
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
                val backend = loadWithTimeout(name, remainingMs) { load(context) }
                if (backend != null) return PoseAnalyzer(backend)
                unavailableGpuBackends += name
            }
            for ((name, load) in cpuBackends.sortedByDescending { it.first == manualName }) {
                val started = System.nanoTime()
                val backend = runCatching { load(context) }
                    .onFailure { AppLog.w(TAG, "$name 加载失败", it) }
                    .getOrNull()
                if (backend != null) {
                    val elapsedMs = (System.nanoTime() - started).nanosToMillis().roundToInt()
                    AppLog.i(TAG, "$name 加载成功：$elapsedMs ms")
                    return PoseAnalyzer(backend)
                }
            }
            AppLog.e(TAG, "没有可用的推理后端")
            error("无法加载姿态模型")
        }

        private fun loadWithTimeout(
            name: String,
            timeoutMs: Long,
            load: () -> PoseBackend?,
        ): PoseBackend? {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "$name-load").apply { isDaemon = true }
            }
            val abandoned = AtomicBoolean(false)
            val result = AtomicReference<PoseBackend?>(null)
            val future = executor.submit<PoseBackend?> {
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
                abandoned.set(true)
                result.getAndSet(null)?.close()
                AppLog.w(TAG, "$name 加载超时（$timeoutMs ms）")
                null
            } catch (e: Exception) {
                AppLog.w(TAG, "$name 加载失败", e)
                null
            } finally {
                executor.shutdown()
            }
        }
    }
}
