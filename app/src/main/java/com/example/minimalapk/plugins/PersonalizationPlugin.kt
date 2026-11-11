package com.example.minimalapk.plugins

import android.content.Context
import com.example.minimalapk.examples.BOTTLENECK_SIZE
import com.example.minimalapk.examples.BottleneckExtractor
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.SoftmaxHead
import com.example.minimalapk.examples.personalizationCheckpoint
import java.io.Closeable
import java.util.Locale
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
            if (!request.context.isCurrent()) throw CancellationException("设备端学习请求已失效")
            val checkpoint = personalizationCheckpoint(application)
            if (!checkpoint.isFile) throw IllegalStateException("未就绪：请先在插件中训练")
            val modelFile = ExampleDownload.ensure(application, ExampleCatalog.personalization)
            if (!request.context.isCurrent()) throw CancellationException("设备端学习请求已失效")
            val head = SoftmaxHead(BOTTLENECK_SIZE)
            head.restore(checkpoint)
            Runner(BottleneckExtractor(modelFile), head)
        },
        ready = { request, _ ->
            if (request.context.isCurrent()) {
                request.context.publish(
                    PluginOutput(
                        System.nanoTime(),
                        listOf(PluginMessage("已就绪：使用本机训练的 4 类分类头", MessageKind.STATUS)),
                    ),
                )
            }
        },
        failed = { request, error ->
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
                val scores = runner.head.predict(runner.extractor.extract(frame.image.bitmap))
                val best = scores.indices.maxByOrNull { scores[it] } ?: return@runIfReady
                context.publish(
                    PluginOutput(
                        timestampNanos = frame.timestampNanos,
                        messages = listOf(
                            PluginMessage("类别 ${best + 1}：${percent(scores[best])}（设备端学习）"),
                        ),
                    ),
                )
            }
            else -> Unit
        }
    }

    private fun percent(value: Float): String =
        String.format(Locale.US, "%.1f%%", value.coerceIn(0f, 1f) * 100f)

    override fun close() = model.close()
}
