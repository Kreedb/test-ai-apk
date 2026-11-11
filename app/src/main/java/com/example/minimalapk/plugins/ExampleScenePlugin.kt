package com.example.minimalapk.plugins

import android.content.Context
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.ExampleModel
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.LiveConfiguredModel
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.overlay
import java.util.concurrent.CancellationException

/**
 * One card per visual example. All four instances share this runner: the fixed [ExampleModel] is
 * downloaded once into the configured-model store, then loaded and run through the same
 * LiveConfiguredModel camera path as the custom-model plugin.
 */
internal data class ExampleSceneSpec(
    val id: String,
    val name: String,
    val example: ExampleModel,
    /** Exactly what the model does; shown in the dialog and camera status. */
    val description: String,
    val thresholdEnabled: Boolean,
    val textHint: String? = null,
)

internal object ExampleScenes {
    val moveNet = ExampleSceneSpec(
        PluginIds.MOVENET_SCENE, "动作观察", ExampleCatalog.moveNet,
        "MoveNet 单人姿态：叠加 17 个关键点并显示平均置信度；只跟踪置信度最高的一个人。",
        thresholdEnabled = true,
        textHint = "17 个关键点；低于阈值的关键点与骨骼不绘制",
    )
    val classification = ExampleSceneSpec(
        PluginIds.IMAGE_CLASSIFICATION_SCENE, "物品识别", ExampleCatalog.classification,
        "MobileNet 图片分类：只在模型内置的 ImageNet 标签集中给出前 5 类及置信度，" +
            "不是通用视觉理解，也不输出位置。",
        thresholdEnabled = false,
        textHint = "ImageNet 标签分类（仅模型内置类别，不是通用视觉理解）",
    )
    val detection = ExampleSceneSpec(
        PluginIds.OBJECT_DETECTION_SCENE, "场景检测", ExampleCatalog.detection,
        "SSD MobileNet 目标检测：按模型内置标签集绘制检测框与类别，" +
            "框坐标与相机预览同源，不是分割或跟踪。",
        thresholdEnabled = true,
        textHint = "检测框与标签来自模型内置标签集，按预览坐标叠加",
    )
    val segmentation = ExampleSceneSpec(
        PluginIds.IMAGE_SEGMENTATION_SCENE, "场景分割", ExampleCatalog.segmentation,
        "DeepLab V3 逐像素分类：按模型内置 21 类输出半透明掩码，背景透明；" +
            "阈值以下的像素不显示。",
        thresholdEnabled = true,
        textHint = "逐像素分类掩码；低于阈值的像素保持透明",
    )

    val all = listOf(moveNet, classification, detection, segmentation)
}

internal class ExampleScenePlugin(
    private val application: Context,
    private val spec: ExampleSceneSpec,
) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        spec.id, spec.name,
        setOf(PluginCapability.CAMERA_FRAMES, PluginCapability.TEXT, PluginCapability.LAYER),
    )

    private data class Request(val context: PluginContext)

    private val model = AsyncModelSlot<Request, LiveConfiguredModel>(
        "ExampleScene:${spec.id}",
        open = { request ->
            if (!request.context.isCurrent()) throw CancellationException("场景模型请求已失效")
            // Same cache/install path as the workbench; an existing config is never overwritten.
            val installed = ExampleDownload.install(application, spec.example)
            if (!request.context.isCurrent()) throw CancellationException("场景模型请求已失效")
            val description = ConfiguredModelStore.describe(installed)
            val config = ModelConfig.decode(installed.configFile.readText(), description)
            val threshold = if (spec.thresholdEnabled) {
                SceneThresholdSettings.from(request.context.settings).threshold ?: 0.3f
            } else null
            LiveConfiguredModel(
                installed, config,
                description.signatures.single { it.key == config.signature },
                threshold,
            )
        },
        ready = { request, _ ->
            request.context.publish(
                PluginOutput(
                    System.nanoTime(),
                    listOf(PluginMessage("已就绪：${spec.name}（${spec.example.name}）", MessageKind.STATUS)),
                ),
            )
        },
        failed = { request, error ->
            request.context.publish(
                PluginOutput(
                    System.nanoTime(),
                    listOf(PluginMessage("未就绪：${error.message ?: "${spec.name}模型加载失败"}", MessageKind.ERROR)),
                ),
            )
        },
    )

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> synchronized(this) {
                if (!context.isCurrent()) return
                context.publish(
                    PluginOutput(
                        System.nanoTime(),
                        listOf(
                            PluginMessage(
                                "正在下载/加载 ${spec.name}模型（首次约 ${spec.example.sizeBytes / 1024 / 1024} MB）",
                                MessageKind.STATUS,
                            ),
                        ),
                    ),
                )
                model.set(Request(context))
            }
            is PluginEvent.Stopped -> model.set(null)
            is PluginEvent.Frame -> model.runIfReady { request, runner ->
                if (request.context.token != context.token || !context.isCurrent()) return@runIfReady
                val frame = event.frame
                val output = runner.run(frame.image.bitmap)
                val hint = spec.textHint?.let { listOf(PluginMessage(it)) } ?: emptyList()
                val lines = output.text.lineSequence().filter { it.isNotBlank() }
                    .take(PluginHost.MAX_MESSAGES - hint.size).map { PluginMessage(it) }.toList()
                context.publish(
                    PluginOutput(
                        timestampNanos = frame.timestampNanos,
                        messages = (hint + lines).take(PluginHost.MAX_MESSAGES),
                        layers = output.overlay(frame.source.space.width)?.let {
                            listOf(PluginLayer(frame.source, BitmapImage(it)))
                        } ?: emptyList(),
                    ),
                )
            }
            else -> Unit
        }
    }

    override fun close() = model.close()
}
