package com.example.minimalapk.plugins

import android.content.Context
import com.example.minimalapk.litert.audio.MatchaEngine
import com.example.minimalapk.litert.audio.AudioControl

internal class LiteRtSpeechPlugin(application: Context) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.LITERT_SPEECH, "神经语音朗读", setOf(PluginCapability.TEXT, PluginCapability.ACTIONS, PluginCapability.MODEL_ASSETS),
        actions = setOf("run", "cancel", "retry"),
    )
    private val assets = LiteRtAssets(application)
    private val playback = LiteRtPlayback(application)
    private val actions = listOf(PluginAction("run", "合成并朗读英语短句"), PluginAction("cancel", "取消 / 停止播放"), PluginAction("retry", "重新加载模型"))
    private var activeToken: PluginToken? = null
    private val work = LiteRtWork<LiteRtInstalledModel<MatchaEngine>>(
        "LiteRtSpeech",
        open = { context ->
            assets.open(context, LiteRtScenes.speech) { _, directory, backend -> MatchaEngine(directory, backend) }
        }, status = ::status,
        ready = { status(it, "Matcha 已就绪；输入英语短句后点击合成朗读", false) },
    )

    private fun status(context: PluginContext, message: String, error: Boolean) {
        context.publish(PluginOutput(System.nanoTime(),
            listOf(PluginMessage(message, if (error) MessageKind.ERROR else MessageKind.STATUS)), actions = actions))
    }

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> { activeToken = context.token; playback.stop(); work.start(context, deferLoad = true) }
            is PluginEvent.Stopped -> if (activeToken == context.token) { activeToken = null; work.stop(); playback.stop() }
            is PluginEvent.Action -> when (event.id) {
                "retry" -> { playback.stop(); work.start(context) }
                "cancel" -> { work.cancel(); playback.stop(); status(context, "已取消合成并停止播放", false) }
                "run" -> {
                    work.cancel()
                    playback.stop()
                    val text = context.settings.config["input"].orEmpty().trim()
                    if (text.isBlank() || text.length > 200) { status(context, "请输入 1–200 字符英语短句；过长音素序列会被模型拒绝", true); return }
                    status(context, "正在合成：$text", false)
                    work.submit(context) { engine, valid ->
                        if (!valid()) return@submit
                        val result = engine.engine.synthesize(text, AudioControl(isCancelled = { !valid() }))
                        if (!valid()) return@submit
                        val messages = buildList {
                            add(PluginMessage("朗读：$text"))
                            add(PluginMessage(liteRtTiming(result.inferenceMs, result.backend), MessageKind.STATUS))
                            add(PluginMessage("${result.sampleRate} Hz · ${result.backendDetails}", MessageKind.STATUS))
                            result.fallbackReason?.let { add(PluginMessage("CPU 回退：$it", MessageKind.STATUS)) }
                        }
                        work.publishIf(valid, context, PluginOutput(System.nanoTime(), messages, actions = actions,
                            execution = PluginExecution(result.inferenceMs, result.backend))) {
                            playback.play(result.samples, result.sampleRate, valid) { message ->
                                work.publishIf(valid, context, PluginOutput(System.nanoTime(), messages + PluginMessage(message, MessageKind.STATUS), actions = actions))
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    override fun close() { work.close(); playback.close() }
}
