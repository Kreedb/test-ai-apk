package com.example.minimalapk.models

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.example.minimalapk.pose.GraphSignature
import org.tensorflow.lite.Interpreter

/**
 * Camera-side runner for a workbench/example model. One CPU Interpreter stays open across frames;
 * only models whose inputs all come from an image can be fed the camera preview.
 */
internal class LiveConfiguredModel(
    model: ConfiguredModel,
    config: ModelConfig,
    graph: GraphSignature,
) : AutoCloseable {
    private val config: ModelConfig
    private val graph: GraphSignature
    private val interpreter: Interpreter

    init {
        cameraModelProblem(config)?.let { error(it) }
        this.config = config
        this.graph = graph
        val opened = Interpreter(model.file, Interpreter.Options().setNumThreads(2))
        try {
            configureInputs(opened, this.config)
        } catch (t: Throwable) {
            opened.close()
            throw t
        }
        interpreter = opened
    }

    fun run(source: Bitmap): ModelRunResult {
        val images = mutableMapOf<Int, Bitmap>()
        val inputs = Array<Any>(graph.inputs.size) { index ->
            val input = config.inputs[index]
            val (width, height, channels) = imageDimensions(input)
            val resized = source.scale(width, height)
            images[index] = resized
            val pixels = IntArray(width * height)
            resized.getPixels(pixels, 0, width, 0, 0, width, height)
            encodeTensor(imageValues(pixels, channels, input), graph.inputs[index], input.source)
        }
        return execute(interpreter, config, graph, inputs, images, transparent = true)
    }

    override fun close() = interpreter.close()
}

/** Returns a user-facing reason when a model cannot take a camera frame, else null. */
internal fun cameraModelProblem(config: ModelConfig): String? =
    if (config.inputs.all { it.source == InputSource.IMAGE }) null
    else "示例模型叠加只支持 IMAGE 输入的模型；音频、文本或数值模型请在 示例 → 模型工作台 中运行"