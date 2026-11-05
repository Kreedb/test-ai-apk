package com.example.minimalapk.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
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

class PoseAnalyzer(context: Context) : AutoCloseable {
    private val handle = NcnnPose.create(context.assets, PARAM_ASSET, MODEL_ASSET)
    val executionProvider: String = "ncnn (CPU)"
    private val lock = Any()
    private var closed = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE)

    init {
        check(handle != 0L) { "无法加载 ncnn 模型" }
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
            NcnnPose.close(handle)
        }
    }

    private fun runModel(pixels: IntArray): FloatArray =
        PosePostprocess.topDetections(NcnnPose.analyze(handle, pixels))

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
    }
}
