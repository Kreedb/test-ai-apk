package com.example.minimalapk.pose

import android.content.Context
import androidx.core.content.edit
import java.io.File

/** How the raw head's four box channels are laid out; both stay in input-pixel coordinates. */
enum class BoxFormat(val label: String) {
    /** `left, top, right, bottom` */
    XYXY("角点 xyxy"),

    /** `centerX, centerY, width, height` (YOLOv8/v11 exports) */
    CENTER("中心宽高 cxcywh"),
}

/** How the pose head orders channels and anchors; both have the same element count. */
enum class OutputLayout(val label: String) {
    /** `[1, 5+3K, N]`: onnx2tf's layout and the decoder's in-place fast path. */
    VALUE_MAJOR("通道优先 [1,C,N]"),

    /** `[1, N, 5+3K]`: the untransposed pose head some exports keep. */
    ANCHOR_MAJOR("锚点优先 [1,N,C]"),
}

/**
 * Everything the pose pipeline needs from one model's tensor contract plus its runtime options.
 * Input geometry, decoder layout and letterboxing all read this descriptor, so a new model never
 * needs changes in those places.
 */
data class ModelSpec(
    val id: String,
    val name: String,
    /** `asset:<assets path>` for a bundled model, `file:<filesDir/models name>` for an import. */
    val source: String,
    val inputSize: Int,
    val anchors: Int,
    val keypoints: Int = PoseNet.KEYPOINT_COUNT,
    val enabled: Boolean = false,
    val scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
    val keypointThreshold: Float = DEFAULT_KEYPOINT_THRESHOLD,
    /** Consecutive agreeing frames before the published presence flips; 1 disables debounce. */
    val debounceFrames: Int = 1,
    val boxFormat: BoxFormat = BoxFormat.XYXY,
    val outputLayout: OutputLayout = OutputLayout.VALUE_MAJOR,
) {
    val valuesPerAnchor: Int get() = 5 + keypoints * 3
    val imported: Boolean get() = source.startsWith(FILE_PREFIX)
    val assetPath: String? get() = if (imported) null else source.removePrefix(ASSET_PREFIX)
    val fileName: String? get() = if (imported) source.removePrefix(FILE_PREFIX) else null

    companion object {
        const val DEFAULT_SCORE_THRESHOLD = 0.35f
        const val DEFAULT_KEYPOINT_THRESHOLD = 0.5f
        internal const val ASSET_PREFIX = "asset:"
        internal const val FILE_PREFIX = "file:"
    }
}

/**
 * The model list shown in 插件 → 自定义模型. Bundled models live in code; the store only carries the
 * user's options. Asset identity/input geometry come from the APK; decoder options are editable
 * for bundled and imported models alike and are checked against the file before saving/loading.
 */
object ModelStore {
    /** Same-frame serial inference: two 640 pose models already halve the frame rate. */
    const val MAX_ENABLED = 2

    private const val PREFS = "app_settings"
    private const val KEY = "pose_models"
    private const val SEPARATOR = '\t'
    private const val LINE = '\n'

    // Only the primary model ships in the APK; others are downloaded from ModelCatalog on demand.
    val builtIn = listOf(
        ModelSpec(
            id = "yolo26n_pose",
            name = "YOLO26n-pose 640",
            source = ModelSpec.ASSET_PREFIX + "models/yolo26n_pose.tflite",
            inputSize = 640,
            anchors = 8400,
            enabled = true,
        ),
    )

