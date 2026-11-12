package com.example.minimalapk.examples

import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.decodeTensor
import com.example.minimalapk.models.wavSamples
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val YAMNET_WINDOW_SAMPLES = 15_600
internal const val YAMNET_SAMPLE_RATE = 16_000

internal data class SoundLabel(val label: String, val score: Float)

internal interface SoundClassifier : Closeable {
    val windowSamples: Int
    val sampleRate: Int
    val labels: List<String>
    fun classify(samples: FloatArray): List<SoundLabel>
}

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
 * from the pinned model's associated file. The built-in scene never reads a user's editable
 * custom-model properties (sample rate, signature, shape or label overrides).
 */
internal class AudioClassificationRunner(modelFile: File) : SoundClassifier {
    /** The self-check also exercises this same fixed scene pipeline for a catalog install. */
    constructor(model: ConfiguredModel) : this(model.file)

    /** Number of mono samples one inference window consumes at [sampleRate]. */
    override val windowSamples: Int = YAMNET_WINDOW_SAMPLES
    override val sampleRate: Int = YAMNET_SAMPLE_RATE
    override val labels: List<String> = ExampleDownload.labels(modelFile, ExampleCatalog.audio)
    private val input = ByteBuffer.allocateDirect(windowSamples * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(labels.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

    private val interpreter = Interpreter(modelFile, Interpreter.Options().setNumThreads(2)).also { runtime ->
        try {
            require(labels.isNotEmpty() && labels.all { it.isNotBlank() }) { "声音识别模型缺少类别标签文件" }
            require(runtime.inputTensorCount == 1 && runtime.outputTensorCount == 1 &&
                runtime.getInputTensor(0).shape().contentEquals(intArrayOf(windowSamples)) &&
                runtime.getInputTensor(0).dataType() == DataType.FLOAT32 &&
                runtime.getOutputTensor(0).shape().contentEquals(intArrayOf(1, labels.size)) &&
                runtime.getOutputTensor(0).dataType() == DataType.FLOAT32) {
                "YAMNet 张量与 15600 个 16 kHz 单声道采样 → 521 类 FLOAT32 契约不符"
            }
        } catch (error: Throwable) {
            runtime.close()
            throw error
        }
    }

    /** Returns every class score sorted from highest to lowest. Pure inference; no Android UI. */
    @Synchronized
    override fun classify(samples: FloatArray): List<SoundLabel> {
        require(samples.size == windowSamples) { "声音识别需要 $windowSamples 个采样点，实际 ${samples.size}" }
        require(samples.all { it.isFinite() && it in -1f..1f }) { "声音输入必须是 [-1,1] 的有限 PCM 采样" }
        input.rewind()
        input.asFloatBuffer().put(samples)
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        return rankedSoundLabels(labels, decodeTensor(output, 0, 0f, 0L))
    }

    @Synchronized
    override fun close() = interpreter.close()
}

/** Same bounded window as the microphone; the shared decoder pads short files with silence. */
internal fun yamnetWavWindow(bytes: ByteArray): FloatArray =
    wavSamples(bytes, YAMNET_SAMPLE_RATE, YAMNET_WINDOW_SAMPLES).let { values ->
        FloatArray(values.size) { values[it].toFloat() }
    }
