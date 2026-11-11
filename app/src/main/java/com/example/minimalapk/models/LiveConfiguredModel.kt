package com.example.minimalapk.models

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.example.minimalapk.pose.GraphSignature

/** Numeric outputs retain their tensor identity; scene plugins must never parse the debug text. */
internal data class LiveTensorOutput(val shape: List<Int>, val values: DoubleArray)

/**
 * Camera-side runner for a configured/scene model. One CPU Interpreter stays open across frames;
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
    private val owner: ConfiguredInterpreter

    init {
        cameraModelProblem(this.config)?.let { error(it) }
        configuredExecutionProblem(this.config, graph)?.let { error(it) }
        this.graph = graph
        val opened = ConfiguredModelAccess.open(model)
        try {
            opened.run { configureInputs(it, this.config) }
        } catch (t: Throwable) {
            opened.close()
            throw t
        }
        owner = opened
    }

    fun run(source: Bitmap): ModelRunResult = withInputs(source) { inputs, images ->
        owner.run(discard = ModelRunResult::recycle) { interpreter ->
            execute(interpreter, config, graph, inputs, images, transparent = true)
        }
    }

    /** Fixed scene contracts use the same encoder and interpreter, with task-specific decoding. */
    fun runTensors(source: Bitmap): List<LiveTensorOutput> = withInputs(source) { inputs, _ ->
        owner.run { interpreter ->
            if (config.signature.isEmpty()) {
                interpreter.runForMultipleInputsOutputs(inputs, emptyMap())
            } else {
                val named = graph.inputs.mapIndexed { index, tensor ->
                    val buffer = inputs[index] as java.nio.ByteBuffer
                    tensor.name to if (config.inputs[index].shape == tensor.shape) buffer
                        else shapedArray(buffer, tensor.type, config.inputs[index].shape)
                }.toMap()
                interpreter.runSignature(named, emptyMap(), config.signature)
            }
            graph.outputs.mapIndexed { index, tensor ->
                val actual = if (config.signature.isEmpty()) interpreter.getOutputTensor(index)
                    else interpreter.getOutputTensorFromSignature(tensor.name, config.signature)
                val shape = actual.shape().toList()
                require(tensorElements(shape) <= MAX_TENSOR_ELEMENTS) { "场景输出张量超过上限" }
                val quantization = actual.quantizationParams()
                LiveTensorOutput(shape, decodeTensor(
                    actual.asReadOnlyBuffer(), tensor.type, quantization.scale, quantization.zeroPoint.toLong(),
                ))
            }
        }
    }

    private fun <T> withInputs(source: Bitmap, consume: (Array<Any>, Map<Int, Bitmap>) -> T): T {
        val images = mutableMapOf<Int, Bitmap>()
        try {
            val inputs = Array<Any>(graph.inputs.size) { index ->
                owner.cancellation.checkpoint()
                val input = config.inputs[index]
                val (width, height, channels) = imageDimensions(input)
                val resized = source.scale(width, height)
                images[index] = resized
                val pixels = IntArray(width * height)
                resized.getPixels(pixels, 0, width, 0, 0, width, height)
                encodeTensor(imageValues(pixels, channels, input), graph.inputs[index], input.source)
            }
            return consume(inputs, images)
        } finally {
            images.values.filter { it !== source }.distinct().forEach { it.recycle() }
        }
    }

    fun cancel() = owner.cancel()
    override fun close() = owner.close()
}

/** Returns a user-facing reason when a model cannot take a camera frame, else null. */
internal fun cameraModelProblem(config: ModelConfig): String? =
    if (config.inputs.all { it.source == InputSource.IMAGE }) null
    else "实时相机帧只支持 IMAGE 输入；请在 插件 → 自定义模型 → 对应模型配置 中运行文件/文本/数值输入；单输入 AUDIO 原始波形可接共享麦克风"