    @Synchronized
    fun load(context: Context): List<ModelSpec> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return builtIn
        return restore(decode(stored)) { modelFile(context, it).isFile }
    }

    internal fun restore(saved: List<ModelSpec>, fileExists: (ModelSpec) -> Boolean): List<ModelSpec> {
        if (saved.isEmpty()) return builtIn
        val specs = saved.mapNotNull { entry ->
            val bundled = builtIn.firstOrNull { it.id == entry.id }
            when {
                bundled != null -> entry.copy(
                    name = bundled.name,
                    source = bundled.source,
                    inputSize = bundled.inputSize,
                )
                fileExists(entry) -> entry
                // An import whose file is gone (cleared data, manual cleanup) would fail at open.
                else -> null
            }
        }
        return specs + builtIn.filter { bundled -> specs.none { it.id == bundled.id } }
    }

    @Synchronized
    fun save(context: Context, specs: List<ModelSpec>) {
        requireEnabledCapacity(specs)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, encode(specs)) }
    }

    /** Used only by the accepted delete transaction, off the UI thread; failure restores memory
     * as well as disk so a failed SharedPreferences commit cannot silently unregister a model. */
    @Synchronized
    internal fun saveDeletion(context: Context, specs: List<ModelSpec>) {
        requireEnabledCapacity(specs)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = preferences.getString(KEY, null)
        if (!preferences.edit().putString(KEY, encode(specs)).commit()) {
            val restore = preferences.edit()
            if (old == null) restore.remove(KEY) else restore.putString(KEY, old)
            check(restore.commit()) { "保存删除列表及恢复旧偏好失败，模型文件已保留，请检查存储" }
            error("无法保存删除列表，原模型与偏好已保留")
        }
    }

    internal fun requireEnabledCapacity(specs: List<ModelSpec>) {
        require(specs.count { it.enabled } <= MAX_ENABLED) {
            "姿态加速管线最多同时加载 $MAX_ENABLED 个模型，请先关闭其它姿态模型"
        }
    }

    /** Identical model bytes must not reset saved toggles, thresholds or corrected decoder options. */
    internal fun withImported(specs: List<ModelSpec>, imported: ModelSpec): List<ModelSpec> =
        if (specs.any { it.id == imported.id }) specs else specs + imported

    internal fun encode(specs: List<ModelSpec>): String = specs.joinToString(LINE.toString()) { spec ->
        listOf(
            spec.id,
            spec.name,
            spec.source,
            spec.inputSize.toString(),
            spec.anchors.toString(),
            spec.keypoints.toString(),
            if (spec.enabled) "1" else "0",
            spec.scoreThreshold.toString(),
            spec.keypointThreshold.toString(),
            spec.debounceFrames.toString(),
            spec.boxFormat.name,
            spec.outputLayout.name,
        ).joinToString(SEPARATOR.toString())
    }

    internal fun decode(value: String): List<ModelSpec> = value.lineSequence().mapNotNull { line ->
        val fields = line.split(SEPARATOR)
        // Older builds stored 11 fields; keep their entries and default the missing layout.
        if (fields.size !in 11..12) return@mapNotNull null
        runCatching {
            ModelSpec(
                id = fields[0],
                name = fields[1],
                source = fields[2],
                inputSize = fields[3].toInt(),
                anchors = fields[4].toInt(),
                keypoints = fields[5].toInt(),
                enabled = fields[6] == "1",
                scoreThreshold = fields[7].toFloat(),
                keypointThreshold = fields[8].toFloat(),
                debounceFrames = fields[9].toInt(),
                boxFormat = BoxFormat.valueOf(fields[10]),
                outputLayout = if (fields.size > 11) {
                    OutputLayout.valueOf(fields[11])
                } else {
                    OutputLayout.VALUE_MAJOR
                },
            )
        }.getOrNull()
    }.toList()
}

/** Tensor count alone is insufficient: transposed heads have the same count but decode differently. */
internal fun validatePoseModelConfig(spec: ModelSpec, graph: GraphDescription) {
    require(spec.scoreThreshold in 0f..1f && spec.keypointThreshold in 0f..1f) { "置信度阈值必须在 0–1" }
    require(spec.debounceFrames in 1..30) { "防抖帧数必须在 1–30" }
    require(spec.anchors in 1..1_000_000 && spec.keypoints in 1..100) { "锚点数或关键点数超出支持范围" }
    val signature = graph.signatures.first { it.key.isEmpty() }
    val input = signature.inputs.singleOrNull() ?: error("姿态模型需要单输入")
    val output = signature.outputs.singleOrNull() ?: error("姿态模型需要单输出")
    require(input.type == 0 && output.type == 0) { "姿态模型需要 FLOAT32 输入与输出" }
    require(input.shape == listOf(1, spec.inputSize, spec.inputSize, 3)) { "输入尺寸与模型文件不一致：${input.shape}" }
    val expected = when (spec.outputLayout) {
        OutputLayout.VALUE_MAJOR -> listOf(1, spec.valuesPerAnchor, spec.anchors)
        OutputLayout.ANCHOR_MAJOR -> listOf(1, spec.anchors, spec.valuesPerAnchor)
    }
    require(output.shape == expected) {
        "当前配置要求 $expected，模型实际输出 ${output.shape}；请核对锚点数、关键点数与布局"
    }
}

internal fun modelFile(context: Context, spec: ModelSpec): File =
    File(File(context.filesDir, MODELS_DIR), spec.fileName ?: "")

internal const val MODELS_DIR = "models"
