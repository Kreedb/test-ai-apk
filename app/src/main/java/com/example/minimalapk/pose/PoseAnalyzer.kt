package com.example.minimalapk.pose

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.example.minimalapk.log.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
        PoseFrame(
            bitmap = input,
            detections = detections,
            timings = PoseTimings(
                preprocessMs = (preprocessed - start).nanosToMillis(),
                inferenceMs = (inferred - preprocessed).nanosToMillis(),
                postprocessMs = (finished - inferred).nanosToMillis(),
            ),
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

        // The GPU probes share one budget: the backends all sit on the same OpenCL/Vulkan driver,
        // so one blocked probe usually predicts the others. A single budget keeps a blocking
        // driver from delaying the camera preview by one timeout per backend.
        private const val GPU_PROBE_BUDGET_MS = 10_000L

        // Some Vulkan/OpenCL drivers block inside model loading forever. Skipping a backend that
        // failed for the rest of the process keeps later camera visits from starting another
        // stuck load.
        private val unavailableGpuBackends = mutableSetOf<String>()
        private val openLock = Any()

        @Volatile
        var lastExecutionProvider: String? = null
            private set

        // LiteRT and MNN are preferred over ncnn because ncnn's Vulkan backend is unstable on
        // Adreno 840 (SM8850). MNN goes first because its session reports the real primary
        // backend, while LiteRT may silently fall back to CPU.
        private val gpuBackends = listOf<Pair<String, (AssetManager) -> PoseBackend?>>(
            "MNN GPU" to { assets -> MnnBackend.open(assets, useGpu = true) },
            "LiteRT GPU" to { assets -> LiteRtBackend.open(assets, useGpu = true) },
            "ncnn Vulkan" to { assets -> NcnnBackend.open(assets, useGpu = true) },
        )
        private val cpuBackends = listOf<Pair<String, (AssetManager) -> PoseBackend?>>(
            "LiteRT CPU" to { assets -> LiteRtBackend.open(assets, useGpu = false) },
            "MNN CPU" to { assets -> MnnBackend.open(assets, useGpu = false) },
            "ncnn CPU" to { assets -> NcnnBackend.open(assets, useGpu = false) },
        )

        fun open(
            context: Context,
            preference: PoseBackendPreference = PoseBackendPreference.AUTO,
        ): PoseAnalyzer = synchronized(openLock) {
            val assets = context.assets
            val manualName = preference.backendName
            AppLog.i(TAG, "推理后端偏好：${preference.label}")
            // A manual CPU choice skips the GPU probes entirely, so it also skips their budget.
            val gpuCandidates = if (manualName != null && !preference.gpu) {
                emptyList()
            } else {
                gpuBackends.sortedByDescending { it.first == manualName }
            }
            val gpuDeadline = System.nanoTime() + GPU_PROBE_BUDGET_MS * 1_000_000L
            for ((name, load) in gpuCandidates) {
                if (name in unavailableGpuBackends) {
                    if (name == manualName) AppLog.w(TAG, "$name 本次运行已加载失败，跳过")
                    continue
                }
                val remainingMs = (gpuDeadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) break
                val backend = loadWithTimeout(name, remainingMs) { load(assets) }
                if (backend != null) return PoseAnalyzer(backend)
                unavailableGpuBackends += name
            }
            for ((name, load) in cpuBackends.sortedByDescending { it.first == manualName }) {
                val backend = runCatching { load(assets) }
                    .onFailure { AppLog.w(TAG, "$name 加载失败", it) }
                    .getOrNull()
                if (backend != null) return PoseAnalyzer(backend)
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
            val future = executor.submit<PoseBackend?> { load() }
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
                // The blocked call cannot be interrupted; a late result is abandoned (one
                // backend is leaked per process).
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
