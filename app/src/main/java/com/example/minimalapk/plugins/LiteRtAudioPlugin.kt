package com.example.minimalapk.plugins

import android.content.Context
import com.example.minimalapk.litert.audio.AudioTaskEngine
import com.example.minimalapk.litert.audio.AudioTaskRequest
import com.example.minimalapk.litert.audio.AudioTasks
import com.example.minimalapk.litert.audio.NamedWaveform
import com.example.minimalapk.litert.audio.ZipformerEngine

/** Batch capture begins only on the explicit record action; memory is at most ten seconds of PCM. */
internal class LiteRtAudioPlugin(
    private val application: Context,
    clock: () -> Long = System::nanoTime,
    private val recordingDeadline: LiteRtRecordingDeadline = LiteRtRecordingTimer(),
) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.LITERT_AUDIO, "录音处理与试听",
        setOf(PluginCapability.AUDIO_PERMISSION, PluginCapability.AUDIO_PCM, PluginCapability.TEXT, PluginCapability.ACTIONS,
            PluginCapability.MODEL_ASSETS),
        actions = setOf("record", "cancel", "retry", "play_0", "play_1", "play_2", "play_3", "play_4"),
        continuousAudio = false,
    )
    private val lock = Any()
    private val assets by lazy { LiteRtAssets(application) }
    private val playback = LiteRtPlayback(application)
    private val recordingGate = LiteRtRequestGate()
    private val recordingIntent = LiteRtRecordingIntent(clock)
    private var currentContext: PluginContext? = null
    private var window: AudioSampleWindow? = null
    private var recordingContext: PluginContext? = null
    @Volatile private var permission = AudioPermission.NOT_REQUESTED
    @Volatile private var playing = false
    @Volatile private var waves: List<NamedWaveform> = emptyList()
    @Volatile private var lastMessages: List<PluginMessage> = emptyList()
    private val work = LiteRtWork<LiteRtInstalledModel<AudioTaskEngine>>(
        "LiteRtBatchAudio",
        open = { context ->
            assets.open(context, LiteRtScenes.audio) { task, directory, backend -> AudioTasks.create(task, directory, backend) }
        },
        status = ::status,
        ready = { status(it, "已就绪；点击录音，自动处理有界窗口；原始录音不落盘", false) },
    )

    private fun actions(context: PluginContext): List<PluginAction> = buildList {
        add(PluginAction("record",
            if (permission == AudioPermission.GRANTED) "录制 ${LiteRtScenes.windowSeconds(context.settings)} 秒并处理"
            else "申请麦克风权限并录音",
            permission != AudioPermission.MICROPHONE_BUSY && !playing))
        add(PluginAction("cancel", "取消录音 / 停止播放"))
        add(PluginAction("retry", if (permission == AudioPermission.MICROPHONE_BUSY) "重试麦克风并录音" else "重新加载模型",
            !playing))
        waves.take(5).forEachIndexed { index, wave -> add(PluginAction("play_$index", "试听 ${wave.name}")) }
    }

    private fun report(context: PluginContext, messages: List<PluginMessage>) {
        if (!context.isCurrent()) return
        lastMessages = messages
        context.publish(PluginOutput(System.nanoTime(), messages, actions = actions(context),
            captureAudio = recordingIntent.wants(context) && !playing))
    }

    private fun status(context: PluginContext, message: String, error: Boolean) {
        if (!context.isCurrent()) return
        if (error) {
            recordingIntent.cancel()
            recordingDeadline.cancel()
            recordingGate.next()
            synchronized(lock) { window = null; recordingContext = null }
        }
        report(context, listOf(PluginMessage(message, if (error) MessageKind.ERROR else MessageKind.STATUS)))
    }

    private fun cancel() {
        recordingIntent.cancel()
        recordingDeadline.cancel()
        recordingGate.next()
        synchronized(lock) { window = null; recordingContext = null }
        work.cancel()
        playback.stop()
    }

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> {
                synchronized(lock) { currentContext = context }
                cancel(); waves = emptyList(); work.start(context, deferLoad = true)
            }
            is PluginEvent.Stopped -> {
                synchronized(lock) { currentContext = null }
                cancel(); waves = emptyList(); work.stop(); permission = AudioPermission.NOT_REQUESTED
            }
            is PluginEvent.AudioPermissionChanged -> {
                permission = event.permission
                val recover = !playing && event.permission in setOf(AudioPermission.GRANTED, AudioPermission.MICROPHONE_BUSY) &&
                    recordingIntent.wants(context)
                recordingGate.next()
                synchronized(lock) {
                    currentContext = context
                    window = null
                    recordingContext = null
                }
                work.cancel()
                playback.stop()
                waves = emptyList()
                if (!recover) { recordingIntent.cancel(); recordingDeadline.cancel() }
                if (permission == AudioPermission.GRANTED) {
                    if (recover) synchronized(lock) {
                        if (recordingIntent.wants(context)) {
                            window = AudioSampleWindow(LiteRtScenes.windowSeconds(context.settings) * AUDIO_SAMPLE_RATE)
                            recordingContext = context
                        }
                    }
                    work.start(context, deferLoad = true)
                    if (recover && recordingIntent.wants(context)) status(context, "麦克风已恢复；重新录制完整窗口，已丢弃中断前样本", false)
                } else { work.stop(); status(context, if (permission == AudioPermission.MICROPHONE_BUSY && recover)
                    "录音被系统静音或占用；等待恢复（仍受本次时限约束），可取消；恢复后重新录制完整窗口"
                    else if (permission == AudioPermission.MICROPHONE_BUSY)
                        "麦克风不可用；恢复系统麦克风后点击“重试麦克风并录音”；若采集失败持续，请重新进入相机"
                    else "等待麦克风权限；授权后可开始短录音", false) }
            }
            is PluginEvent.AudioPlaybackChanged -> {
                if (playing == event.playing) return
                playing = event.playing
                if (playing) {
                    recordingIntent.cancel()
                    recordingDeadline.cancel()
                    synchronized(lock) { window = null; recordingContext = null }
                }
                report(context, lastMessages.take(PluginHost.MAX_MESSAGES - 1) +
                    PluginMessage(if (playing) "播放中，共享麦克风暂停" else "播放结束，可录音", MessageKind.STATUS))
            }
            is PluginEvent.Action -> when {
                event.id == "retry" -> if (permission == AudioPermission.MICROPHONE_BUSY && !playing) {
                    beginRecording(context)
                } else { cancel(); waves = emptyList(); work.start(context) }
                event.id == "cancel" -> { cancel(); status(context, "已取消；音频不会保存到文件", false) }
                event.id == "record" -> {
                    if (permission == AudioPermission.MICROPHONE_BUSY || playing) return
                    beginRecording(context)
                }
                event.id.startsWith("play_") -> {
                    val wave = waves.getOrNull(event.id.removePrefix("play_").toIntOrNull() ?: -1) ?: return
                    val generation = recordingGate.next()
                    val messages = lastMessages
                    playback.play(wave.samples, wave.sampleRate,
                        valid = { context.isCurrent() && recordingGate.accepts(generation) }) { message ->
                        work.publishIf({ recordingGate.accepts(generation) }, context, PluginOutput(System.nanoTime(),
                            messages.take(PluginHost.MAX_MESSAGES - 1) + PluginMessage(message, MessageKind.STATUS),
                            actions = actions(context), captureAudio = false))
                    }
                }
            }
            is PluginEvent.Audio -> {
                val captured = synchronized(lock) {
                    if (recordingContext?.token != context.token || playing || !recordingIntent.wants(context)) return
                    val samples = window?.append(event.block) ?: return
                    window = null
                    recordingContext = null
                    recordingIntent.cancel()
                    recordingDeadline.cancel()
                    samples to recordingGate.current()
                }
                process(context, captured.first, captured.second)
            }
            else -> Unit
        }
    }

    private fun beginRecording(context: PluginContext) {
        cancel()
        waves = emptyList()
        val seconds = LiteRtScenes.windowSeconds(context.settings)
        val request = synchronized(lock) {
            if (!context.isCurrent() || playing) return
            currentContext = context
            val id = recordingIntent.begin(context, seconds)
            if (permission == AudioPermission.GRANTED) {
                window = AudioSampleWindow(seconds * AUDIO_SAMPLE_RATE)
                recordingContext = context
            }
            id
        }
        recordingDeadline.schedule(LiteRtRecordingIntent.budgetNanos(seconds)) { expireRecording(request) }
        status(context, when (permission) {
            AudioPermission.GRANTED -> "正在录音 $seconds 秒；包含启动/恢复等待最多 ${seconds + LiteRtRecordingIntent.RECOVERY_ALLOWANCE_SECONDS} 秒，可随时取消"
            AudioPermission.MICROPHONE_BUSY -> "正在重试麦克风；等待系统恢复后录制 $seconds 秒，最多等待至本次 ${seconds + LiteRtRecordingIntent.RECOVERY_ALLOWANCE_SECONDS} 秒时限"
            else -> "等待麦克风授权；授权后录制 $seconds 秒（本次等待最多 ${seconds + LiteRtRecordingIntent.RECOVERY_ALLOWANCE_SECONDS} 秒）。取消或离开相机后须重新点击录音"
        }, false)
    }

    private fun expireRecording(request: Long) {
        synchronized(lock) {
            if (!recordingIntent.expire(request)) return
            recordingGate.next()
            window = null
            recordingContext = null
            currentContext?.let {
                status(it, "本次录音/恢复等待已超时，麦克风已释放；恢复系统麦克风后可点击重试", false)
            }
        }
    }

    private fun process(context: PluginContext, samples: FloatArray, generation: Long) {
        if (!recordingGate.accepts(generation)) return
        work.publishIf({ recordingGate.accepts(generation) }, context, PluginOutput(System.nanoTime(),
            listOf(PluginMessage("录音完成，处理中…", MessageKind.STATUS)), actions = actions(context), captureAudio = false))
        work.submit(context, valid = { recordingGate.accepts(generation) }) { engine, valid ->
            if (!valid()) return@submit
            val result = engine.engine.run(AudioTaskRequest(samples, AUDIO_SAMPLE_RATE, onProgress = { stage, current, total ->
                work.publishIf(valid, context, PluginOutput(System.nanoTime(),
                    listOf(PluginMessage("$stage：$current/$total", MessageKind.STATUS)), actions = actions(context), captureAudio = false))
            }, isCancelled = { !valid() }))
            val backend = engine.engine.backend
            val produced = result.waveforms.take(4).map { it.copy(samples = it.samples.copyOf()) }
            if (!valid()) return@submit
            val messages = buildList {
                add(PluginMessage(liteRtTiming(result.inferenceMs, backend.actual), MessageKind.STATUS))
                addAll(result.summary.chunked(PluginHost.MAX_MESSAGE_CHARS).take(4).map { PluginMessage(it) })
                add(PluginMessage("共享输入 16 kHz 单声道；按模型重采样 · ${samples.size / AUDIO_SAMPLE_RATE.toFloat()} 秒", MessageKind.STATUS))
                if (backend.details.isNotBlank()) add(PluginMessage(backend.details, MessageKind.STATUS))
                backend.fallbackReason?.let { add(PluginMessage("CPU 回退：$it", MessageKind.STATUS)) }
            }
            work.publishIf(valid, context, PluginOutput(System.nanoTime(), messages, actions = buildList {
                addAll(actions(context).filterNot { it.id.startsWith("play_") })
                add(PluginAction("play_0", "试听 原始录音"))
                produced.forEachIndexed { index, wave -> add(PluginAction("play_${index + 1}", "试听 ${wave.name}")) }
            }, execution = PluginExecution(result.inferenceMs, backend.actual), captureAudio = false)) {
                waves = listOf(NamedWaveform("原始录音", samples, AUDIO_SAMPLE_RATE)) + produced
                lastMessages = messages
            }
        }
    }

    override fun close() { cancel(); waves = emptyList(); recordingDeadline.close(); work.close(); playback.close() }
}

