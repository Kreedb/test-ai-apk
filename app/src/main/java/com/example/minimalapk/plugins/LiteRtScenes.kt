package com.example.minimalapk.plugins

import android.content.Context
import com.example.minimalapk.models.AssetCatalog
import com.example.minimalapk.models.AssetSet
import com.example.minimalapk.models.AssetLease
import com.example.minimalapk.models.AssetResourceStore
import java.io.File
import java.util.Locale

internal enum class LiteRtSceneKind { VISION, IMAGE, CAPTIONS, SPEECH, AUDIO, TEXT }

internal data class LiteRtScene(
    val id: String,
    val name: String,
    val purpose: String,
    val kind: LiteRtSceneKind,
    val tasks: List<String>,
)

internal object LiteRtScenes {
    val vision = LiteRtScene(
        PluginIds.LITERT_VISION, "画面分析", "识物、文字、植物、人群与画面匹配", LiteRtSceneKind.VISION,
        listOf("object-detection", "image-classification", "fine-grained-classification", "ocr",
            "image-tagging", "image-quality", "crowd-counting", "dense-feature-visualization",
            "image-matching", "head-pose-estimation", "face-liveness-anti-spoofing", "video-action-recognition"),
    )
    val image = LiteRtScene(
        PluginIds.LITERT_IMAGE, "画面增强与分区", "去雾、抠图、分割、深度与按压超分对比", LiteRtSceneKind.IMAGE,
        listOf("super-resolution", "super-resolution-real-esrgan", "image-dehazing", "background-removal",
            "portrait-matting", "semantic-segmentation", "instance-segmentation", "lane-detection",
            "monocular-geometry-estimation"),
    )
    val captions = LiteRtScene(
        PluginIds.LITERT_CAPTIONS, "实时英语字幕", "Zipformer · 有界语音窗口转写", LiteRtSceneKind.CAPTIONS,
        listOf("speech-recognition"),
    )
    val speech = LiteRtScene(
        PluginIds.LITERT_SPEECH, "神经语音朗读", "Matcha · 英语短句本地合成与播放", LiteRtSceneKind.SPEECH,
        listOf("text-to-speech"),
    )
    val audio = LiteRtScene(
        PluginIds.LITERT_AUDIO, "录音处理与试听", "短录音降噪、分离、编解码、音高与音符", LiteRtSceneKind.AUDIO,
        listOf("speech-enhancement", "audio-source-separation", "audio-codec", "audio-classification",
            "pitch-detection", "music-transcription"),
    )
    val text = LiteRtScene(
        PluginIds.LITERT_TEXT, "文本决策", "根据输入与自定选项给出分类建议", LiteRtSceneKind.TEXT,
        listOf("text-decision-laya-multilingual", "text-decision-julia-1", "text-decision-gliclass-edge",
            "text-decision-open-decision", "text-decision-gliner-decide"),
    )
    val all = listOf(vision, image, captions, speech, audio, text)
    val taskIds: Set<String> = all.flatMap { it.tasks }.toSet()
    val faceTasks = setOf("head-pose-estimation", "face-liveness-anti-spoofing")
    val superResolutionTasks = setOf("super-resolution", "super-resolution-real-esrgan")
    val faceRoi = PhotoRoi(0.25f, 0.18f, 0.75f, 0.82f)
    val livenessFaceRoi = PhotoRoi(
        0.5f - (faceRoi.right - faceRoi.left) / 5.4f, 0.5f - (faceRoi.bottom - faceRoi.top) / 5.4f,
        0.5f + (faceRoi.right - faceRoi.left) / 5.4f, 0.5f + (faceRoi.bottom - faceRoi.top) / 5.4f,
    )

    fun task(settings: PluginSettings, scene: LiteRtScene): String =
        settings.config["task_id"] ?: scene.tasks.first()

    fun windowSeconds(settings: PluginSettings, captions: Boolean = false): Int =
        (settings.config["window_seconds"]?.toIntOrNull() ?: if (captions) 4 else 5)
            .coerceIn(2, if (captions) 8 else 10)

