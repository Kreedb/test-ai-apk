package com.example.minimalapk.pose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

data class PoseFrame(
    val bitmap: Bitmap,
    val detections: List<PoseDetection>,
)

class PoseAnalyzer(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = context.assets.open(MODEL_ASSET).use { asset ->
        val bytes = asset.readBytes()
        OrtSession.SessionOptions().use { options ->
            environment.createSession(bytes, options)
        }
    }
    private val inputName = session.inputNames.first()
    private val lock = Any()
    private var closed = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE)
    private val inputData = FloatArray(PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE * 3)

    fun analyze(source: Bitmap, rotationDegrees: Int): PoseFrame = synchronized(lock) {
        check(!closed) { "PoseAnalyzer is closed" }
        val input = createInput(source, rotationDegrees)
        val output = runModel(input.toFloatArray())
        PoseFrame(input, PoseDecoder.decode(output))
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { session.close() }
        }
    }

    private fun runModel(data: FloatArray): FloatArray {
        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, PoseNet.INPUT_SIZE.toLong(), PoseNet.INPUT_SIZE.toLong()),
        )
        return tensor.use {
            session.run(mapOf(inputName to it)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                buffer.rewind()
                FloatArray(buffer.remaining()).also(buffer::get)
            }
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

    private fun Bitmap.toFloatArray(): FloatArray {
        val planeSize = PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE
        getPixels(pixels, 0, PoseNet.INPUT_SIZE, 0, 0, PoseNet.INPUT_SIZE, PoseNet.INPUT_SIZE)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            inputData[index] = ((pixel shr 16) and 0xFF) / 255f
            inputData[planeSize + index] = ((pixel shr 8) and 0xFF) / 255f
            inputData[2 * planeSize + index] = (pixel and 0xFF) / 255f
        }
        return inputData
    }

    companion object {
        const val MODEL_ASSET = "models/yolo26n-pose.onnx"
    }
}
