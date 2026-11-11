package com.example.minimalapk.plugins

internal data class AudioCaptureTicket(val session: Long, val sequence: Long)

/** Platform observations, not a heuristic based on quiet/all-zero PCM. */
internal enum class AudioCaptureAvailability(val reason: String) {
    AVAILABLE("麦克风可用"),
    MICROPHONE_MUTED("AudioManager.isMicrophoneMute=true（系统麦克风静音）"),
    CLIENT_SILENCED("AudioRecordingConfiguration.isClientSilenced=true（系统隐私开关或录音竞争）"),
    CONFIGURATION_UNAVAILABLE("AudioRecord.activeRecordingConfiguration=null（暂无法确认录音状态）"),
}

internal enum class AudioReadOutcome { DELIVER, DROP, EMPTY, STOP, FAILED }

internal data class AudioCaptureRead(
    val ticket: AudioCaptureTicket,
    val revision: Long,
    val available: Boolean,
    val reported: Boolean,
)

internal data class AudioCaptureReport(
    val session: Long,
    val revision: Long,
    val permission: AudioPermission,
    val withdrawBeforeGrant: Boolean,
)

/**
 * Pure capture ownership/availability state. The Android owner serializes calls with its lock.
 * A stopped ticket retains ownership until release, even across a quick background/playback
 * round trip. Mute keeps that one foreground recorder alive to observe unsilencing; a read/start
 * failure instead latches until a new foreground/session or a fresh runtime permission grant.
 */
internal class AudioCaptureGate {
    var foreground = false
        private set
    var subscribed = false
        private set
    var session: Long? = null
        private set
    private var runtimePermission = AudioPermission.NOT_REQUESTED
    private var playbackPaused = false
    private var failed = false
    private var availability = AudioCaptureAvailability.AVAILABLE
    private var observed = false
    private var owner: AudioCaptureTicket? = null
    private var stopping = false
    private var sequence = 0L
    private var inputRevision = 0L
    private var bufferBytes = 0
    private var discardBytes = 0
    private var reportRevision = 0L
    private var acknowledgedRevision = -1L
    private var withdrawalPending = false

    val permission: AudioPermission
        get() = when {
            runtimePermission != AudioPermission.GRANTED -> runtimePermission
            failed || availability != AudioCaptureAvailability.AVAILABLE -> AudioPermission.MICROPHONE_BUSY
            else -> AudioPermission.GRANTED
        }

    val shouldCapture: Boolean
        get() = foreground && subscribed && session != null && !playbackPaused &&
            runtimePermission == AudioPermission.GRANTED && !failed

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        val previous = permission
        foreground = value
        if (value) {
            failed = false
            availability = AudioCaptureAvailability.AVAILABLE
            observed = false
        } else stop()
        permissionUpdated(previous)
    }

    fun setSubscribed(value: Boolean) {
        subscribed = value
        if (!value) stop()
    }

    fun setSession(value: Long?) {
        if (session == value) return
        stop()
        session = value
        failed = false
        availability = AudioCaptureAvailability.AVAILABLE
        observed = false
        inputRevision++
        reportRevision++
        acknowledgedRevision = -1
        withdrawalPending = false
    }

    fun setPermission(value: AudioPermission) {
        if (runtimePermission == value) return
        val previous = permission
        runtimePermission = value
        if (value == AudioPermission.GRANTED) failed = false else stop()
        permissionUpdated(previous)
    }

    fun setPlaybackPaused(value: Boolean) {
        playbackPaused = value
        if (value) stop()
    }

    fun begin(): AudioCaptureTicket? {
        if (!shouldCapture || owner != null) return null
        return AudioCaptureTicket(checkNotNull(session), ++sequence).also {
            owner = it
            stopping = false
            observed = false
            inputRevision++
        }
    }

    fun isRunning(ticket: AudioCaptureTicket): Boolean =
        owner == ticket && !stopping && shouldCapture && session == ticket.session

    fun bufferSize(ticket: AudioCaptureTicket, bytes: Int) {
        require(bytes > 0)
        if (isRunning(ticket)) bufferBytes = bytes
    }

    /** Returns true only for a new, current observation (also bounds diagnostic logging). */
    fun observe(ticket: AudioCaptureTicket, next: AudioCaptureAvailability): Boolean {
        if (!isRunning(ticket) || observed && availability == next) return false
        val previous = permission
        val wasBlocked = availability != AudioCaptureAvailability.AVAILABLE
        availability = next
        observed = true
        inputRevision++
        // Drain at least the native buffer after recovery, not just the read spanning the change.
        if (wasBlocked || next != AudioCaptureAvailability.AVAILABLE) discardBytes = bufferBytes
        permissionUpdated(previous)
        return true
    }

    fun beginRead(ticket: AudioCaptureTicket): AudioCaptureRead? {
        if (!isRunning(ticket)) return null
        return AudioCaptureRead(ticket, inputRevision,
            observed && availability == AudioCaptureAvailability.AVAILABLE,
            acknowledgedRevision == reportRevision)
    }

    fun readCompleted(read: AudioCaptureRead, count: Int): AudioReadOutcome {
        // AudioRecord.stop() can make a pending read return an error: that is not a failure.
        if (!isRunning(read.ticket)) return AudioReadOutcome.STOP
        if (count < 0) {
            fail(read.ticket)
            return AudioReadOutcome.FAILED
        }
        if (count == 0) return AudioReadOutcome.EMPTY
        if (!read.available || read.revision != inputRevision ||
            !observed || availability != AudioCaptureAvailability.AVAILABLE) return AudioReadOutcome.DROP
        if (discardBytes > 0) {
            discardBytes = (discardBytes - count).coerceAtLeast(0)
            return AudioReadOutcome.DROP
        }
        return if (canDeliver(read)) AudioReadOutcome.DELIVER else AudioReadOutcome.DROP
    }

    fun canDeliver(read: AudioCaptureRead): Boolean = isRunning(read.ticket) && read.available &&
        read.reported && read.revision == inputRevision && observed && discardBytes == 0 &&
        permission == AudioPermission.GRANTED && acknowledgedRevision == reportRevision

    fun fail(ticket: AudioCaptureTicket): Boolean {
        if (!isRunning(ticket)) return false
        val previous = permission
        failed = true
        stop()
        permissionUpdated(previous)
        return true
    }

    fun stop() {
        if (owner != null && !stopping) {
            stopping = true
            inputRevision++
        }
    }

    /** Called only after AudioRecord.release(), never merely after requesting stop. */
    fun released(ticket: AudioCaptureTicket) {
        if (owner != ticket) return
        owner = null
        stopping = false
        observed = false
        bufferBytes = 0
        discardBytes = 0
        inputRevision++
    }

    fun report(): AudioCaptureReport? {
        val current = session ?: return null
        if (!foreground || acknowledgedRevision == reportRevision) return null
        return AudioCaptureReport(current, reportRevision, permission,
            withdrawalPending && permission == AudioPermission.GRANTED)
    }

    fun acknowledge(report: AudioCaptureReport) {
        if (foreground && session == report.session && reportRevision == report.revision) {
            acknowledgedRevision = reportRevision
            withdrawalPending = false
        }
    }

    private fun permissionUpdated(previous: AudioPermission) {
        if (permission == previous) return
        reportRevision++
        inputRevision++
        // A mute/unmute before the UI drains its notification still withdraws pre-mute results.
        if (permission != AudioPermission.GRANTED) withdrawalPending = true
    }
}
