package com.example.minimalapk.plugins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredInputMode
import com.example.minimalapk.models.ConfiguredModelResults
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.ConfiguredPcmContract
import com.example.minimalapk.models.ConfiguredPcmModel
import com.example.minimalapk.models.ConfiguredPublishedResult
import com.example.minimalapk.models.ConfiguredResultPreview
import com.example.minimalapk.models.LiveConfiguredModel
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.ModelRunResult
import com.example.minimalapk.models.configuredInputContract
import com.example.minimalapk.models.configuredExecutionProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

/** Image and explicit PCM models share one serial owner; manual results survive the configuration tab. */
internal class ConfiguredModelPlugin(
    private val listModels: () -> List<ConfiguredModel>,
    private val openRunner: (ConfiguredModel, AudioPermission) -> ConfiguredFrameRunner,
    private val results: StateFlow<ConfiguredPublishedResult?>,
) : CameraPlugin {
    constructor(application: Context) : this(
        { ConfiguredModelStore.list(ConfiguredModelStore.directory(application)) },
        ::openConfiguredRunner,
        ConfiguredModelResults.result,
    ) { ConfiguredModelResults.attach(application) }

    override val descriptor = PluginDescriptor(
        PluginIds.CONFIGURED_MODEL, "自定义模型",
        setOf(PluginCapability.CAMERA_FRAMES, PluginCapability.TEXT, PluginCapability.LAYER,
            PluginCapability.AUDIO_PCM, PluginCapability.AUDIO_PERMISSION, PluginCapability.REPORT_IMAGE),
        continuousAudio = false,
    )
    private data class Request(val context: PluginContext, val permission: AudioPermission)
    private data class AudioRequest(
        val context: PluginContext,
        val generation: Long,
        val samples: Map<String, FloatArray>,
        val timestampNanos: Long,
    )
    private var current: PluginContext? = null
    private var requested: Request? = null
    private val lock = Any()
    private var permission = AudioPermission.NOT_REQUESTED
    private var playing = false
    private val gate = AudioResultGate()
    // User intent survives temporary unavailability; the shared recorder needs it to observe unmute.
    private var pcmContracts = emptyMap<String, ConfiguredPcmContract>()
    private var windows = emptyMap<String, ConfiguredPcmWindow>()
    private val reports = linkedMapOf<String, List<PluginMessage>>()
    private var layer: PluginLayer? = null
    private var audioPreview: ConfiguredResultPreview? = null
    private var activeGroup: ConfiguredModelGroup? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val model = AsyncModelSlot<Request, ConfiguredModelGroup>(
        "ConfiguredModelOwner",
        open = { request ->
            ConfiguredModelGroup(
                listModels(),
                request.context::isCurrent,
            ) { selected -> openRunner(selected, request.permission) }
        },
        ready = { request, group ->
            synchronized(lock) {
                if (current?.token == request.context.token && request.context.isCurrent()) {
                    activeGroup = group
                    pcmContracts = group.pcmContracts
                    windows = newWindows()
                    reports.clear()
                    reports.putAll(group.modelStatus.mapValues { listOf(it.value) })
                    publish(request.context)
                }
            }
        },
        failed = { request, error ->
            synchronized(lock) {
                if (current?.token == request.context.token && request.context.isCurrent()) {
                    reports["failure"] = listOf(PluginMessage("未就绪：${error.message ?: "自定义模型失败"}", MessageKind.ERROR))
                    windows = emptyMap()
                    pcmContracts = emptyMap()
                    publish(request.context)
                }
            }
        },
    )

    private val audioWorker = BoundedFrameWorker<AudioRequest>("ConfiguredAudioWork",
        failed = { request, error ->
            synchronized(lock) {
                if (current?.token == request.context.token && gate.isCurrent(request.generation)) {
                    reports["audio"] = listOf(PluginMessage("PCM 失败：${error.message}", MessageKind.ERROR))
                    publish(request.context)
                }
            }
        }, consume = ::runAudio)

    init {
        scope.launch {
            results.collect {
                synchronized(lock) { current?.let(::publish) }
            }
        }
    }

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> restart(context)
            is PluginEvent.AudioPermissionChanged -> synchronized(lock) {
                permission = event.permission
                restart(context, preserveAudioIntent = true)
            }
            is PluginEvent.AudioPlaybackChanged -> synchronized(lock) {
                playing = event.playing
                invalidateAudio()
                windows = newWindows()
                pcmContracts.keys.forEach { id -> reports[id] = listOf(PluginMessage(
                    if (playing) "播放期间暂停麦克风输入" else "等待完整的新 PCM 窗口", MessageKind.STATUS)) }
                publish(context)
            }
            is PluginEvent.Stopped -> synchronized(lock) {
                if (current?.token == context.token) {
                    current = null
                    requested = null
                    permission = AudioPermission.NOT_REQUESTED
                    playing = false
                    invalidateAudio()
                    windows = emptyMap()
                    pcmContracts = emptyMap()
                    reports.clear()
                    layer = null
                    activeGroup?.cancel()
                    activeGroup = null
                    model.set(null)
                }
            }
            is PluginEvent.Audio -> receiveAudio(event.block, context)
            is PluginEvent.Frame -> model.runIfReady { request, group ->
                if (request.context.token != context.token || !context.isCurrent()) return@runIfReady
                if (!group.needsFrames) return@runIfReady
                val frame = event.frame
                val messages = linkedMapOf<String, List<PluginMessage>>()
                var overlay: Bitmap? = null
                var canvas: Canvas? = null
                val target = RectF(0f, 0f, frame.source.space.width.toFloat(), frame.source.space.height.toFloat())
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                group.run(frame.image, context::isCurrent) { selected, result ->
                    val output = result.getOrNull()
                    if (output == null) {
                        messages[selected.id] = modelOutputMessages(selected.name, result.exceptionOrNull()?.message.orEmpty(), MessageKind.ERROR)
                    } else {
                        messages[selected.id] = modelOutputMessages(selected.name, output.text)
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
                synchronized(lock) {
                    if (!context.isCurrent() || current?.token != context.token) {
                        overlay?.recycle()
                        return@runIfReady
                    }
                    reports.putAll(messages)
                    layer = overlay?.let { PluginLayer(frame.source, BitmapImage(it)) }
                    publish(context)
                }
            }
            else -> Unit
        }
    }

    private fun restart(context: PluginContext, preserveAudioIntent: Boolean = false) = synchronized(lock) {
        if (!context.isCurrent()) return@synchronized
        if (requested?.context?.token == context.token && requested?.permission == permission) return@synchronized
        current = context
        activeGroup?.cancel()
        activeGroup = null
        invalidateAudio()
        windows = emptyMap()
        if (!preserveAudioIntent) pcmContracts = emptyMap()
        reports.clear()
        if (preserveAudioIntent && permission != AudioPermission.GRANTED) {
            pcmContracts.keys.forEach { id -> reports[id] = listOf(PluginMessage(configuredAudioPermissionMessage(permission), MessageKind.STATUS)) }
        }
        layer = null
        publish(context)
        model.set(Request(context, permission).also { requested = it })
    }

    private fun invalidateAudio() {
        gate.reset()
        windows.values.forEach { it.reset() }
        audioWorker.clear()
        audioPreview = null
    }

    private fun newWindows(): Map<String, ConfiguredPcmWindow> =
        if (permission == AudioPermission.GRANTED && !playing) pcmContracts.mapValues { ConfiguredPcmWindow(it.value) }
        else emptyMap()

    private fun receiveAudio(block: PcmBlock, context: PluginContext) {
        val request = synchronized(lock) {
            if (current?.token != context.token || !context.isCurrent() || permission != AudioPermission.GRANTED || playing) return
            val completed = linkedMapOf<String, FloatArray>()
            var invalid = false
            windows.forEach { (id, window) ->
                try { window.append(block)?.let { completed[id] = it } }
                catch (error: Exception) {
                    window.reset()
                    reports[id] = listOf(PluginMessage("PCM 输入无效：${error.message}", MessageKind.ERROR))
                    invalid = true
                }
            }
            if (invalid) publish(context)
            if (completed.isEmpty()) return
            AudioRequest(context, gate.current(), completed, block.timestampNanos)
        }
        audioWorker.offer(request)
    }

    private fun runAudio(request: AudioRequest) {
        fun current() = request.context.isCurrent() && gate.isCurrent(request.generation)
        if (!current()) return
        model.runIfReady { owner, group ->
            if (owner.context.token != request.context.token || !current()) return@runIfReady
            group.runPcm(request.samples, ::current) { selected, result ->
                val output = result.getOrNull()
                try {
                    val preview = output?.takeIf { it.images.isNotEmpty() }?.let {
                        ConfiguredResultPreview.from(it.copy(sourceLabel = "共享麦克风 · ${selected.name}"))
                    }
                    synchronized(lock) {
                        if (!current() || !gate.accept(request.generation, request.timestampNanos)) return@synchronized
                        reports[selected.id] = if (output != null) modelOutputMessages(selected.name, output.text)
                            else modelOutputMessages(selected.name, result.exceptionOrNull()?.message.orEmpty(), MessageKind.ERROR)
                        if (preview != null) audioPreview = preview
                        if (result.isFailure) {
                            windows = windows - selected.id
                            pcmContracts = pcmContracts - selected.id
                        }
                        publish(request.context)
                    }
                } finally {
                    output?.images?.map { it.second }?.distinct()?.forEach { it.recycle() }
                }
            }
        }
    }

    /** Caller holds lock. File/generated images are reports, never a layer on the current frame. */
    private fun publish(context: PluginContext) {
        if (current?.token != context.token || !context.isCurrent()) return
        val saved = results.value?.takeIf { it.ticket.pluginRevision == context.settings.revision }
        val preview = saved?.preview ?: audioPreview
        val messages = saved?.let { modelOutputMessages(it.ticket.model.name, it.preview.text) }.orEmpty() +
            reports.filterKeys { it != saved?.ticket?.model?.id }.values.flatten()
        context.publish(PluginOutput(
            timestampNanos = System.nanoTime(),
            messages = boundedModelMessages(messages),
            layers = listOfNotNull(layer),
            imageReport = preview?.let { output ->
                (output.input ?: output.output)?.let { image ->
                    PluginImageReport(null, BitmapImage(image),
                        output.output?.takeIf { output.input != null }?.let(::BitmapImage), output.label)
                }
            },
            captureAudio = pcmContracts.isNotEmpty() && !playing,
        ))
    }

    override fun close() {
        synchronized(lock) {
            current = null
            requested = null
            invalidateAudio()
            windows = emptyMap()
            pcmContracts = emptyMap()
            reports.clear()
            layer = null
            activeGroup?.cancel()
            activeGroup = null
        }
        scope.cancel()
        audioWorker.close()
        model.close()
    }
}

