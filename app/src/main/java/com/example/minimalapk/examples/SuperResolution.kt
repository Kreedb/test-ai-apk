package com.example.minimalapk.examples

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

internal const val SR_INPUT_SIZE = 50
internal const val SR_OUTPUT_SIZE = 200

/**
 * ESRGAN from tensorflow/examples/lite/examples/super_resolution: float32 RGB in 0..255,
 * 50×50 in, 200×200 out. The model is fixed-shape, so the stock Interpreter is enough.
 */
internal class SuperResolutionRunner(modelFile: File) : Closeable {
    private val interpreter = Interpreter(modelFile, Interpreter.Options().setNumThreads(2))
    private val input = Array(1) { Array(SR_INPUT_SIZE) { Array(SR_INPUT_SIZE) { FloatArray(3) } } }
    private val output = Array(1) { Array(SR_OUTPUT_SIZE) { Array(SR_OUTPUT_SIZE) { FloatArray(3) } } }

    @Synchronized
    fun run(bitmap: Bitmap): Bitmap {
        val pixels = IntArray(SR_INPUT_SIZE * SR_INPUT_SIZE)
        squareBitmap(bitmap, SR_INPUT_SIZE)
            .getPixels(pixels, 0, SR_INPUT_SIZE, 0, 0, SR_INPUT_SIZE, SR_INPUT_SIZE)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val row = input[0][index / SR_INPUT_SIZE][index % SR_INPUT_SIZE]
            row[0] = ((pixel shr 16) and 0xff).toFloat()
            row[1] = ((pixel shr 8) and 0xff).toFloat()
            row[2] = (pixel and 0xff).toFloat()
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
internal fun channelToByte(value: Float): Int = value.roundToInt().coerceIn(0, 255)
