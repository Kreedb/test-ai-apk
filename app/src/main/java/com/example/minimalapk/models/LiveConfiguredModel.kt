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
    /** Runtime score threshold for MoveNet/BOXES/SEGMENTATION renders; never rewrites the stored config. */
    thresholdOverride: Float? = null,
) : AutoCloseable {
    private val config: ModelConfig =
        if (thresholdOverride == null) config
        else config.copy(outputs = config.outputs.map { it.copy(threshold = thresholdOverride) })
    private val graph: GraphSignature
    private val interpreter: Interpreter

    init {
        cameraModelProblem(this.config)?.let { error(it) }
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
        try {
            val inputs = Array<Any>(graph.inputs.size) { index ->
                val input = config.inputs[index]
                val (width, height, channels) = imageDimensions(input)
                val resized = source.scale(width, height)
                images[index] = resized
                val pixels = IntArray(width * height)
                resized.getPixels(pixels, 0, width, 0, 0, width, height)
                encodeTensor(imageValues(pixels, channels, input), graph.inputs[index], input.source)
            }
            // Transparent renderers allocate independent outputs; the input resizes never escape.
            return execute(interpreter, config, graph, inputs, images, transparent = true)
        } finally {
            images.values.filter { it !== source }.distinct().forEach { it.recycle() }
        }
    }

    override fun close() = interpreter.close()
}

/** Returns a user-facing reason when a model cannot take a camera frame, else null. */
internal fun cameraModelProblem(config: ModelConfig): String? =
    if (config.inputs.all { it.source == InputSource.IMAGE }) null
    else "相机叠加只支持 IMAGE 输入；音频、文本或数值模型请在 插件 → 自定义模型 → 模型工作台 中运行"
