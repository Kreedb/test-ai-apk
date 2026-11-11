package com.example.minimalapk.examples

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale

internal const val DEFAULT_PERSONALIZATION_CONFIDENCE = 0.75f
internal const val MIN_PERSONALIZATION_CONFIDENCE = 0.5f
internal const val MAX_PERSONALIZATION_CONFIDENCE = 0.95f
internal const val MAX_CATEGORY_NAME_LENGTH = 40

// writeUTF uses at most three bytes per UTF-16 code unit. Bound the entire file before reading it.
internal const val MAX_PERSONALIZATION_PROFILE_BYTES =
    CLASS_COUNT * (2 + MAX_CATEGORY_NAME_LENGTH * 3 + Int.SIZE_BYTES) + Float.SIZE_BYTES + 1

/** Names are ordered exactly like the head's output neurons; never infer names for a v1 head. */
internal data class PersonalizationProfile(
    val categoryNames: List<String>,
    val confidenceThreshold: Float = DEFAULT_PERSONALIZATION_CONFIDENCE,
    /** The last training session's counts, not retained photos. Unknown for upgraded v1 heads. */
    val trainingSampleCounts: List<Int>? = null,
) {
    init {
        require(personalizationCategoryProblem(categoryNames) == null) { "请填写 4 个不同的类别名称" }
        require(categoryNames.all { it == it.trim() }) { "类别名称两端不能包含空格" }
        require(confidenceThreshold in MIN_PERSONALIZATION_CONFIDENCE..MAX_PERSONALIZATION_CONFIDENCE) {
            "识别阈值须为 50%–95%"
        }
        require(trainingSampleCounts == null || trainingSampleCounts.size == CLASS_COUNT &&
            trainingSampleCounts.all { it in MIN_SAMPLES_PER_CATEGORY..MAX_SAMPLES_PER_CATEGORY }) {
            "训练样本计数无效"
        }
    }
}

internal fun personalizationCategoryProblem(names: List<String>): String? {
    if (names.size != CLASS_COUNT) return "需要 4 个类别"
    val trimmed = names.map(String::trim)
    if (trimmed.any { it.isEmpty() }) return "请先为 4 个类别填写名称"
    if (trimmed.any { it.length > MAX_CATEGORY_NAME_LENGTH || it.any(Char::isISOControl) }) {
        return "每个名称限 1–$MAX_CATEGORY_NAME_LENGTH 个字符，不能包含换行或控制字符"
    }
    if (trimmed.map { it.lowercase(Locale.ROOT) }.distinct().size != CLASS_COUNT) {
        return "类别名称不能重复"
    }
    return null
}

internal fun DataOutputStream.writePersonalizationProfile(profile: PersonalizationProfile) {
    profile.categoryNames.forEach(::writeUTF)
    writeFloat(profile.confidenceThreshold)
    writeBoolean(profile.trainingSampleCounts != null)
    profile.trainingSampleCounts?.forEach(::writeInt)
}

internal fun DataInputStream.readPersonalizationProfile(): PersonalizationProfile {
    val names = List(CLASS_COUNT) { readUTF() }
    val threshold = readFloat()
    val hasCounts = readUnsignedByte()
    require(hasCounts in 0..1) { "训练样本计数标记无效" }
    val counts = if (hasCounts == 1) List(CLASS_COUNT) { readInt() } else null
    return PersonalizationProfile(names, threshold, counts)
}

internal fun personalizationCategoryName(profile: PersonalizationProfile?, index: Int): String =
    profile?.categoryNames?.get(index) ?: "旧类别 ${index + 1}（未命名）"

internal fun personalizationPercent(value: Float): String =
    String.format(Locale.US, "%.1f%%", value * 100f)

/** Shared by photo testing and the camera so the saved threshold has one meaning. */
internal fun personalizationResultText(scores: FloatArray, profile: PersonalizationProfile?): String {
    require(scores.size == CLASS_COUNT && scores.all { it.isFinite() && it in 0f..1f }) {
        "识别结果无效"
    }
    val best = scores.indices.maxBy { scores[it] }
    val name = personalizationCategoryName(profile, best)
    val confidence = personalizationPercent(scores[best])
    val threshold = profile?.confidenceThreshold ?: DEFAULT_PERSONALIZATION_CONFIDENCE
    return if (scores[best] < threshold) {
        "未知 · 最高匹配：$name，置信度 $confidence（低于 ${personalizationPercent(threshold)}）"
    } else {
        "$name · 置信度 $confidence（阈值 ${personalizationPercent(threshold)}）"
    }
}

/** Checkpoint written by the on-device trainer; the camera plugin only reads it. */
internal fun personalizationCheckpoint(context: Context): File =
    File(File(context.filesDir, "examples"), "personalization-head.bin")
