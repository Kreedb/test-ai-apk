package com.example.minimalapk.plugins

import android.content.Context
import android.graphics.Bitmap
import com.example.minimalapk.examples.BOTTLENECK_SIZE
import com.example.minimalapk.examples.BottleneckExtractor
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.SoftmaxHead
import com.example.minimalapk.examples.personalizationCheckpoint
import com.example.minimalapk.examples.personalizationPercent
import com.example.minimalapk.examples.personalizationResultText
import com.example.minimalapk.examples.fittedImage
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.AssetTransfer
import java.io.Closeable
import java.util.concurrent.CancellationException

/**
 * Camera consumer for the on-device personalization head. The plugin dialog owns training and the
 * checkpoint; this consumer only restores it and never retrains or rewrites it.
 */
internal class PersonalizationPlugin(private val application: Context) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.PERSONALIZATION, "设备端学习",
        setOf(PluginCapability.CAMERA_FRAMES, PluginCapability.TEXT),
    )

    private data class Request(val context: PluginContext)

    private class Runner(
        val extractor: BottleneckExtractor,
        val head: SoftmaxHead,
    ) : Closeable {
        override fun close() = extractor.close()
    }

    private val model = AsyncModelSlot<Request, Runner>(
        "PersonalizationModel",
        open = { request ->
            val transfer = AssetTransfer {
                if (!request.context.isCurrent()) throw CancellationException("设备端学习请求已失效")
            }
            transfer.checkpoint()
            val checkpoint = personalizationCheckpoint(application)
            transfer.checkpoint()
            if (!checkpoint.isFile) {
                throw IllegalStateException("未就绪：打开设备端学习，为 4 类命名、添加照片后训练并保存")
            }
            val head = SoftmaxHead(BOTTLENECK_SIZE)
            head.restore(checkpoint)
            val modelFile = ExampleDownload.ensure(application, ExampleCatalog.personalization, transfer)
            transfer.checkpoint()
            Runner(BottleneckExtractor(modelFile), head)
        },
        ready = { request, runner ->
            if (request.context.isCurrent()) {
                val profile = runner.head.profile
                request.context.publish(
                    PluginOutput(
                        System.nanoTime(),
                        listOf(
                            PluginMessage(
                                if (profile == null) {
                                    "已恢复旧分类器：类别 1–4 尚未命名，请在插件内确认原类别对应关系"
                                } else {
                                    "已就绪：${profile.categoryNames.joinToString(" / ")}；" +
                                        "对准画面中央的单个物品，低于 ${personalizationPercent(profile.confidenceThreshold)} 显示未知"
                                },
                                MessageKind.STATUS,
                            ),
                        ),
                    ),
                )
            }
        },
        failed = { request, error ->
            if (error is CancellationException) return@AsyncModelSlot
            AppLog.e("Personalization", "分类器加载或识别失败", error)
            val detail = error.message ?: "设备端学习模型加载失败"
            request.context.publish(
                PluginOutput(
                    System.nanoTime(),
                    listOf(
                        PluginMessage(
                            if (detail.startsWith("未就绪")) detail else "未就绪：$detail",
                            MessageKind.ERROR,
                        ),
                    ),
                ),
            )
        },
    )

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> {
                if (!context.isCurrent()) return
                context.publish(
                    PluginOutput(
                        System.nanoTime(),
                        listOf(
                            PluginMessage(
                                "正在加载设备端学习模型与训练存档" +
                                    "（首次需下载约 ${ExampleCatalog.personalization.sizeBytes / 1024 / 1024} MB）",
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
                // Match training's bounded 512px photo, independent of another plugin's analysis
                // size/letterbox. Rotation is applied before the extractor's same center crop.
                val photo = personalizationCameraImage(frame)
                val scores = try {
                    runner.head.predict(runner.extractor.extract(photo))
                } finally {
                    photo.recycle()
                }
                context.publish(
                    PluginOutput(
                        timestampNanos = frame.timestampNanos,
                        messages = listOf(
                            PluginMessage(personalizationResultText(scores, runner.head.profile)),
                        ),
                    ),
                )
            }
            else -> Unit
        }
    }

    override fun close() = model.close()
}

/** Owned upright photo for the same extractor used during training; the caller recycles it. */
internal fun personalizationCameraImage(frame: CameraFrame): Bitmap {
    val swapped = frame.source.rotationDegrees % 180 != 0
    val width = if (swapped) frame.raw.height else frame.raw.width
    val height = if (swapped) frame.raw.width else frame.raw.height
    val scale = minOf(1f, 512f / maxOf(width, height))
    return fittedImage(frame.raw.bitmap, (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1), frame.source.rotationDegrees).bitmap
}
