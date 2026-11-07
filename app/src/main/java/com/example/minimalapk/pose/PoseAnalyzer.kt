package com.example.minimalapk.pose

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.createBitmap
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
        val output = runModel(pixels)
        val inferred = System.nanoTime()
        val detections = PoseDecoder.decode(output)
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

    private fun runModel(pixels: IntArray): FloatArray =
        PosePostprocess.topDetections(backend.analyze(pixels))

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

        fun open(context: Context): PoseAnalyzer = synchronized(openLock) {
            val assets = context.assets
            val gpuDeadline = System.nanoTime() + GPU_PROBE_BUDGET_MS * 1_000_000L
            for ((name, load) in gpuBackends) {
                if (name in unavailableGpuBackends) continue
                val remainingMs = (gpuDeadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) break
                val backend = loadWithTimeout(name, remainingMs) { load(assets) }
                if (backend != null) return PoseAnalyzer(backend)
                unavailableGpuBackends += name
            }
            for ((name, load) in cpuBackends) {
                val backend = runCatching { load(assets) }
                    .onFailure { Log.w(TAG, "$name load failed", it) }
                    .getOrNull()
                if (backend != null) return PoseAnalyzer(backend)
            }
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
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // The blocked call cannot be interrupted; a late result is abandoned (one
                // backend is leaked per process).
                Log.w(TAG, "$name load timed out")
                null
            } catch (e: Exception) {
                Log.w(TAG, "$name load failed", e)
                null
            } finally {
                executor.shutdown()
            }
        }
    }
}
