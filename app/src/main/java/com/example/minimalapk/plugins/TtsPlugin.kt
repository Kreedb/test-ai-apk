package com.example.minimalapk.plugins

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Empty means follow the system default; otherwise this is an installed engine package name. */
internal const val TTS_ENGINE_KEY = "engine"

internal data class TtsEngineChoice(val packageName: String, val label: String)

@Suppress("DEPRECATION")
internal fun installedTtsEngines(context: Context): List<TtsEngineChoice> {
    val manager = context.packageManager
    return manager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        .mapNotNull { result ->
            val service = result.serviceInfo ?: return@mapNotNull null
            if (!service.exported || !service.enabled || !service.applicationInfo.enabled) return@mapNotNull null
            TtsEngineChoice(service.packageName, result.loadLabel(manager).toString().ifBlank { service.packageName })
        }.distinctBy { it.packageName }.sortedBy { it.label }
}

/** Pure main-thread state: a late callback can only affect the utterance that created it. */
internal class TtsPlayback {
    private var sequence = 0L
    private var activeId: String? = null
    private var closed = false
    var ready = false
        private set
    var text: String? = null
        private set
    var message = "正在初始化系统语音引擎…"
        private set

    fun initialized(isReady: Boolean, status: String): Boolean {
        if (closed) return false
        ready = isReady
        message = status
        return true
    }

    fun enqueue(value: String): String? {
        if (closed || !ready || value.isBlank()) return null
        val id = "plugin-tts-${++sequence}"
        activeId = id
        text = value
        message = "等待语音引擎朗读…"
        return id
    }

    fun started(id: String): Boolean {
        if (closed || id != activeId) return false
        message = "正在朗读…"
        return true
    }

    fun finished(id: String, status: String): Boolean {
        if (closed || id != activeId) return false
        activeId = null
        text = null
        message = status
        return true
    }

    fun stop(): Boolean {
        if (closed) return false
        activeId = null
        text = null
        message = "已停止朗读"
        return true
    }

    fun close() {
        closed = true
        ready = false
        activeId = null
        text = null
    }
}

/**
 * Own one binding per selected engine. All client callbacks are delivered on the main looper;
 * engine callbacks are always posted, including synchronous constructor/speak failures.
 */
internal class TtsEngine(
    private val context: Context,
    requestedEngine: String = "",
    private val onPlayback: (String?) -> Unit = {},
    private val onStatus: (String, Boolean) -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val playback = TtsPlayback()
    private var engine: TextToSpeech? = null
    private var audioFocus: PluginAudioFocus.Lease? = null
    private var focusGeneration = 0L
    @Volatile private var closed = false

    val ready: Boolean get() = !closed && playback.ready

    init {
        check(Looper.myLooper() == main.looper) { "TTS 必须在主线程创建" }
        try {
            require(requestedEngine.isEmpty() || installedTtsEngines(context).any { it.packageName == requestedEngine }) {
                "所选语音引擎未安装或已停用，请重新选择"
            }
            engine = TextToSpeech(context.applicationContext, { code ->
                // Post even if a vendor calls onInit inline, before the constructor returns.
                main.post {
                    if (!closed) initialize(code)
                }
            }, requestedEngine.takeIf { it.isNotEmpty() })
        } catch (error: Exception) {
            main.post {
                if (!closed && playback.initialized(false, "语音引擎初始化失败：${error.message ?: "无法连接服务"}")) publish()
            }
        }
    }

    private fun initialize(code: Int) {
        val tts = engine ?: return
        try {
            check(code == TextToSpeech.SUCCESS) { "无法连接系统语音服务" }
            check(tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = event { playback.started(utteranceId) }
                override fun onDone(utteranceId: String) = event { playback.finished(utteranceId, "朗读完成") }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = event {
                    playback.finished(utteranceId, "朗读失败，请检查语音引擎与语音包")
                }
                override fun onError(utteranceId: String, errorCode: Int) = event {
                    playback.finished(utteranceId, "朗读失败（错误 $errorCode），请检查语音引擎与语音包")
                }
                override fun onStop(utteranceId: String, interrupted: Boolean) = event {
                    playback.finished(utteranceId, "已停止朗读")
                }
            }) == TextToSpeech.SUCCESS) { "语音服务无法报告朗读状态" }
            val language = tts.setLanguage(Locale.CHINA)
            check(language >= TextToSpeech.LANG_AVAILABLE) {
                if (language == TextToSpeech.LANG_MISSING_DATA) "系统缺少中文语音包，请在系统设置中安装"
                else "语音服务不支持中文，请选择其他引擎或安装中文语音包"
            }
            // defaultEngine is a setting, not the bound engine. The public Android API may
            // silently fall back; report readiness without claiming the requested engine bound.
            playback.initialized(true, "中文语音服务就绪")
        } catch (error: Exception) {
            playback.initialized(false, "语音引擎未就绪：${error.message ?: "初始化失败"}")
        }
        publish()
    }

    private fun event(update: () -> Boolean) {
        main.post { if (!closed && update()) publish() }
    }

    private fun publish() {
        if (closed) return
        if (playback.text == null) { audioFocus?.close(); audioFocus = null }
        onStatus(playback.message, playback.ready)
        onPlayback(playback.text)
    }

    /** Called by the UI. Replacing speech invalidates the old ID before QUEUE_FLUSH fires onStop. */
    fun speak(text: String): Boolean {
        check(Looper.myLooper() == main.looper) { "朗读必须在主线程调用" }
        if (closed) return false
        val tts = engine ?: return false
        if (!playback.ready || text.isBlank()) return false
        runCatching { tts.stop() }
        audioFocus?.close()
        val focusRequest = ++focusGeneration
        audioFocus = try {
            PluginAudioFocus.acquire(context) {
                onMain { if (focusGeneration == focusRequest) stop() }
            }
        } catch (_: Exception) {
            playback.stop()
            publish()
            return false
        }
        val id = playback.enqueue(clampTtsText(text, TextToSpeech.getMaxSpeechInputLength())) ?: run {
            audioFocus?.close()
            audioFocus = null
            return false
        }
        publish()
        val accepted = try {
            tts.speak(playback.text!!, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        } catch (_: Exception) {
            false
        }
        if (!accepted && playback.finished(id, "朗读失败，请检查系统引擎与语音包")) publish()
        return accepted
    }

    fun stop() {
        onMain {
            if (!closed && playback.stop()) {
                focusGeneration++
                // Clear synchronously: some engines never emit onStop for a queued utterance.
                runCatching { engine?.stop() }
                publish()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        onMain {
            focusGeneration++
            playback.close()
            val tts = engine
            engine = null
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            audioFocus?.close()
            audioFocus = null
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
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
