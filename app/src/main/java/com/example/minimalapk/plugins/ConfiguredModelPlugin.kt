package com.example.minimalapk.plugins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.InputSource
import com.example.minimalapk.models.LiveConfiguredModel
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.ModelRunResult
import java.util.concurrent.CancellationException

/** All enabled image models share one serial owner; a failed model does not stop its siblings. */
internal class ConfiguredModelPlugin(private val application: Context) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.CONFIGURED_MODEL, "自定义模型",
        setOf(PluginCapability.CAMERA_FRAMES, PluginCapability.TEXT, PluginCapability.LAYER),
    )
    private data class Request(val context: PluginContext)
    private var current: PluginContext? = null
    private val lock = Any()
    private val model = AsyncModelSlot<Request, ConfiguredModelGroup>(
        "ConfiguredModelOwner",
        open = { request ->
            ConfiguredModelGroup(
                ConfiguredModelStore.list(ConfiguredModelStore.directory(application)),
                request.context::isCurrent,
            ) { selected ->
                val description = ConfiguredModelStore.describe(selected)
                val config = ModelConfig.decode(selected.configFile.readText(), description)
                require(config.inputs.all { it.source == InputSource.IMAGE }) {
                    "需要文件输入，请点击模型卡片配置并运行；相机仅支持 IMAGE 输入"
                }
                val runner = LiveConfiguredModel(selected, config, description.signatures.single { it.key == config.signature })
                object : ConfiguredFrameRunner {
                    override fun run(image: PluginImage) = runner.run(image.bitmap)
                    override fun close() = runner.close()
                }
            }
        },
        ready = { request, group -> request.context.publish(PluginOutput(
            System.nanoTime(), boundedModelMessages(group.status),
        )) },
        failed = { request, error ->
            val message = error.message ?: "自定义模型失败"
            request.context.publish(PluginOutput(
                System.nanoTime(),
                listOf(PluginMessage(if (message.startsWith("未就绪")) message else "未就绪：$message", MessageKind.ERROR)),
            ))
        },
    )

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> synchronized(lock) {
                if (!context.isCurrent()) return
                current = context
                context.publish(PluginOutput(System.nanoTime()))
                model.set(Request(context))
            }
            is PluginEvent.Stopped -> synchronized(lock) {
                if (current?.token == context.token) {
                    current = null
                    model.set(null)
                }
            }
            is PluginEvent.Frame -> model.runIfReady { request, group ->
                if (request.context.token != context.token || !context.isCurrent()) return@runIfReady
                val frame = event.frame
                val messages = mutableListOf<PluginMessage>()
                var overlay: Bitmap? = null
                var canvas: Canvas? = null
                val target = RectF(0f, 0f, frame.source.space.width.toFloat(), frame.source.space.height.toFloat())
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                group.run(frame.image, context::isCurrent) { selected, result ->
                    val output = result.getOrNull()
                    if (output == null) {
                        messages += modelOutputMessages(selected.name, result.exceptionOrNull()?.message.orEmpty(), MessageKind.ERROR)
                    } else {
                        messages += modelOutputMessages(selected.name, output.text)
                        // Composite every output into one source-sized layer, so the host's layer
                        // bound cannot silently discard the fifth (or later) enabled model.
                        try {
                            output.images.forEach { (_, bitmap) ->
                                if (overlay == null) {
                                    overlay = Bitmap.createBitmap(frame.source.space.width, frame.source.space.height, Bitmap.Config.ARGB_8888)
                                    canvas = Canvas(checkNotNull(overlay))
                                }
                                checkNotNull(canvas).drawBitmap(bitmap, null, target, paint)
                            }
                        } finally {
                            output.images.map { it.second }.distinct().filter { it !== frame.image.bitmap }.forEach { it.recycle() }
                        }
                    }
                }
                if (!context.isCurrent()) {
                    overlay?.recycle()
                    return@runIfReady
                }
                val accepted = context.publish(PluginOutput(
                    timestampNanos = frame.timestampNanos,
                    messages = boundedModelMessages(messages),
                    layers = overlay?.let {
                        listOf(PluginLayer(frame.source, BitmapImage(it)))
                    } ?: emptyList(),
                ))
                if (!accepted) overlay?.recycle()
            }
            else -> Unit
        }
    }

    override fun close() = model.close()
}

/** Narrow runtime seam also used by the JVM lifecycle/multi-model regression tests. */
internal interface ConfiguredFrameRunner : AutoCloseable {
    fun run(image: PluginImage): ModelRunResult
}

internal class ConfiguredModelGroup(
    models: List<ConfiguredModel>,
    stillNeeded: () -> Boolean,
    open: (ConfiguredModel) -> ConfiguredFrameRunner,
) : AutoCloseable {
    private class Entry(val model: ConfiguredModel, var runner: ConfiguredFrameRunner? = null, var failure: Throwable? = null)
    private val entries = mutableListOf<Entry>()

    init {
        try {
            models.filter { it.enabled }.forEach { model ->
                if (!stillNeeded()) throw CancellationException("Model request superseded")
                val entry = Entry(model).also { entries += it }
                try {
                    entry.runner = open(model)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    entry.failure = error
                    AppLog.w("ConfiguredModels", "${model.name} 加载失败", error)
                }
            }
            if (!stillNeeded()) throw CancellationException("Model request superseded")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    val status: List<PluginMessage> get() = entries.map { entry ->
        entry.failure?.let { PluginMessage("${entry.model.name}：${it.message}", MessageKind.ERROR) }
            ?: PluginMessage("${entry.model.name} 已就绪", MessageKind.STATUS)
    }

    fun run(image: PluginImage, stillNeeded: () -> Boolean, consume: (ConfiguredModel, Result<ModelRunResult>) -> Unit) {
        entries.forEach { entry ->
            if (!stillNeeded()) return
            val result = entry.failure?.let { Result.failure<ModelRunResult>(it) } ?: try {
                Result.success(checkNotNull(entry.runner).run(image))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Latch the failure until a configuration/session changes; keep the other models.
                entry.failure = error
                runCatching { entry.runner?.close() }
                entry.runner = null
                AppLog.w("ConfiguredModels", "${entry.model.name} 推理失败", error)
                Result.failure(error)
            }
            consume(entry.model, result)
        }
    }

    override fun close() {
        entries.forEach { entry ->
            runCatching { entry.runner?.close() }
            entry.runner = null
        }
    }
}

internal fun modelOutputMessages(name: String, text: String, kind: MessageKind = MessageKind.TEXT): List<PluginMessage> {
    val prefix = "${name.take(80)}："
    return text.lineSequence().filter { it.isNotBlank() }
        .flatMap { it.chunked(PluginHost.MAX_MESSAGE_CHARS - prefix.length) }
        .take(PluginHost.MAX_MESSAGES + 1).map { PluginMessage(prefix + it, kind) }.toList()
}

internal fun boundedModelMessages(messages: List<PluginMessage>): List<PluginMessage> {
    if (messages.size <= PluginHost.MAX_MESSAGES) return messages
    return listOf(PluginMessage(
        "结果较多，显示前 ${PluginHost.MAX_MESSAGES - 1} 行；可减少启用模型或在模型卡片中查看完整测试结果，失败详情见日志",
        MessageKind.STATUS,
    )) + messages.take(PluginHost.MAX_MESSAGES - 1)
}
