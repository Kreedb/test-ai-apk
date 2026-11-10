package com.example.minimalapk.examples

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.ln
import kotlin.math.sqrt

internal const val PERSONALIZATION_INPUT = 224
internal const val BOTTLENECK_SIZE = 7 * 7 * 1280
internal const val CLASS_COUNT = 4
internal const val MAX_TRAIN_BATCH = 20
internal const val TRAIN_STEPS = 150

internal data class TrainingSample(val bottleneck: FloatArray, val label: Int)

/**
 * Runs the `load` signature of tensorflow/examples/lite/examples/model_personalization. That
 * subgraph has no custom ops. Its `train` signature does (FlexBroadcastGradientArgs), so the
 * classification head is trained in Kotlin by [SoftmaxHead] instead of feeding Flex to LiteRT.
 */
internal class BottleneckExtractor(modelFile: File) : Closeable {
    private val interpreter = Interpreter(modelFile, Interpreter.Options().setNumThreads(2))
    private val feature = ByteBuffer.allocateDirect(
        PERSONALIZATION_INPUT * PERSONALIZATION_INPUT * 3 * Float.SIZE_BYTES,
    ).order(ByteOrder.nativeOrder())
    private val bottleneck = Array(1) { FloatArray(BOTTLENECK_SIZE) }

    @Synchronized
    fun extract(bitmap: Bitmap): FloatArray {
        fillFeature(bitmap)
        interpreter.runSignature(
            mapOf("feature" to feature),
            mapOf("bottleneck" to bottleneck),
            "load",
        )
        return bottleneck[0].copyOf()
    }

    private fun fillFeature(bitmap: Bitmap) {
        val pixels = IntArray(PERSONALIZATION_INPUT * PERSONALIZATION_INPUT)
        squareBitmap(bitmap, PERSONALIZATION_INPUT)
            .getPixels(pixels, 0, PERSONALIZATION_INPUT, 0, 0, PERSONALIZATION_INPUT, PERSONALIZATION_INPUT)
        feature.position(0)
        val floats = feature.asFloatBuffer()
        for (pixel in pixels) {
            floats.put(((pixel shr 16) and 0xff) / 255f)
            floats.put(((pixel shr 8) and 0xff) / 255f)
            floats.put((pixel and 0xff) / 255f)
        }
        feature.position(0)
    }

    @Synchronized
    override fun close() = interpreter.close()
}

/**
 * Softmax regression trained on-device with Adam (same learning rate as the upstream head).
 * The heavy MobileNetV2 backbone stays frozen in [BottleneckExtractor]; only the 4×62720 weights
 * and 4 biases change, so a handful of images is enough and no data leaves the device.
 */
internal class SoftmaxHead(
    private val features: Int,
    private val classes: Int = CLASS_COUNT,
    private val learningRate: Float = 0.001f,
) {
    private val weights = FloatArray(features * classes)
    private val biases = FloatArray(classes)
    private val gradWeights = FloatArray(weights.size)
    private val gradBiases = FloatArray(classes)
    private val firstWeights = FloatArray(weights.size)
    private val secondWeights = FloatArray(weights.size)
    private val firstBiases = FloatArray(classes)
    private val secondBiases = FloatArray(classes)
    private var firstPower = 1f
    private var secondPower = 1f

    @Synchronized
    fun reset() {
        weights.fill(0f)
        biases.fill(0f)
        Arrays.fill(gradWeights, 0f)
        Arrays.fill(gradBiases, 0f)
        Arrays.fill(firstWeights, 0f)
        Arrays.fill(secondWeights, 0f)
        Arrays.fill(firstBiases, 0f)
        Arrays.fill(secondBiases, 0f)
        firstPower = 1f
        secondPower = 1f
    }

    /** One Adam step over the batch; returns the mean cross-entropy loss. */
    @Synchronized
    fun train(samples: List<TrainingSample>): Float {
        require(samples.isNotEmpty()) { "训练样本为空" }
        var loss = 0f
        for (sample in samples) {
            val bottleneck = sample.bottleneck
            require(bottleneck.size == features) {
                "特征维度不符：期望 $features，实际 ${bottleneck.size}"
            }
            val target = sample.label.coerceIn(0, classes - 1)
            val probabilities = predictInternal(bottleneck)
            loss -= ln(probabilities[target].coerceAtLeast(1e-7f))
            for (label in 0 until classes) {
                val delta = probabilities[label] - if (label == target) 1f else 0f
                gradBiases[label] += delta
                val offset = label * features
                for (index in 0 until features) {
                    gradWeights[offset + index] += delta * bottleneck[index]
                }
            }
        }
        val scale = 1f / samples.size
        firstPower *= BETA1
        secondPower *= BETA2
        val biasCorrection1 = 1f - firstPower
        val biasCorrection2 = 1f - secondPower
        for (index in weights.indices) {
            val gradient = gradWeights[index] * scale
            firstWeights[index] = BETA1 * firstWeights[index] + (1f - BETA1) * gradient
            secondWeights[index] = BETA2 * secondWeights[index] + (1f - BETA2) * gradient * gradient
            weights[index] -= learningRate *
                (firstWeights[index] / biasCorrection1) /
                (sqrt(secondWeights[index] / biasCorrection2) + EPSILON)
        }
        for (label in biases.indices) {
            val gradient = gradBiases[label] * scale
            firstBiases[label] = BETA1 * firstBiases[label] + (1f - BETA1) * gradient
            secondBiases[label] = BETA2 * secondBiases[label] + (1f - BETA2) * gradient * gradient
            biases[label] -= learningRate *
                (firstBiases[label] / biasCorrection1) /
                (sqrt(secondBiases[label] / biasCorrection2) + EPSILON)
        }
        Arrays.fill(gradWeights, 0f)
        Arrays.fill(gradBiases, 0f)
        return loss * scale
    }

    @Synchronized
    fun predict(bottleneck: FloatArray): FloatArray = predictInternal(bottleneck)

    private fun predictInternal(bottleneck: FloatArray): FloatArray {
        require(bottleneck.size == features) {
            "特征维度不符：期望 $features，实际 ${bottleneck.size}"
        }
        val logits = FloatArray(classes)
        for (label in 0 until classes) {
            val offset = label * features
            var dot = biases[label]
            for (index in 0 until features) dot += weights[offset + index] * bottleneck[index]
            logits[label] = dot
        }
        return softmax(logits)
    }

    private companion object {
        const val BETA1 = 0.9f
        const val BETA2 = 0.999f
        const val EPSILON = 1e-8f
    }
}

/**
 * Same batching as the upstream helper: the batch is at most [MAX_TRAIN_BATCH] samples and the
 * last batch repeats the tail so every training step sees a full batch.
 */
internal fun trainingBatches(samples: List<TrainingSample>, batchSize: Int): List<List<TrainingSample>> {
    if (samples.isEmpty()) return emptyList()
    val size = batchSize.coerceIn(1, minOf(MAX_TRAIN_BATCH, samples.size))
    return buildList {
        var index = 0
        while (index < samples.size) {
            val end = index + size
            add(
                if (end >= samples.size) {
                    samples.subList(samples.size - size, samples.size)
                } else {
                    samples.subList(index, end)
                },
            )
            index = end
        }
    }
}

internal fun softmax(logits: FloatArray): FloatArray {
    if (logits.isEmpty()) return logits
    val max = logits.max()
    val exp = FloatArray(logits.size) { kotlin.math.exp(logits[it] - max) }
    val sum = exp.sum()
    return FloatArray(exp.size) { exp[it] / sum }
}