private fun openConfiguredRunner(selected: ConfiguredModel, permission: AudioPermission): ConfiguredFrameRunner {
    val description = ConfiguredModelStore.describe(selected)
    val config = ModelConfig.decode(selected.configFile.readText(), description)
    val graph = description.signatures.single { it.key == config.signature }
    configuredExecutionProblem(config, graph)?.let { error(it) }
    val contract = configuredInputContract(config, graph)
    return when (contract.mode) {
        ConfiguredInputMode.CAMERA -> {
            val runner = LiveConfiguredModel(selected, config, graph)
            object : ConfiguredFrameRunner {
                override fun run(image: PluginImage) = runner.run(image.bitmap)
                override fun cancel() = runner.cancel()
                override fun close() = runner.close()
            }
        }
        ConfiguredInputMode.PCM -> {
            val runner = if (permission == AudioPermission.GRANTED) ConfiguredPcmModel(selected, config, graph) else null
            object : ConfiguredFrameRunner {
                override val acceptsFrames = false
                override val pcm = contract.pcm
                override val status = contract.description + if (runner == null) "；${configuredAudioPermissionMessage(permission)}" else ""
                override fun run(image: PluginImage): ModelRunResult = error("需要 PCM 窗口")
                override fun runPcm(samples: FloatArray) = checkNotNull(runner).run(samples)
                override fun cancel() { runner?.cancel() }
                override fun close() { runner?.close() }
            }
        }
        ConfiguredInputMode.ON_DEMAND -> object : ConfiguredFrameRunner {
            override val acceptsFrames = false
            override val status = contract.description
            override fun run(image: PluginImage): ModelRunResult = error("需要单次输入")
            override fun close() {}
        }
    }
}

