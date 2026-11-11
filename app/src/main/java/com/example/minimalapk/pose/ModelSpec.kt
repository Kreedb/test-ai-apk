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
 * user's options, so a changed asset path or shape in a later version wins over stale settings.
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

    fun load(context: Context): List<ModelSpec> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return builtIn
        val saved = decode(stored)
        if (saved.isEmpty()) return builtIn
        val specs = saved.mapNotNull { entry ->
            val bundled = builtIn.firstOrNull { it.id == entry.id }
            when {
                bundled != null -> entry.copy(
                    name = bundled.name,
                    source = bundled.source,
                    inputSize = bundled.inputSize,
                    anchors = bundled.anchors,
                    keypoints = bundled.keypoints,
                    outputLayout = bundled.outputLayout,
                )
                modelFile(context, entry).isFile -> entry
                // An import whose file is gone (cleared data, manual cleanup) would fail at open.
                else -> null
            }
        }
        return specs + builtIn.filter { bundled -> specs.none { it.id == bundled.id } }
    }

    fun save(context: Context, specs: List<ModelSpec>) {
        var enabled = 0
        val capped = specs.map { spec ->
            val keep = spec.enabled && enabled < MAX_ENABLED
            if (keep) enabled++
            spec.copy(enabled = keep)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, encode(capped)) }
    }

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

internal fun modelFile(context: Context, spec: ModelSpec): File =
    File(File(context.filesDir, MODELS_DIR), spec.fileName ?: "")

internal const val MODELS_DIR = "models"