    fun explanation(task: String): String = when (task) {
        "video-action-recognition" -> "开始观察后最多采样 30 帧（每秒最多 5 帧），显示片段动作类别；停止后清除循环状态。"
        "face-liveness-anti-spoofing" -> "脸放入中央青框；模型只读取黄色框内的 2.7× 上下文 ROI，分数是模型判断，不作为身份认证。"
        "head-pose-estimation" -> "将一张脸放入中央人脸框；只分析框内人脸，报告朝向角度。"
        "image-matching" -> "先在相机捕获参考画面，再移动相机并分析当前画面，显示两幅快照间的匹配。"
        "super-resolution", "super-resolution-real-esrgan" -> "按住预览处理整张来源快照；松手或取消立即恢复实时画面。输入长边最多 256 像素，处理范围与源画面一致。"
        "speech-recognition" -> "英语模型，每 2–8 秒窗口转写；慢推理只保留最新待处理窗口，可能跳过语音，不是连续无损听写。"
        "text-to-speech" -> "英语短句合成；只在显式运行后播放。播放时暂停共享麦克风，取消、后台或停用立即停止。"
        "audio-classification" -> "PANNs 声音标签；不是语音转写。短于模型 10 秒窗口的输入按上游补齐。"
        "pitch-detection" -> "录下单音旋律，查看音高、音名和置信度。"
        "music-transcription" -> "录下旋律，显示 MIDI 音符、起止时间；不是歌词识别。"
        "audio-source-separation" -> "短录音分离为对话、音乐与音效，各轨可独立试听；共享输入为单声道 16 kHz，模型输入重采样到 44.1 kHz。"
        "audio-codec" -> "对录音执行 DAC 编码/解码，试听原始与重建音频。"
        "speech-enhancement" -> "录下语音后用 CMGAN 降噪，试听处理前后音频。"
        "lane-detection" -> "道路图片的区域/车道分区，仅作画面分析。"
        "monocular-geometry-estimation" -> "单目相对深度可视化，不是经过标定的距离测量。"
        "image-quality" -> "NIMA 美学分布与评分，属于模型偏好。"
        "dense-feature-visualization" -> "DINOv2 特征 PCA 色图，颜色不代表类别标签。"
        else -> if (task.startsWith("text-decision-")) "填写文本、问题与至少两个选项；结果仅展示，不执行命令。模型语言/窗口限制见来源说明。"
            else "点击相机中的“分析当前画面”，结果与输入快照一起显示；实时原始预览持续可用。"
    }
}

/** Reads the same installed task directories as AssetInstaller; never initiates a download. */
internal class LiteRtAssets(private val context: Context) {
    val root = File(context.filesDir, "model_assets")
    private val resources = AssetResourceStore.get(context)
    val sets: List<AssetSet> by lazy { AssetCatalog.load(context) }
    fun requireReady(task: String, scene: LiteRtScene): AssetSet {
        require(task in scene.tasks) { "该场景不支持任务：$task" }
        val set = sets.firstOrNull { it.id == task } ?: error("模型目录缺少任务：$task")
        check(resources.installedDirectory(set) != null) {
            "${set.name} 未就绪或正在更新（${liteRtSize(set.totalBytes)}）；请到 插件 → 自定义模型 → 从远程下载模型 检查该任务"
        }
        return set
    }
    fun <R : AutoCloseable> open(
        context: PluginContext,
        scene: LiteRtScene,
        create: (String, File, String) -> R,
    ): LiteRtInstalledModel<R> {
        val set = requireReady(LiteRtScenes.task(context.settings, scene), scene)
        val lease = resources.acquire(set)
        try {
            check(context.isCurrent()) { "模型请求已失效" }
            LiteRtMemory.blockingReason(set.id, LiteRtMemory.snapshot(this.context))?.let { error(it) }
            return LiteRtInstalledModel(create(set.id, lease.directory, backend(context.settings, set)), lease)
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
    }
    fun backend(settings: PluginSettings, set: AssetSet): String {
        val selected = settings.config["backend"] ?: "auto"
        return if (selected == "auto") set.backend else selected.also {
            require(it in setOf("cpu", "gpu")) { "请选择 CPU 或 GPU" }
        }
    }
}

/** Close every native/mapped reader before allowing a resource set to be replaced or deleted. */
internal class LiteRtInstalledModel<R : AutoCloseable>(val engine: R, private val lease: AssetLease) : AutoCloseable {
    override fun close() {
        try { engine.close() } finally { lease.close() }
    }
}

internal fun liteRtSize(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024)
    String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
else String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))

internal fun liteRtTiming(ms: Double, backend: String): String =
    String.format(Locale.ROOT, "%s · %.1f ms（本次调用）", backend, ms)