/** Zipformer is batch CTC over short live windows, not a recurrent unbounded transcript. */
internal class LiteRtCaptionsPlugin(private val application: Context) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.LITERT_CAPTIONS, "实时英语字幕",
        setOf(PluginCapability.AUDIO_PERMISSION, PluginCapability.AUDIO_PCM, PluginCapability.TEXT, PluginCapability.ACTIONS,
            PluginCapability.MODEL_ASSETS),
        actions = setOf("listen", "cancel", "retry"),
    )
    private val lock = Any()
    private val assets = LiteRtAssets(application)
    private var window: AudioSampleWindow? = null
    private var activeToken: PluginToken? = null
    private var permission = AudioPermission.NOT_REQUESTED
    private var paused = false
    private var listening = true
    private val listeningGate = LiteRtRequestGate()
    private val actions = listOf(PluginAction("listen", "开始英语字幕"), PluginAction("cancel", "暂停字幕"), PluginAction("retry", "重新加载模型"))
    private val work = LiteRtWork<LiteRtInstalledModel<ZipformerEngine>>(
        "LiteRtCaptions",
        open = { context ->
            assets.open(context, LiteRtScenes.captions) { _, directory, backend -> ZipformerEngine(application, directory, backend) }
        }, status = ::status,
        ready = { status(it, "英语字幕就绪 · 有界窗口转写，慢推理会跳过旧窗口", false) },
    )

    private fun status(context: PluginContext, message: String, error: Boolean) {
        if (!context.isCurrent()) return
        if (error) {
            listeningGate.next()
            synchronized(lock) { window = null; listening = false }
        }
        context.publish(PluginOutput(System.nanoTime(),
            listOf(PluginMessage(message, if (error) MessageKind.ERROR else MessageKind.STATUS)), actions = actions,
            // Intent stays subscribed during client silencing so the single foreground recorder
            // can observe recovery; host permission gating still drops all silenced PCM.
            captureAudio = synchronized(lock) { listening && !paused && activeToken == context.token }))
    }

    private fun refresh(context: PluginContext) {
        listeningGate.next()
        synchronized(lock) {
            activeToken = context.token
            window = if (permission == AudioPermission.GRANTED && !paused && listening)
                AudioSampleWindow(LiteRtScenes.windowSeconds(context.settings, captions = true) * AUDIO_SAMPLE_RATE) else null
        }
        work.cancel()
        if (window != null) work.start(context)
        else { work.stop(); status(context, if (paused) "播放中，字幕和麦克风已暂停"
            else if (!listening) "字幕已暂停"
            else if (permission == AudioPermission.MICROPHONE_BUSY)
                "麦克风不可用（被占用、系统静音或采集失败），请检查系统麦克风开关或稍后重新进入相机"
            else "英语字幕等待麦克风权限", false) }
    }

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> { listening = true; refresh(context) }
            is PluginEvent.AudioPermissionChanged -> { permission = event.permission; refresh(context) }
            is PluginEvent.AudioPlaybackChanged -> if (paused != event.playing) { paused = event.playing; refresh(context) }
            is PluginEvent.Stopped -> {
                listeningGate.next()
                synchronized(lock) { activeToken = null; window = null }
                permission = AudioPermission.NOT_REQUESTED
                work.stop()
            }
            is PluginEvent.Action -> when (event.id) {
                "listen", "retry" -> { listening = true; refresh(context) }
                "cancel" -> { listening = false; refresh(context) }
            }
            is PluginEvent.Audio -> {
                val captured = synchronized(lock) {
                    if (activeToken != context.token) return
                    window?.append(event.block)?.let { it to listeningGate.current() }
                } ?: return
                val (samples, generation) = captured
                val timestamp = event.block.timestampNanos
                work.submit(context, replace = false, valid = { listeningGate.accepts(generation) }) { engine, valid ->
                    if (!valid()) return@submit
                    val result = engine.engine.transcribe(AudioTaskRequest(samples, AUDIO_SAMPLE_RATE, isCancelled = { !valid() }))
                    val messages = buildList {
                        add(PluginMessage(result.text.ifBlank { "（此窗口未识别出英语语音）" }))
                        add(PluginMessage("英语 · ${samples.size / AUDIO_SAMPLE_RATE} 秒窗口 · ${liteRtTiming(result.inferenceMs, result.backend)}", MessageKind.STATUS))
                        result.fallbackReason?.let { add(PluginMessage("CPU 回退：$it", MessageKind.STATUS)) }
                    }
                    // Finish time is monotonic; the window timestamp is explicit, never substituted
                    // with a new permission/session context. Results stay bounded to the newest slot.
                    work.publishIf(valid, context, PluginOutput(System.nanoTime(), messages +
                        PluginMessage("采集时间 ${timestamp / 1_000_000} ms（单调时钟）", MessageKind.STATUS), actions = actions,
                        execution = PluginExecution(result.inferenceMs, result.backend), captureAudio = true))
                }
            }
            else -> Unit
        }
    }

    override fun close() { synchronized(lock) { window = null }; work.close() }
}
