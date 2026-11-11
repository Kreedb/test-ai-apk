package com.example.minimalapk.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * System TextToSpeech wrapper shared by the plugin card and its dialog. Initialization is
 * asynchronous and reported through [onStatus]; no model or microphone is involved.
 */
internal class TtsEngine(context: Context, private val onStatus: (String, Boolean) -> Unit) : AutoCloseable {
    private var engine: TextToSpeech? = null
    private var closed = false

    @Volatile
    var ready: Boolean = false
        private set

    init {
        val created = TextToSpeech(context.applicationContext) { code ->
            // The constructor can return before the engine binds; finish setup after assignment.
            Handler(Looper.getMainLooper()).post {
                val tts = engine ?: return@post
                if (closed) return@post
                if (code != TextToSpeech.SUCCESS) {
                    ready = false
                    onStatus("系统语音引擎初始化失败", false)
                    return@post
                }
                val result = tts.setLanguage(Locale.CHINA)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                onStatus(
                    if (ready) "语音引擎就绪：${tts.defaultEngine ?: "系统默认"}"
                    else "系统缺少中文语音包，请在系统设置中安装",
                    ready,
                )
            }
        }
        engine = created
    }

    /** Queues a new utterance, replacing the previous one. Returns false when the engine is not ready. */
    fun speak(text: String): Boolean {
        val tts = engine ?: return false
        if (!ready || text.isBlank()) return false
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "plugin-tts") != TextToSpeech.ERROR
    }

    fun stop() {
        engine?.stop()
    }

    override fun close() {
        if (closed) return
        closed = true
        ready = false
        val tts = engine
        engine = null
        tts?.stop()
        tts?.shutdown()
    }
}

/**
 * TTS is a system capability, not a LiteRT model. The dialog is the only control surface and
 * speaks only when the user presses 朗读; this plugin only reports state while the camera is open.
 */
internal class TtsPlugin : CameraPlugin {
    override val descriptor = PluginDescriptor(PluginIds.TTS, "语音朗读", setOf(PluginCapability.TEXT))

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> {
                if (!context.isCurrent()) return
                val text = clampTtsText(
                    context.settings.config[TTS_TEXT_KEY].orEmpty(),
                    TextToSpeech.getMaxSpeechInputLength(),
                )
                val status = if (text.isBlank()) {
                    "语音朗读已启用：未设置文本；请在 插件 → 语音朗读 中朗读"
                } else {
                    "语音朗读已启用：系统 TTS，不自动朗读；请在 插件 → 语音朗读 中操作（当前 ${text.length} 字）"
                }
                context.publish(PluginOutput(System.nanoTime(), listOf(PluginMessage(status, MessageKind.STATUS))))
            }
            is PluginEvent.Stopped -> Unit
            else -> Unit
        }
    }
}
