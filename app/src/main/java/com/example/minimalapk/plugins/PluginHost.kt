package com.example.minimalapk.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** One camera session, bounded output slots, and generation checks shared by every consumer.
 * No callback runs under [lock]: a slow consumer can never prevent disabling it or cancelling a press.
 */
internal class PluginHost(plugins: List<CameraPlugin>) : AutoCloseable {
    init {
        plugins.forEach { plugin ->
            val descriptor = plugin.descriptor
            require(descriptor.id.matches(Regex("[a-z][a-z0-9_]*")))
            require(PluginCapability.REPLACEMENT !in descriptor.capabilities || PluginCapability.POINTER in descriptor.capabilities)
            require(PluginCapability.AUDIO_PCM !in descriptor.capabilities || PluginCapability.AUDIO_PERMISSION in descriptor.capabilities)
        }
    }
    private class Entry(val plugin: CameraPlugin) {
        var settings = PluginSettings()
        var generation = 0L
        var output: PluginOutput? = null
    }

    private val lock = Any()
    private val entries = plugins.associate { it.descriptor.id to Entry(it) }.also {
        require(it.size == plugins.size) { "Duplicate plugin ID" }
    }
    private var session = 0L
    private var entered = false
    private var leavePending = false
    private var active = false
    private var closed = false
    private var frame: CameraFrame? = null
    private var press: PressToken? = null
    private var pressPosition = ImagePoint(0f, 0f)
    private var pressSequence = 0L
    private val pressRecipients = mutableSetOf<PluginToken>()
    private var audioPermission = AudioPermission.NOT_REQUESTED
    private val mutableSnapshot = MutableStateFlow(HostSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    fun configure(settings: Map<String, PluginSettings>) {
        val deliveries = synchronized(lock) {
            if (closed) return
            entries.values.mapNotNull { entry ->
                val next = settings[entry.plugin.descriptor.id] ?: PluginSettings()
                if (entry.settings == next) return@mapNotNull null
                val wasEnabled = entry.settings.enabled
                val oldContext = context(entry)
                entry.settings = next
                entry.generation++
                entry.output = null
                pressRecipients.remove(oldContext.token)
                val event = when {
                    !active && wasEnabled && leavePending -> PluginEvent.Stopped(CameraLifecycle.STOP)
                    !active -> null
                    !next.enabled && wasEnabled -> PluginEvent.Stopped(CameraLifecycle.STOP)
                    next.enabled && !wasEnabled -> PluginEvent.Started
                    next.enabled -> PluginEvent.ConfigurationChanged
                    else -> null
                }
                event?.let { Triple(entry, it, if (next.enabled) context(entry) else oldContext) }
            }.also { refresh() }
        }
        deliveries.forEach { (entry, event, context) ->
            deliver(entry, event, context)
            if (event == PluginEvent.Started && PluginCapability.LIFECYCLE in entry.plugin.descriptor.capabilities) {
                deliver(entry, PluginEvent.Lifecycle(CameraLifecycle.ENTER), context)
                deliver(entry, PluginEvent.Lifecycle(CameraLifecycle.FOREGROUND), context)
            }
            if (event == PluginEvent.Started && PluginCapability.AUDIO_PERMISSION in entry.plugin.descriptor.capabilities) {
                deliver(entry, PluginEvent.AudioPermissionChanged(audioPermission), context)
            }
        }
    }

    fun lifecycle(state: CameraLifecycle) {
        val deliveries = synchronized(lock) {
            if (closed) return
            if (state == CameraLifecycle.ENTER) {
                entered = true
                leavePending = true
                return
            }
            if (state == CameraLifecycle.FOREGROUND) {
                if (!entered || active) return
                active = true
                session = sessions.incrementAndGet()
                refresh()
                entries.values.filter { it.settings.enabled }.flatMap { entry ->
                    val context = context(entry)
                    buildList {
                        add(Triple(entry, PluginEvent.Started, context))
                        if (PluginCapability.LIFECYCLE in entry.plugin.descriptor.capabilities) {
                            add(Triple(entry, PluginEvent.Lifecycle(CameraLifecycle.ENTER), context))
                            add(Triple(entry, PluginEvent.Lifecycle(state), context))
                        }
                        if (PluginCapability.AUDIO_PERMISSION in entry.plugin.descriptor.capabilities) {
                            add(Triple(entry, PluginEvent.AudioPermissionChanged(audioPermission), context))
                        }
                    }
                }
            } else {
                val notifyExit = leavePending && state in setOf(CameraLifecycle.LEAVE, CameraLifecycle.STOP)
                val recipients = entries.values.filter { (active || notifyExit) && it.settings.enabled }
                val oldPress = press
                val oldPressRecipients = pressRecipients.toSet()
                active = false
                if (state == CameraLifecycle.LEAVE || state == CameraLifecycle.STOP) {
                    entered = false
                    leavePending = false
                }
                frame = null
                press = null
                pressRecipients.clear()
                entries.values.forEach { it.output = null }
                audioPermission = AudioPermission.NOT_REQUESTED
                refresh()
                recipients.flatMap { entry ->
                    val context = context(entry)
                    buildList {
                        if (oldPress != null && context.token in oldPressRecipients) {
                            val reason = when (state) {
                                CameraLifecycle.BACKGROUND -> CancelReason.BACKGROUND
                                CameraLifecycle.LEAVE -> CancelReason.LEAVE
                                else -> CancelReason.STOP
                            }
                            add(Triple(entry, PluginEvent.Cancel(oldPress, pressPosition, System.nanoTime(), reason), context))
                        }
                        if (PluginCapability.LIFECYCLE in entry.plugin.descriptor.capabilities) {
                            add(Triple(entry, PluginEvent.Lifecycle(state), context))
                        }
                        add(Triple(entry, PluginEvent.Stopped(state), context))
                    }
                }
            }
        }
        deliveries.forEach { (entry, event, context) -> deliver(entry, event, context) }
    }

    fun sessionId(): Long? = synchronized(lock) { session.takeIf { active && !closed } }

    /** Called on the single CameraX analysis worker. Holding its proxy bounds synchronous consumers.
     * Async plugins should retain this immutable frame and use a single latest-frame worker.
     */
    fun acceptFrame(next: CameraFrame) {
        synchronized(lock) {
            if (!active || next.source.session != session || (frame?.source?.frameNumber ?: -1) >= next.source.frameNumber) return
            frame = if (next.poseToken != null && !valid(next.poseToken)) next.copy(poses = emptyList()) else next
            refresh()
        }
        dispatch(PluginCapability.POSE_RESULTS, PluginEvent.Pose(next))
        dispatch(PluginCapability.CAMERA_FRAMES, PluginEvent.Frame(next))
    }

    fun viewport(width: Int, height: Int) {
        cancel(CancelReason.VIEWPORT_CHANGED)
        synchronized(lock) {
            frame = frame?.let { it.copy(viewport = ViewportMapping(it.source.space, width, height)) }
            refresh()
        }
    }

    fun hasSubscribers(capability: PluginCapability): Boolean = synchronized(lock) {
        active && entries.values.any { it.settings.enabled && capability in it.plugin.descriptor.capabilities }
    }

    /** A permission request/capture owner must retain the session it was started in. */
    fun permissionChanged(sessionId: Long, permission: AudioPermission) {
        synchronized(lock) {
            if (!active || closed || sessionId != session || permission == audioPermission) return
            audioPermission = permission
            entries.values.filter { entry ->
                entry.settings.enabled && entry.plugin.descriptor.capabilities.any {
                    it == PluginCapability.AUDIO_PCM || it == PluginCapability.AUDIO_PERMISSION
                }
            }.forEach { entry ->
                // A late transcript from an earlier permission grant cannot restore revoked output.
                pressRecipients.remove(context(entry).token)
                entry.generation++
                entry.output = null
            }
            refresh()
        }
        dispatch(PluginCapability.AUDIO_PERMISSION, PluginEvent.AudioPermissionChanged(permission), sessionId)
    }

    fun audio(sessionId: Long, block: PcmBlock) {
        dispatch(PluginCapability.AUDIO_PCM, PluginEvent.Audio(block), sessionId)
    }

    fun press(pointerId: Long, position: ImagePoint, timestampNanos: Long): PressToken? {
        if (synchronized(lock) { press?.let { it.pointerId != pointerId } == true }) {
            cancel(CancelReason.MULTITOUCH, timestampNanos)
            return null
        }
        val deliveries = synchronized(lock) {
            val current = frame ?: return null
            if (!active || press != null || !current.viewport.containsPhoto(position) ||
                entries.values.none { it.settings.enabled && PluginCapability.POINTER in it.plugin.descriptor.capabilities }) return null
            val token = PressToken(session, ++pressSequence, pointerId, timestampNanos, current.source.frameNumber)
            press = token
            pressPosition = position
            pressRecipients.clear()
            entries.values.filter { it.settings.enabled && PluginCapability.POINTER in it.plugin.descriptor.capabilities }
                .forEach { pressRecipients += context(it).token }
            refresh()
            val event = PluginEvent.Press(token, position, timestampNanos)
            entries.values.filter { context(it).token in pressRecipients }.map { Triple(it, event, context(it)) }
        }
        deliveries.forEach { (entry, event, context) -> if (context.isCurrent()) deliver(entry, event, context) }
        return deliveries.firstOrNull()?.second?.token
    }

    fun move(pointerId: Long, position: ImagePoint, timestampNanos: Long) {
        val outside = synchronized(lock) {
            if (press?.pointerId != pointerId) return
            pressPosition = position
            frame?.viewport?.containsPhoto(position) != true
        }
        if (outside) cancel(CancelReason.OUT_OF_BOUNDS, timestampNanos)
    }

    fun release(pointerId: Long, position: ImagePoint, timestampNanos: Long) {
        finishPress(pointerId, position, timestampNanos, null)
    }

    fun cancel(reason: CancelReason, timestampNanos: Long = System.nanoTime()) {
        val current = synchronized(lock) { press to pressPosition }
        current.first?.let { finishPress(it.pointerId, current.second, timestampNanos, reason) }
    }

    private fun finishPress(pointerId: Long, position: ImagePoint, time: Long, reason: CancelReason?) {
        val deliveries = synchronized(lock) {
            val current = press?.takeIf { it.pointerId == pointerId } ?: return
            val event = if (reason == null) PluginEvent.Release(current, position, time)
                else PluginEvent.Cancel(current, position, time, reason)
            val recipients = entries.values.filter { context(it).token in pressRecipients }
            press = null
            pressRecipients.clear()
            // Remove rather than merely hide candidates: a subsequent press cannot resurrect them.
            entries.values.forEach { entry -> entry.output = entry.output?.copy(replacement = null) }
            refresh()
            recipients.map { Triple(it, event, context(it)) }
        }
        deliveries.forEach { (entry, event, context) -> deliver(entry, event, context) }
    }

    fun isCurrent(token: PluginToken): Boolean = synchronized(lock) { valid(token) }

    fun currentPress(token: PluginToken): PressToken? = synchronized(lock) {
        press.takeIf { valid(token) && token in pressRecipients }
    }

    fun contextFor(id: String): PluginContext? = synchronized(lock) {
        entries[id]?.let { context(it) }?.takeIf { valid(it.token) }
    }

    fun publish(token: PluginToken, output: PluginOutput): Boolean = synchronized(lock) {
        if (!valid(token)) return false
        val entry = entries.getValue(token.pluginId)
        if (output.timestampNanos < (entry.output?.timestampNanos ?: Long.MIN_VALUE)) return false
        val capabilities = entry.plugin.descriptor.capabilities
        val replacement = output.replacement
        if (replacement != null && (PluginCapability.REPLACEMENT !in capabilities ||
                token !in pressRecipients || replacement.press != press ||
                output.timestampNanos < replacement.press.startedNanos ||
                replacement.source.session != session ||
                replacement.source.frameNumber < replacement.press.firstFrame ||
                replacement.source.frameNumber > (frame?.source?.frameNumber ?: -1) ||
                !sameAspect(replacement.source.space, replacement.image))) return false
        val layers = if (PluginCapability.LAYER in capabilities || PluginCapability.MONITOR in capabilities) {
            output.layers.take(MAX_LAYERS).filter { layer ->
                layer.source.session == session && (layer.image == null || sameAspect(layer.source.space, layer.image))
            }.map { it.copy(lines = it.lines.take(MAX_LINES).map { line -> line.copy(label = line.label?.take(80)) }) }
        } else emptyList()
        entry.output = output.copy(
            messages = if (PluginCapability.TEXT in capabilities) output.messages.take(MAX_MESSAGES)
                .map { it.copy(text = it.text.take(MAX_MESSAGE_CHARS)) } else emptyList(),
            layers = layers,
        )
        refresh()
        true
    }

    private fun dispatch(capability: PluginCapability, event: PluginEvent, expectedSession: Long? = null) {
        val recipients = synchronized(lock) {
            if (!active || closed) return
            if (expectedSession != null && expectedSession != session) return
            if (event is PluginEvent.Audio && audioPermission != AudioPermission.GRANTED) return
            val sourceSession = when (event) {
                is PluginEvent.Frame -> event.frame.source.session
                is PluginEvent.Pose -> event.frame.source.session
                else -> session
            }
            if (sourceSession != session) return
            entries.values.filter { it.settings.enabled && capability in it.plugin.descriptor.capabilities }
                .map { it to context(it) }
        }
        recipients.forEach { (entry, context) ->
            if (isCurrent(context.token)) {
                fun clean(frame: CameraFrame): CameraFrame =
                    if (frame.poseToken?.let { !isCurrent(it) } == true) frame.copy(poses = emptyList(), executionProvider = null)
                    else frame
                val sanitized = when (event) {
                    is PluginEvent.Pose -> event.copy(frame = clean(event.frame))
                    is PluginEvent.Frame -> event.copy(frame = clean(event.frame))
                    else -> event
                }
                deliver(entry, sanitized, context)
            }
        }
    }

    private fun deliver(entry: Entry, event: PluginEvent, context: PluginContext) {
        // Stopped/cancel/exit deliberately carry the invalidated token for resource cleanup.
        val terminal = event is PluginEvent.Stopped || event is PluginEvent.Cancel || event is PluginEvent.Release ||
            (event is PluginEvent.Lifecycle && event.state in setOf(CameraLifecycle.BACKGROUND, CameraLifecycle.LEAVE, CameraLifecycle.STOP))
        if (!terminal && !context.isCurrent()) return
        try {
            entry.plugin.onEvent(event, context)
        } catch (_: CancellationException) {
            // Normal lifecycle cancellation is not a plugin fault.
        } catch (error: Throwable) {
            synchronized(lock) {
                if (valid(context.token)) {
                    entry.output = PluginOutput(System.nanoTime(), listOf(
                        PluginMessage((error.message ?: "插件运行失败").take(MAX_MESSAGE_CHARS), MessageKind.ERROR),
                    ))
                    refresh()
                }
            }
        }
    }

    private fun context(entry: Entry) = PluginContext(
        PluginToken(entry.plugin.descriptor.id, session, entry.generation), entry.settings, this,
    )

    private fun valid(token: PluginToken): Boolean = !closed && active && token.session == session &&
        entries[token.pluginId]?.let { it.settings.enabled && it.generation == token.generation } == true

    private fun refresh() {
        val contributions = entries.values.sortedBy { it.plugin.descriptor.id }.mapNotNull { entry ->
            entry.output?.let { PluginContribution(entry.plugin.descriptor.id, entry.plugin.descriptor.name, it) }
        }
        val replacements = contributions.filter { it.output.replacement?.press == press && press != null }
            .sortedWith(compareByDescending<PluginContribution> {
                entries.getValue(it.pluginId).plugin.descriptor.replacementPriority
            }.thenBy { it.pluginId })
        val winner = replacements.firstOrNull()
        val source = winner?.output?.replacement?.source ?: frame?.source
        val compatible = contributions.mapNotNull { contribution ->
            contribution.output.layers.filter { it.source == source }.takeIf { it.isNotEmpty() }
                ?.let { contribution.copy(output = contribution.output.copy(layers = it)) }
        }
        val reports = contributions.map { contribution ->
            if (replacements.size > 1 && contribution in replacements.drop(1)) {
                contribution.copy(output = contribution.output.copy(messages =
                    (contribution.output.messages.take(MAX_MESSAGES - 1) +
                        PluginMessage("主画面由 ${winner!!.name} 替换（优先级 / 插件 ID 仲裁）", MessageKind.STATUS)),
                ))
            } else contribution
        }.filter { it.output.messages.isNotEmpty() }
        mutableSnapshot.value = HostSnapshot(
            session = session.takeIf { active && !closed },
            frame = frame?.let { if (it.poseToken != null && !valid(it.poseToken)) it.copy(poses = emptyList(), executionProvider = null) else it },
            replacement = winner,
            layers = compatible.filter { PluginCapability.MONITOR !in entries.getValue(it.pluginId).plugin.descriptor.capabilities },
            monitors = compatible.filter { PluginCapability.MONITOR in entries.getValue(it.pluginId).plugin.descriptor.capabilities },
            reports = reports,
            press = press,
        )
    }

    override fun close() {
        lifecycle(CameraLifecycle.STOP)
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        entries.values.forEach { runCatching { it.plugin.close() } }
    }

    companion object {
        private val sessions = AtomicLong()
        const val MAX_MESSAGES = 8
        const val MAX_MESSAGE_CHARS = 240
        const val MAX_LAYERS = 4
        const val MAX_LINES = 128

        private fun sameAspect(space: ImageSpace, image: PluginImage): Boolean =
            image.width > 0 && image.height > 0 &&
                image.width.toLong() * space.height == image.height.toLong() * space.width
    }
}
