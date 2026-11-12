package com.example.minimalapk.examples

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

internal const val SR_INPUT_SIZE = 50
internal const val SR_OUTPUT_SIZE = 200

/** Fixed-shape tile seam shared by the camera pipeline and native/instrumented checks. */
internal interface SuperResolutionTiles : Closeable {
    fun runTile(pixels: IntArray, width: Int, height: Int, left: Int, top: Int): Bitmap
}

/**
 * ESRGAN from tensorflow/examples/lite/examples/super_resolution: float32 RGB in 0..255,
 * 50×50 in, 200×200 out. The model is fixed-shape, so the stock Interpreter is enough.
 */
internal class SuperResolutionRunner(modelFile: File) : SuperResolutionTiles {
    private val interpreter = Interpreter(modelFile, Interpreter.Options().setNumThreads(2))
    private val input = Array(1) { Array(SR_INPUT_SIZE) { Array(SR_INPUT_SIZE) { FloatArray(3) } } }
    private val output = Array(1) { Array(SR_OUTPUT_SIZE) { Array(SR_OUTPUT_SIZE) { FloatArray(3) } } }

    init {
        try {
            require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1 &&
                interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, SR_INPUT_SIZE, SR_INPUT_SIZE, 3)) &&
                interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, SR_OUTPUT_SIZE, SR_OUTPUT_SIZE, 3)) &&
                interpreter.getInputTensor(0).dataType() == DataType.FLOAT32 &&
                interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32) { "ESRGAN 张量与 50→200 RGB 契约不符" }
        } catch (error: Throwable) {
            interpreter.close()
            throw error
        }
    }

    /** Single-tile diagnostic entry point. Camera callers use runTile with full-photo coordinates. */
    @Synchronized
    fun run(bitmap: Bitmap): Bitmap {
        require(bitmap.width == SR_INPUT_SIZE && bitmap.height == SR_INPUT_SIZE) {
            "单次 ESRGAN 输入必须为 50×50；整张照片请使用有界分块管线"
        }
        val pixels = IntArray(SR_INPUT_SIZE * SR_INPUT_SIZE)
        bitmap.getPixels(pixels, 0, SR_INPUT_SIZE, 0, 0, SR_INPUT_SIZE, SR_INPUT_SIZE)
        return runTile(pixels, SR_INPUT_SIZE, SR_INPUT_SIZE, 0, 0)
    }

    @Synchronized
    override fun runTile(pixels: IntArray, width: Int, height: Int, left: Int, top: Int): Bitmap {
        require(width > 0 && height > 0 && pixels.size == width * height)
        for (y in 0 until SR_INPUT_SIZE) for (x in 0 until SR_INPUT_SIZE) {
            // Repeat edge pixels only beyond the photo; neighboring tiles share real context.
            val pixel = pixels[(top + y).coerceIn(0, height - 1) * width + (left + x).coerceIn(0, width - 1)]
            val rgb = input[0][y][x]
            rgb[0] = ((pixel shr 16) and 0xff).toFloat()
            rgb[1] = ((pixel shr 8) and 0xff).toFloat()
            rgb[2] = (pixel and 0xff).toFloat()
        }
        interpreter.run(input, output)
        return superResolutionBitmap(output[0])
    }

    @Synchronized
    override fun close() = interpreter.close()
}

internal fun superResolutionBitmap(pixels: Array<Array<FloatArray>>): Bitmap {
    val argb = IntArray(SR_OUTPUT_SIZE * SR_OUTPUT_SIZE)
    for (y in 0 until SR_OUTPUT_SIZE) {
        for (x in 0 until SR_OUTPUT_SIZE) {
            val rgb = pixels[y][x]
            argb[y * SR_OUTPUT_SIZE + x] = (0xFF shl 24) or
                (channelToByte(rgb[0]) shl 16) or
                (channelToByte(rgb[1]) shl 8) or
                channelToByte(rgb[2])
        }
    }
    return Bitmap.createBitmap(argb, SR_OUTPUT_SIZE, SR_OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
}

/** The upstream example clamps the raw 0..255 output to a byte. */
internal fun channelToByte(value: Float): Int {
    require(value.isFinite()) { "ESRGAN 输出包含 NaN/Infinity" }
    return value.roundToInt().coerceIn(0, 255)
}
