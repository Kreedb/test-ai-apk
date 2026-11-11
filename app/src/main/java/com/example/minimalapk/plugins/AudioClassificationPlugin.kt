package com.example.minimalapk.plugins

import android.content.Context
import android.net.Uri
import com.example.minimalapk.examples.AudioClassificationRunner
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.SoundClassifier
import com.example.minimalapk.examples.yamnetWavWindow
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.AssetTransfer
import com.example.minimalapk.pose.readCapped
import java.util.Locale
import java.util.concurrent.CancellationException

internal const val AUDIO_INPUT_KEY = "audio_input"
internal const val AUDIO_WAV_URI_KEY = "wav_uri"
internal const val AUDIO_INPUT_WAV = "wav"
internal const val AUDIO_WAV_MAX_BYTES = 16 * 1024 * 1024
internal fun audioUsesMicrophone(settings: PluginSettings): Boolean = settings.config[AUDIO_INPUT_KEY] != AUDIO_INPUT_WAV

/**
 * YAMNet sound classification over the shared microphone PCM stream. It never opens the
 * microphone, never writes audio to disk, and does not transcribe speech: the text lists the
 * model's own sound/environment labels for the newest bounded window.
 */
internal class AudioClassificationPlugin(
    private val application: Context,
    private val openModel: (Context, AssetTransfer) -> SoundClassifier = { context, transfer ->
        val file = ExampleDownload.ensure(context, ExampleCatalog.audio, transfer)
        transfer.checkpoint()
        AudioClassificationRunner(file)
    },
) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.AUDIO_CLASSIFICATION, "声音识别",
        setOf(PluginCapability.AUDIO_PCM, PluginCapability.AUDIO_PERMISSION, PluginCapability.TEXT, PluginCapability.ACTIONS),
        actions = setOf("run_file", "cancel_file"),
        continuousAudio = false,
    )

    private data class Request(val context: PluginContext, val generation: Long)
    private data class WindowRequest(
        val context: PluginContext,
        val generation: Long,
        val samples: FloatArray?,
        val timestampNanos: Long,
        val wavUri: String? = null,
    )

    private val lock = Any()
    private var permission = AudioPermission.NOT_REQUESTED
    private var playbackPaused = false
    private var activeToken: PluginToken? = null
    private var window: AudioSampleWindow? = null
    private var ready = false
    private var fileBusy = false
    private var fileTransfer: AssetTransfer? = null
    private val gate = AudioResultGate()
    private val model = AsyncModelSlot<Request, SoundClassifier>(
        "AudioClassificationOwner",
        open = { request ->
            val transfer = AssetTransfer {
                if (!request.context.isCurrent() || !gate.isCurrent(request.generation) ||
                    synchronized(lock) { activeToken != request.context.token }) {
                    throw CancellationException("声音识别请求已失效")
                }
            }
            transfer.checkpoint()
            openModel(application, transfer)
        },
        ready = { request, runner ->
            synchronized(lock) {
                if (activeToken == request.context.token && gate.isCurrent(request.generation) && request.context.isCurrent()) {
                    ready = true
                    window = if (audioUsesMicrophone(request.context.settings)) AudioSampleWindow(runner.windowSamples, runner.sampleRate) else null
                    report(request.context, "已就绪：YAMNet ${runner.labels.size} 类声音分类（不是语音识别/转写）" +
                        if (audioUsesMicrophone(request.context.settings)) "" else "；点击识别 WAV，只分析开头 0.975 秒")
                }
            }
        },
        failed = { request, error ->
            if (error is CancellationException) return@AsyncModelSlot
            AppLog.e("AudioClassification", "YAMNet 加载或推理失败", error)
            synchronized(lock) {
                if (activeToken == request.context.token && gate.isCurrent(request.generation)) {
                    window?.reset()
                    window = null
                    ready = false
                    fileBusy = false
                    gate.reset()
                    report(request.context, "未就绪：${error.message ?: "声音识别模型加载失败"}", MessageKind.ERROR)
                }
            }
        },
    )

    private val worker = BoundedFrameWorker<WindowRequest>(
        "AudioClassificationWork",
        failed = { request, error ->
            AppLog.e("AudioClassification", "声音窗口推理失败", error)
            synchronized(lock) {
                if (current(request)) {
                    fileBusy = false
                    report(request.context, "声音识别失败：${error.message ?: "推理失败"}", MessageKind.ERROR)
                }
            }
        },
        consume = ::classify,
    )

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> updatePermission(context)
            is PluginEvent.AudioPermissionChanged -> {
                permission = event.permission
                updatePermission(context)
            }
            is PluginEvent.AudioPlaybackChanged -> {
                if (playbackPaused == event.playing) return
                playbackPaused = event.playing
                if (audioUsesMicrophone(context.settings)) updatePermission(context)
            }
            is PluginEvent.Action -> if (!audioUsesMicrophone(context.settings)) when (event.id) {
                "run_file" -> startFile(context)
                "cancel_file" -> {
                    stop()
                    restart(context)
                }
            }
            is PluginEvent.Stopped -> {
                permission = AudioPermission.NOT_REQUESTED
                stop()
            }
            is PluginEvent.Audio -> onAudio(event, context)
            else -> Unit
        }
    }

    private fun updatePermission(context: PluginContext) {
        if (!audioUsesMicrophone(context.settings) || permission == AudioPermission.GRANTED && !playbackPaused) {
            restart(context)
        } else {
            stop()
            report(context,
                if (playbackPaused) "播放中，共享麦克风已暂停" else when (permission) {
                    AudioPermission.MICROPHONE_BUSY -> "麦克风不可用（被占用、系统静音或采集失败），请检查系统麦克风开关或稍后重新进入相机"
                    AudioPermission.DENIED, AudioPermission.REVOKED -> "请授予麦克风权限以识别周围声音"
                    else -> "等待麦克风权限"
                })
        }
    }

    private fun restart(context: PluginContext) {
        if (!context.isCurrent()) return
        val generation = synchronized(lock) {
            if (activeToken == context.token) return
            activeToken = context.token
            ready = false
            fileBusy = false
            fileTransfer?.cancel()
            fileTransfer = null
            window?.reset()
            window = null
            gate.reset()
        }
        worker.clear()
        report(context, "正在下载/加载 YAMNet 声音分类模型（首次约 ${ExampleCatalog.audio.sizeBytes / 1024 / 1024} MB）")
        model.set(Request(context, generation))
    }

    private fun stop() {
        synchronized(lock) {
            activeToken = null
            ready = false
            fileBusy = false
            fileTransfer?.cancel()
            fileTransfer = null
            window?.reset()
            window = null
            gate.reset()
        }
        worker.clear()
        // Release the interpreter on stop/disable/background; the downloaded file stays cached.
        model.set(null)
    }

    private fun onAudio(event: PluginEvent.Audio, context: PluginContext) {
        if (!context.isCurrent()) return
        val request = synchronized(lock) {
            if (activeToken != context.token) return
            val active = window ?: return
            val samples = try {
                active.append(event.block)
            } catch (_: Throwable) {
                null
            } ?: return
            WindowRequest(context, gate.current(), samples, active.filledTimestampNanos)
        }
        worker.offer(request)
    }

    private fun classify(request: WindowRequest) {
        if (!current(request)) return
        val wav = request.wavUri?.let { uri ->
            val transfer = AssetTransfer { if (!current(request)) throw CancellationException("声音文件请求已失效") }
            synchronized(lock) {
                if (!current(request)) return
                fileTransfer = transfer
            }
            try {
                val parsed = Uri.parse(uri)
                require(parsed.scheme == "content") { "请重新选择 WAV 文件" }
                transfer.use(application.contentResolver.openInputStream(parsed) ?: error("无法读取 WAV 文件，请重新选择")) {
                    it.readCapped(AUDIO_WAV_MAX_BYTES, transfer::checkpoint)
                }
            } finally { synchronized(lock) { if (fileTransfer === transfer) fileTransfer = null } }
        }
        // A bad input file is not a broken interpreter; validate/decode before borrowing the model.
        val samples = wav?.let(::yamnetWavWindow) ?: checkNotNull(request.samples)
        val labels = model.runIfReady { owner, runner ->
            if (owner.context.token != request.context.token || !current(request)) return@runIfReady null
            runner.classify(samples)
        } ?: return
        val topK = AudioClassificationSettings.from(request.context.settings).topK ?: 3
        val messages = buildList {
            add(
                PluginMessage(
                    (if (wav != null) "WAV 开头 0.975 秒 · " else "") +
                        "声音分类（YAMNet ${labels.size} 类声音/环境声，不是语音识别/转写）",
                    MessageKind.STATUS,
                ),
            )
            labels.take(topK).forEachIndexed { index, item ->
                add(PluginMessage("${index + 1}. ${item.label}：${percent(item.score)}"))
            }
        }
        // Playback uses the same host token: generation check and publication must be atomic with
        // pause/reset, otherwise a native completion can restore pre-TTS labels after withdrawal.
        synchronized(lock) {
            if (!current(request) || !gate.accept(request.generation, request.timestampNanos)) return
            fileBusy = false
            request.context.publish(PluginOutput(System.nanoTime(), messages,
                actions = actions(request.context), captureAudio = audioUsesMicrophone(request.context.settings)))
        }
    }

    private fun current(request: WindowRequest): Boolean = request.context.isCurrent() && gate.isCurrent(request.generation)

    private fun startFile(context: PluginContext): Unit = synchronized(lock) {
        if (!context.isCurrent() || activeToken != context.token || !ready || fileBusy) return
        val uri = context.settings.config[AUDIO_WAV_URI_KEY].orEmpty()
        if (uri.isBlank()) return
        fileBusy = true
        report(context, "正在识别所选 WAV 的开头 0.975 秒…")
        worker.offer(WindowRequest(context, gate.current(), null, System.nanoTime(), uri))
    }

    private fun actions(context: PluginContext): List<PluginAction> = if (audioUsesMicrophone(context.settings)) emptyList() else listOf(
        PluginAction("run_file", "识别 WAV", ready && !fileBusy && !context.settings.config[AUDIO_WAV_URI_KEY].isNullOrBlank()),
        PluginAction("cancel_file", "取消识别", fileBusy),
    )

    private fun report(context: PluginContext, text: String, kind: MessageKind = MessageKind.STATUS) = synchronized(lock) {
        context.publish(PluginOutput(System.nanoTime(), listOf(PluginMessage(text, kind)),
            actions = actions(context), captureAudio = audioUsesMicrophone(context.settings)))
    }

    private fun percent(score: Float): String =
        String.format(Locale.US, "%.1f%%", score.coerceIn(0f, 1f) * 100f)

    override fun close() {
        stop()
        worker.close()
        model.close()
    }
}
