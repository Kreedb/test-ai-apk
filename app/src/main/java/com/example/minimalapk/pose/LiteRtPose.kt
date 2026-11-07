package com.example.minimalapk.pose

import android.content.res.AssetManager
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer

internal class LiteRtBackend private constructor(
    private val model: CompiledModel,
    private val input: TensorBuffer,
    private val output: TensorBuffer,
    override val name: String,
) : PoseBackend {
    // The TFLite graph takes NHWC float RGB; onnx2tf emits the pose head as [1, 56, 8400].
    private val inputValues = FloatArray(PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE * 3)

    override fun analyze(pixels: IntArray): FloatArray {
        argbToRgbFloat(pixels, inputValues)
        input.writeFloat(inputValues)
        model.run(listOf(input), listOf(output))
        return transposeOutput(output.readFloat())
    }

    override fun close() {
        input.close()
        output.close()
        model.close()
    }

    companion object {
        fun open(assets: AssetManager, useGpu: Boolean): LiteRtBackend {
            val options = if (useGpu) {
                CompiledModel.Options(Accelerator.GPU)
            } else {
                CompiledModel.Options(Accelerator.CPU)
            }
            val model = CompiledModel.create(assets, PoseAnalyzer.TFLITE_MODEL_ASSET, options)
            val input = try {
                model.createInputBuffers().first()
            } catch (e: Throwable) {
                model.close()
                throw e
            }
            val output = try {
                model.createOutputBuffers().first()
            } catch (e: Throwable) {
                input.close()
                model.close()
                throw e
            }
            val name = if (useGpu) "LiteRT (GPU)" else "LiteRT (CPU)"
            return LiteRtBackend(model, input, output, name)
        }
    }
}

/** Converts ARGB pixels to the interleaved NHWC RGB floats expected by the TFLite model. */
internal fun argbToRgbFloat(pixels: IntArray, out: FloatArray) {
    require(out.size >= pixels.size * 3) { "Output buffer is too small" }
    for (index in pixels.indices) {
        val pixel = pixels[index]
        out[index * 3] = ((pixel shr 16) and 0xff) / 255f
        out[index * 3 + 1] = ((pixel shr 8) and 0xff) / 255f
        out[index * 3 + 2] = (pixel and 0xff) / 255f
    }
}

internal fun transposeOutput(raw: FloatArray): FloatArray {
    require(raw.size == PosePostprocess.ANCHOR_COUNT * PosePostprocess.VALUES_PER_ANCHOR) {
        "Unexpected LiteRT output size: ${raw.size}"
    }
    val output = FloatArray(raw.size)
    for (anchor in 0 until PosePostprocess.ANCHOR_COUNT) {
        for (value in 0 until PosePostprocess.VALUES_PER_ANCHOR) {
            output[anchor * PosePostprocess.VALUES_PER_ANCHOR + value] =
                raw[value * PosePostprocess.ANCHOR_COUNT + anchor]
        }
    }
    return output
}
