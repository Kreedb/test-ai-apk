package com.example.minimalapk.examples

import android.graphics.Bitmap
import com.example.minimalapk.models.sha256
import com.example.minimalapk.models.writeAtomically
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
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
internal const val MIN_SAMPLES_PER_CATEGORY = 5
internal const val MAX_SAMPLES_PER_CATEGORY = 50
internal const val MAX_PERSONALIZATION_SAMPLES = CLASS_COUNT * MAX_SAMPLES_PER_CATEGORY

internal data class TrainingSample(val bottleneck: FloatArray, val label: Int) {
    // Hash model-visible content, not a URI: choosing another copy of a photo must not increase
    // the distinct sample count or let the same photo train two contradictory labels.
    val fingerprint: String = ByteBuffer.allocate(bottleneck.size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(bottleneck) }.array().sha256()
}

/** Enforced again at training time, independently of the collection UI's button state. */
internal fun validatePersonalizationSamples(samples: List<TrainingSample>): List<Int> {
    require(samples.size <= MAX_PERSONALIZATION_SAMPLES) { "最多添加 $MAX_PERSONALIZATION_SAMPLES 张照片" }
    require(samples.all { it.label in 0 until CLASS_COUNT }) { "样本类别无效" }
    val features = samples.firstOrNull()?.bottleneck?.size ?: 0
    require(samples.all { it.bottleneck.isNotEmpty() && it.bottleneck.size == features && it.bottleneck.all(Float::isFinite) }) {
        "样本特征无效，请重新选择照片"
    }
    require(samples.map { it.fingerprint }.distinct().size == samples.size) {
        "照片重复：同一照片只能属于一个类别，且不能重复计数"
    }
    val counts = List(CLASS_COUNT) { label -> samples.count { it.label == label } }
    require(counts.all { it in MIN_SAMPLES_PER_CATEGORY..MAX_SAMPLES_PER_CATEGORY }) {
        "每类需要 $MIN_SAMPLES_PER_CATEGORY–$MAX_SAMPLES_PER_CATEGORY 张不同照片"
    }
    return counts
}

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
        val square = squareBitmap(bitmap, PERSONALIZATION_INPUT)
        try {
            square.getPixels(pixels, 0, PERSONALIZATION_INPUT, 0, 0, PERSONALIZATION_INPUT, PERSONALIZATION_INPUT)
        } finally {
            if (square !== bitmap) square.recycle()
        }
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
 * and 4 biases change. Recognition quality depends on representative labeled photos.
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
    var profile: PersonalizationProfile? = null
        private set

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
        profile = null
    }

    /** One Adam step over the batch; returns the mean cross-entropy loss. */
    @Synchronized
    fun train(samples: List<TrainingSample>): Float {
        require(samples.isNotEmpty()) { "训练样本为空" }
        require(samples.all { it.label in 0 until classes && it.bottleneck.size == features && it.bottleneck.all(Float::isFinite) }) {
            "训练样本的类别或特征无效"
        }
        var loss = 0f
        for (sample in samples) {
            val bottleneck = sample.bottleneck
            require(bottleneck.size == features) {
                "特征维度不符：期望 $features，实际 ${bottleneck.size}"
            }
            val target = sample.label
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

    @Synchronized
    fun save(file: File, profile: PersonalizationProfile? = this.profile) {
        require(profile == null || classes == CLASS_COUNT) { "类别名称与分类头维度不匹配" }
        val arrays = listOf(weights, biases, firstWeights, secondWeights, firstBiases, secondBiases)
        require(arrays.all { it.all(Float::isFinite) } && firstPower in 0f..1f && secondPower in 0f..1f) {
            "训练结果包含无效数值，原存档已保留"
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(if (profile == null) LEGACY_CHECKPOINT_VERSION else CHECKPOINT_VERSION)
            output.writeInt(features)
            output.writeInt(classes)
            arrays.forEach { values ->
                values.forEach { output.writeFloat(it) }
            }
            output.writeFloat(firstPower)
            output.writeFloat(secondPower)
            profile?.let { output.writePersonalizationProfile(it) }
        }
        // Names, threshold, weights and optimizer are one atomic replacement, never sidecar files.
        file.writeAtomically(bytes.toByteArray())
        this.profile = profile
    }

    @Synchronized
    fun restore(file: File) {
        val arrays = listOf(weights, biases, firstWeights, secondWeights, firstBiases, secondBiases)
        val legacySize = 20L + arrays.sumOf { it.size.toLong() } * Float.SIZE_BYTES
        try {
            // Read length and content from the same inode, even if a trainer atomically replaces it.
            file.inputStream().use { source ->
                val size = source.channel.size()
                require(size in legacySize..(legacySize + MAX_PERSONALIZATION_PROFILE_BYTES)) { "训练存档大小不符" }
                val input = DataInputStream(source.buffered())
                val version = input.readInt()
                require(version in LEGACY_CHECKPOINT_VERSION..CHECKPOINT_VERSION &&
                    input.readInt() == features && input.readInt() == classes) {
                    "训练存档版本或维度不匹配"
                }
                require(version != LEGACY_CHECKPOINT_VERSION || size == legacySize) { "训练存档大小不符" }
                require(version != CHECKPOINT_VERSION || classes == CLASS_COUNT) { "类别名称与分类头维度不匹配" }
                val loaded = arrays.map { values -> FloatArray(values.size) { input.readFloat() } }
                val first = input.readFloat()
                val second = input.readFloat()
                require(loaded.all { it.all(Float::isFinite) } && first in 0f..1f && second in 0f..1f) {
                    "训练存档包含无效数值"
                }
                val loadedProfile = if (version == CHECKPOINT_VERSION) input.readPersonalizationProfile() else null
                require(input.read() == -1) { "训练存档包含多余数据" }
                // Commit only after the entire file (including names/counts) is validated.
                loaded.forEachIndexed { index, values -> values.copyInto(arrays[index]) }
                gradWeights.fill(0f)
                gradBiases.fill(0f)
                firstPower = first
                secondPower = second
                profile = loadedProfile
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("训练存档不完整或无法读取", error)
        }
    }

    private fun predictInternal(bottleneck: FloatArray): FloatArray {
        require(bottleneck.size == features) {
            "特征维度不符：期望 $features，实际 ${bottleneck.size}"
        }
        require(bottleneck.all(Float::isFinite)) { "照片特征包含无效数值" }
        val logits = FloatArray(classes)
        for (label in 0 until classes) {
            val offset = label * features
            var dot = biases[label]
            for (index in 0 until features) dot += weights[offset + index] * bottleneck[index]
            logits[label] = dot
        }
        require(logits.all(Float::isFinite)) { "分类器产生无效分数，请检查训练存档与照片" }
        return softmax(logits)
    }

    private companion object {
        const val LEGACY_CHECKPOINT_VERSION = 1
        const val CHECKPOINT_VERSION = 2
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
