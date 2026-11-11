package com.example.minimalapk.plugins

import android.content.Context
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.LiveConfiguredModel
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.overlay
import java.util.concurrent.CancellationException

/** Runs the configured workbench model chosen in 插件 → 自定义模型 on camera frames. */
internal class ConfiguredModelPlugin(private val application: Context) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.CONFIGURED_MODEL, "自定义模型",
        setOf(PluginCapability.CAMERA_FRAMES, PluginCapability.TEXT, PluginCapability.LAYER),
    )
    private data class Request(val context: PluginContext)
    private var current: PluginContext? = null
    private val lock = Any()
    private val model = AsyncModelSlot<Request, LiveConfiguredModel>(
        "ConfiguredModelOwner",
        open = { request ->
            if (!request.context.isCurrent()) throw CancellationException("Model request superseded")
            val id = request.context.settings.config[CONFIGURED_MODEL_ID_KEY].orEmpty()
            if (id.isBlank()) error("未就绪：请在 插件 → 自定义模型 中选择图片模型")
            val selected = ConfiguredModelStore.list(ConfiguredModelStore.directory(application))
                .firstOrNull { it.id == id } ?: error("未就绪：所选模型已不存在，请重新选择")
            val description = ConfiguredModelStore.describe(selected)
            val config = ModelConfig.decode(selected.configFile.readText(), description)
            if (!request.context.isCurrent()) throw CancellationException("Model request superseded")
            LiveConfiguredModel(selected, config, description.signatures.single { it.key == config.signature })
        },
        ready = { request, _ -> request.context.publish(PluginOutput(
            System.nanoTime(), listOf(PluginMessage("自定义模型已就绪", MessageKind.STATUS)),
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
                context.publish(PluginOutput(System.nanoTime(), listOf(PluginMessage("正在加载自定义模型", MessageKind.STATUS))))
                model.set(Request(context))
            }
            is PluginEvent.Stopped -> synchronized(lock) {
                if (current?.token == context.token) {
                    current = null
                    model.set(null)
                }
            }
            is PluginEvent.Frame -> model.runIfReady { request, runner ->
                if (request.context.token != context.token || !context.isCurrent()) return@runIfReady
                val frame = event.frame
                val output = runner.run(frame.image.bitmap)
                context.publish(PluginOutput(
                    timestampNanos = frame.timestampNanos,
                    messages = output.text.lineSequence().filter { it.isNotBlank() }
                        .take(PluginHost.MAX_MESSAGES).map { PluginMessage(it) }.toList(),
                    layers = output.overlay(frame.source.space.width)?.let {
                        listOf(PluginLayer(frame.source, BitmapImage(it)))
                    } ?: emptyList(),
                ))
            }
            else -> Unit
        }
    }

    override fun close() = model.close()
}
