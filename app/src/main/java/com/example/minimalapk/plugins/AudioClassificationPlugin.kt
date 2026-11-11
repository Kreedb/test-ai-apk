package com.example.minimalapk.plugins

import android.content.Context
import com.example.minimalapk.examples.AudioClassificationRunner
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.log.AppLog
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * YAMNet sound classification over the shared microphone PCM stream. It never opens the
 * microphone, never writes audio to disk, and does not transcribe speech: the text lists the
 * model's own sound/environment labels for the newest bounded window.
 */
internal class AudioClassificationPlugin(private val application: Context) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.AUDIO_CLASSIFICATION, "声音识别",
        setOf(PluginCapability.AUDIO_PCM, PluginCapability.AUDIO_PERMISSION, PluginCapability.TEXT),
    )

    private data class Request(val context: PluginContext)
    private data class WindowRequest(
        val context: PluginContext,
        val generation: Long,
        val samples: FloatArray,
        val timestampNanos: Long,
    )

    private val lock = Any()
    private var permission = AudioPermission.NOT_REQUESTED
    private var activeToken: PluginToken? = null
    private var window: AudioSampleWindow? = null
    private val gate = AudioResultGate()
    private val model = AsyncModelSlot<Request, AudioClassificationRunner>(
        "AudioClassificationOwner",
        open = { request ->
            if (!request.context.isCurrent()) throw CancellationException("声音识别请求已失效")
            val installed = ExampleDownload.install(application, ExampleCatalog.audio)
            if (!request.context.isCurrent()) throw CancellationException("声音识别请求已失效")
            AudioClassificationRunner(installed)
        },
        ready = { request, runner ->
            if (request.context.isCurrent()) {
                val accepted = synchronized(lock) {
                    if (activeToken != request.context.token) return@synchronized false
                    window?.reset()
                    window = AudioSampleWindow(runner.windowSamples, runner.sampleRate)
                    true
                }
                if (accepted) request.context.publish(
                    PluginOutput(
                        System.nanoTime(),
                        listOf(
                            PluginMessage(
                                "已就绪：YAMNet ${runner.labels.size} 类声音分类（不是语音识别/转写）",
                                MessageKind.STATUS,
                            ),
                        ),
                    ),
                )
            }
        },
        failed = { request, error ->
            if (error !is CancellationException) AppLog.e("AudioClassification", "YAMNet 加载或推理失败", error)
            synchronized(lock) {
                if (activeToken == request.context.token) {
                    window?.reset()
                    window = null
                    gate.reset()
                }
            }
            request.context.publish(
                PluginOutput(
                    System.nanoTime(),
                    listOf(PluginMessage("未就绪：${error.message ?: "声音识别模型加载失败"}", MessageKind.ERROR)),
                ),
            )
        },
    )

    private val worker = BoundedFrameWorker<WindowRequest>(
        "AudioClassificationWork",
        failed = { request, error ->
            AppLog.e("AudioClassification", "声音窗口推理失败", error)
            if (request.context.isCurrent()) {
                request.context.publish(
                    PluginOutput(
                        System.nanoTime(),
                        listOf(PluginMessage("声音识别失败：${error.message ?: "推理失败"}", MessageKind.ERROR)),
                    ),
                )
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
            is PluginEvent.Stopped -> {
                permission = AudioPermission.NOT_REQUESTED
                stop()
            }
            is PluginEvent.Audio -> onAudio(event, context)
            else -> Unit
        }
    }

    private fun updatePermission(context: PluginContext) {
        if (permission == AudioPermission.GRANTED) {
            restart(context)
        } else {
            stop()
            context.publish(PluginOutput(System.nanoTime(), listOf(PluginMessage(
                when (permission) {
                    AudioPermission.MICROPHONE_BUSY -> "麦克风被占用，请释放后重新进入相机"
                    AudioPermission.DENIED, AudioPermission.REVOKED -> "请授予麦克风权限以识别周围声音"
                    else -> "等待麦克风权限"
                }, MessageKind.STATUS,
            ))))
        }
    }

    private fun restart(context: PluginContext) {
        if (!context.isCurrent()) return
        synchronized(lock) {
            activeToken = context.token
            window?.reset()
            window = null
            gate.reset()
        }
        worker.clear()
        context.publish(
            PluginOutput(
                System.nanoTime(),
                listOf(
                    PluginMessage(
                        "正在下载/加载 YAMNet 声音分类模型（首次约 ${ExampleCatalog.audio.sizeBytes / 1024 / 1024} MB）",
                        MessageKind.STATUS,
                    ),
                ),
            ),
        )
        model.set(Request(context))
    }

    private fun stop() {
        synchronized(lock) {
            activeToken = null
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
        if (!request.context.isCurrent() || !gate.isCurrent(request.generation)) return
        val labels = model.runIfReady { owner, runner ->
            if (owner.context.token != request.context.token || !request.context.isCurrent()) return@runIfReady null
            runner.classify(request.samples)
        } ?: return
        // A stop/config change between inference start and publish must not restore old text.
        if (!request.context.isCurrent() || !gate.accept(request.generation, request.timestampNanos)) return
        val topK = AudioClassificationSettings.from(request.context.settings).topK ?: 3
        val messages = buildList {
            add(
                PluginMessage(
                    "声音分类（YAMNet ${labels.size} 类声音/环境声，不是语音识别/转写）",
                    MessageKind.STATUS,
                ),
            )
            labels.take(topK).forEachIndexed { index, item ->
                add(PluginMessage("${index + 1}. ${item.label}：${percent(item.score)}"))
            }
        }
        request.context.publish(PluginOutput(request.timestampNanos, messages))
    }

    private fun percent(score: Float): String =
        String.format(Locale.US, "%.1f%%", score.coerceIn(0f, 1f) * 100f)

    override fun close() {
        stop()
        worker.close()
        model.close()
    }
}