internal fun configuredAudioPermissionMessage(permission: AudioPermission): String = when (permission) {
    AudioPermission.MICROPHONE_BUSY -> "麦克风不可用（被占用、系统静音或采集失败），请检查系统麦克风开关或稍后重新进入相机"
    AudioPermission.DENIED, AudioPermission.REVOKED -> "请授予麦克风权限以运行已配置的 AUDIO 模型"
    AudioPermission.GRANTED -> "等待完整的新 PCM 窗口"
    else -> "等待麦克风权限"
}

/** Narrow runtime seam also used by the JVM lifecycle/multi-model regression tests. */
internal interface ConfiguredFrameRunner : AutoCloseable {
    val acceptsFrames: Boolean get() = true
    val pcm: ConfiguredPcmContract? get() = null
    val status: String get() = "已就绪"
    fun run(image: PluginImage): ModelRunResult
    fun runPcm(samples: FloatArray): ModelRunResult = error("未配置原始 PCM 输入")
    /** May run concurrently with invoke; close remains owned by the serial worker. */
    fun cancel() {}
}

internal class ConfiguredModelGroup(
    models: List<ConfiguredModel>,
    stillNeeded: () -> Boolean,
    open: (ConfiguredModel) -> ConfiguredFrameRunner,
) : AutoCloseable {
    private class Entry(val model: ConfiguredModel, @Volatile var runner: ConfiguredFrameRunner? = null, var failure: Throwable? = null)
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

    val modelStatus: Map<String, PluginMessage> get() = entries.associate { entry -> entry.model.id to (
        entry.failure?.let { PluginMessage("${entry.model.name}：未就绪：${it.message}", MessageKind.ERROR) }
            ?: PluginMessage("${entry.model.name}：${entry.runner?.status}", MessageKind.STATUS)) }
    val status: List<PluginMessage> get() = modelStatus.values.toList()
    val needsFrames: Boolean get() = entries.any { it.runner?.acceptsFrames == true }
    val pcmContracts: Map<String, ConfiguredPcmContract> get() = entries.mapNotNull { entry ->
        entry.runner?.pcm?.let { entry.model.id to it }
    }.toMap()

    fun run(image: PluginImage, stillNeeded: () -> Boolean, consume: (ConfiguredModel, Result<ModelRunResult>) -> Unit) {
        runEntries(stillNeeded, { it.runner?.acceptsFrames != false }, { runner, _ -> runner.run(image) }, consume)
    }

    fun runPcm(samples: Map<String, FloatArray>, stillNeeded: () -> Boolean, consume: (ConfiguredModel, Result<ModelRunResult>) -> Unit) {
        runEntries(stillNeeded, { it.model.id in samples }, { runner, model -> runner.runPcm(samples.getValue(model.id)) }, consume)
    }

    private fun runEntries(
        stillNeeded: () -> Boolean,
        accepts: (Entry) -> Boolean,
        run: (ConfiguredFrameRunner, ConfiguredModel) -> ModelRunResult,
        consume: (ConfiguredModel, Result<ModelRunResult>) -> Unit,
    ) {
        entries.filter(accepts).forEach { entry ->
            if (!stillNeeded()) return
            val result = entry.failure?.let { Result.failure<ModelRunResult>(it) } ?: try {
                Result.success(run(checkNotNull(entry.runner), entry.model))
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

    fun cancel() { entries.forEach { entry -> runCatching { entry.runner?.cancel() } } }
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
