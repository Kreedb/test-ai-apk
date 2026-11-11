package com.example.minimalapk.examples

import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.InputSource
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.configureInputs
import com.example.minimalapk.models.decodeTensor
import com.example.minimalapk.models.encodeTensor
import com.example.minimalapk.models.shapedArray
import com.example.minimalapk.models.tensorElements
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class SoundLabel(val label: String, val score: Float)

/** Fail visibly rather than attach a plausible but shifted/truncated class list to scores. */
internal fun rankedSoundLabels(labels: List<String>, scores: DoubleArray): List<SoundLabel> {
    require(labels.size == scores.size && labels.all { it.isNotBlank() }) {
        "声音识别标签数 ${labels.size} 与输出类别数 ${scores.size} 不符"
    }
    require(scores.isNotEmpty() && scores.all { it.isFinite() }) { "声音识别输出无效" }
    return scores.indices.map { SoundLabel(labels[it], scores[it].toFloat()) }.sortedByDescending { it.score }
}

/**
 * YAMNet from tensorflow/examples/lite/examples/audio_classification. The input is one mono
 * waveform window; the output is independent per-class scores (not a transcription). Labels come
 * from the metadata zip / stored config, never from a hard-coded class list.
 */
internal class AudioClassificationRunner(model: ConfiguredModel) : Closeable {
    private val description = ConfiguredModelStore.describe(model)
    private val config = ModelConfig.decode(model.configFile.readText(), description)
    private val graph = description.signatures.firstOrNull { it.key == config.signature }
        ?: error("声音识别模型缺少签名")
    private val inputConfig = config.inputs.singleOrNull()
        ?: error("声音识别模型需要恰好一个音频输入")
    private val inputTensor = graph.inputs.singleOrNull()
        ?: error("声音识别模型需要恰好一个音频输入张量")
    private val outputTensor = graph.outputs.firstOrNull()
        ?: error("声音识别模型没有输出")

    /** Number of mono samples one inference window consumes at [sampleRate]. */
    val windowSamples: Int = tensorElements(inputConfig.shape)
    val sampleRate: Int = inputConfig.sampleRate
    val labels: List<String> = config.outputs.firstOrNull()?.labels?.takeIf { it.isNotEmpty() }
        ?: ExampleDownload.labels(model.file, ExampleCatalog.audio)

    private val interpreter = Interpreter(model.file, Interpreter.Options().setNumThreads(2)).also { runtime ->
        try {
            require(labels.isNotEmpty() && labels.all { it.isNotBlank() }) { "声音识别模型缺少类别标签文件" }
            configureInputs(runtime, config)
            val output = if (config.signature.isEmpty()) runtime.getOutputTensor(0)
            else runtime.getOutputTensorFromSignature(outputTensor.name, config.signature)
            require(labels.size == output.numElements()) { "声音识别标签数 ${labels.size} 与输出类别数 ${output.numElements()} 不符" }
        } catch (error: Throwable) {
            runtime.close()
            throw error
        }
    }

    /** Returns every class score sorted from highest to lowest. Pure inference; no Android UI. */
    @Synchronized
    fun classify(samples: FloatArray): List<SoundLabel> {
        require(samples.size == windowSamples) { "声音识别需要 $windowSamples 个采样点，实际 ${samples.size}" }
        val values = DoubleArray(samples.size) { samples[it].toDouble() }
        val input = encodeTensor(values, inputTensor, InputSource.AUDIO)
        if (config.signature.isEmpty()) {
            val target = interpreter.getOutputTensor(0)
            val output = ByteBuffer.allocateDirect(target.numBytes()).order(ByteOrder.nativeOrder())
            interpreter.runForMultipleInputsOutputs(arrayOf(input), mapOf(0 to output))
        } else {
            val value = if (inputConfig.shape != inputTensor.shape) {
                shapedArray(input, inputTensor.type, inputConfig.shape)
            } else input
            interpreter.runSignature(mapOf(inputTensor.name to value), emptyMap(), config.signature)
        }
        val tensor = if (config.signature.isEmpty()) interpreter.getOutputTensor(0)
        else interpreter.getOutputTensorFromSignature(outputTensor.name, config.signature)
        val quantization = tensor.quantizationParams()
        val scores = decodeTensor(
            tensor.asReadOnlyBuffer(), outputTensor.type,
            quantization.scale, quantization.zeroPoint.toLong(),
        )
        return rankedSoundLabels(labels, scores)
    }

    @Synchronized
    override fun close() = interpreter.close()
}
