package com.example.minimalapk.plugins

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.minimalapk.log.AppLog
import java.nio.ByteBuffer

internal const val AUDIO_SAMPLE_RATE = 16_000
internal const val AUDIO_CHANNEL_COUNT = 1
internal const val AUDIO_CHUNK_MS = 40
internal const val AUDIO_CHUNK_MIN_MS = 20
internal const val AUDIO_CHUNK_MAX_MS = 100

/** Capture format is deliberately narrow: one shared 16 kHz mono PCM-16-LE stream. */
internal fun pcmChunkBytes(
    sampleRate: Int = AUDIO_SAMPLE_RATE,
    channels: Int = AUDIO_CHANNEL_COUNT,
    format: PcmFormat = PcmFormat.PCM_16_LE,
    chunkMs: Int = AUDIO_CHUNK_MS,
): Int {
    require(format == PcmFormat.PCM_16_LE) { "Only PCM 16-bit LE capture is supported" }
    require(sampleRate in 8_000..48_000) { "Unsupported sample rate: $sampleRate" }
    require(channels in 1..2) { "Unsupported channel count: $channels" }
    require(chunkMs in AUDIO_CHUNK_MIN_MS..AUDIO_CHUNK_MAX_MS) {
        "Chunk must be ${AUDIO_CHUNK_MIN_MS}..${AUDIO_CHUNK_MAX_MS} ms"
    }
    val frameBytes = channels * format.bytesPerSample
    val bytes = sampleRate.toLong() * frameBytes * chunkMs / 1_000L
    return (bytes - bytes % frameBytes).toInt().coerceAtLeast(frameBytes)
}

internal fun audioPermissionAfterCheck(
    granted: Boolean,
    requested: Boolean,
    previous: AudioPermission,
): AudioPermission = when {
    granted -> AudioPermission.GRANTED
    previous == AudioPermission.GRANTED || previous == AudioPermission.MICROPHONE_BUSY -> AudioPermission.REVOKED
    requested -> AudioPermission.DENIED
    else -> AudioPermission.NOT_REQUESTED
}

/**
 * The single owner of the microphone for plugin consumers. [CameraSession] drives foreground/
 * background; settings changes call [refresh] so disabling the last AUDIO_PCM consumer releases
 * the microphone. One bounded, blocking read loop means a slow consumer drops samples in the
 * AudioRecord buffer instead of growing an app queue, and raw audio never reaches disk.
 */
internal class AudioCaptureHost(
    context: Context,
    private val host: PluginHost,
) : AutoCloseable {
    private val application = context.applicationContext
    private val main = ContextCompat.getMainExecutor(application)
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private val gate = AudioCaptureGate()
    private var capture: Capture? = null
    private var playbackPaused = false
    private var reportPosted = false
    @Volatile private var closed = false

    private class Capture(val ticket: AudioCaptureTicket) {
        // Start/stop/release are serialized on this object; a blocking read never holds it.
        var record: AudioRecord? = null
        var started = false
        var startedNanos = 0L
        var configurationSeen = false
        var callback: AudioManager.AudioRecordingCallback? = null
    }

    fun permissionChanged(permission: AudioPermission) {
        if (closed) return
        synchronized(lock) { gate.setPermission(permission) }
        report()
        reconcile()
    }

    fun playbackChanged(playing: Boolean) {
        if (closed) return
        synchronized(lock) {
            playbackPaused = playing
            gate.setPlaybackPaused(playing)
        }
        host.playbackChanged(playing)
        reconcile()
    }

    /** Re-reads the session and subscriber set after configure/lifecycle changes. */
    fun refresh() {
        if (closed) return
        val session = host.sessionId()
        val subscribed = host.hasSubscribers(PluginCapability.AUDIO_PCM)
        synchronized(lock) {
            gate.setSession(session)
            gate.setSubscribed(subscribed)
        }
        report()
        reconcile()
    }

    fun onForeground() {
        if (closed) return
        synchronized(lock) {
            gate.setForeground(true)
            // Recheck even if the permission UI has not delivered its resume callback yet.
            gate.setPermission(audioPermissionAfterCheck(hasPermission(),
                requested = gate.permission != AudioPermission.NOT_REQUESTED, previous = gate.permission))
        }
        refresh()
    }

    fun onBackground() {
        synchronized(lock) {
            gate.setForeground(false)
            gate.setSubscribed(false)
            gate.setSession(null)
        }
        stopCapture()
    }

    private fun reconcile() {
        if (closed) {
            stopCapture()
            return
        }
        val current = synchronized(lock) { capture }
        if (current != null) {
            if (!synchronized(lock) { gate.isRunning(current.ticket) && !playbackPaused }) stopCapture()
            // Retain ownership until the worker has released the old recorder.
            return
        }
        val next = synchronized(lock) {
            gate.begin()?.let { Capture(it).also { owner -> capture = owner } }
        } ?: return
        Thread({ captureLoop(next) }, "AudioCapture").start()
    }

    private fun isRunning(capture: Capture): Boolean = synchronized(lock) {
        !closed && this.capture === capture && gate.isRunning(capture.ticket)
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop(capture: Capture) {
        try {
            if (!isRunning(capture)) return
            if (!hasPermission()) {
                permissionLost(capture)
                return
            }
            val chunkBytes = pcmChunkBytes()
            val minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                failed(capture, "AudioRecord.getMinBufferSize=$minBuffer")
                return
            }
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuffer, chunkBytes * 2))
                .build()
            synchronized(capture) {
                capture.record = record
                if (!isRunning(capture)) return
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    failed(capture, "AudioRecord.state=${record.state}（初始化失败）")
                    return
                }
                synchronized(lock) {
                    gate.bufferSize(capture.ticket,
                        record.bufferSizeInFrames * AUDIO_CHANNEL_COUNT * PcmFormat.PCM_16_LE.bytesPerSample)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val callback = object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                            // AudioRecord filters to its own port. Preserve even a short silencing
                            // notification, but query current state before allowing recovery.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                observeAvailability(capture, wasSilenced = configs.any { it.isClientSilenced })
                            }
                        }
                    }
                    record.registerAudioRecordingCallback(main, callback)
                    capture.callback = callback
                }
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    failed(capture, "AudioRecord.recordingState=${record.recordingState}（未开始录音）")
                    return
                }
                capture.started = true
                capture.startedNanos = System.nanoTime()
            }
            val buffer = ByteArray(chunkBytes)
            while (isRunning(capture)) {
                observeAvailability(capture)
                val pending = synchronized(lock) { gate.beginRead(capture.ticket) } ?: break
                val read = record.read(buffer, 0, buffer.size)
                // Observe both sides of the read; never deliver a block spanning mute/recovery.
                if (read >= 0) observeAvailability(capture)
                val outcome = synchronized(lock) { gate.readCompleted(pending, read) }
                when (outcome) {
                    AudioReadOutcome.DELIVER -> {
                        val frameBytes = AUDIO_CHANNEL_COUNT * PcmFormat.PCM_16_LE.bytesPerSample
                        val aligned = read - read % frameBytes
                        if (aligned > 0 && synchronized(lock) { gate.canDeliver(pending) }) {
                            host.audio(capture.ticket.session, PcmBlock(
                                ByteBuffer.wrap(buffer.copyOf(aligned)).asReadOnlyBuffer(),
                                AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT, PcmFormat.PCM_16_LE, System.nanoTime(),
                            ))
                        }
                    }
                    AudioReadOutcome.FAILED -> {
                        AppLog.w("AudioCapture", "麦克风采集不可用：AudioRecord.read=$read (${audioReadError(read)})")
                        postReport()
                        break
                    }
                    AudioReadOutcome.STOP -> break
                    // A broken vendor returning zero immediately must not create a hot loop.
                    AudioReadOutcome.EMPTY -> Thread.sleep(AUDIO_CHUNK_MS.toLong())
                    AudioReadOutcome.DROP -> Unit
                }
            }
        } catch (error: SecurityException) {
            if (hasPermission()) failed(capture, "麦克风采集被系统拒绝", error)
            else permissionLost(capture)
        } catch (error: Throwable) {
            failed(capture, "麦克风初始化/采集失败", error)
        } finally {
            synchronized(capture) {
                val record = capture.record
                if (record != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        capture.callback?.let { runCatching { record.unregisterAudioRecordingCallback(it) } }
                    }
                    runCatching { record.stop() }
                    runCatching { record.release() }
                }
                capture.record = null
                capture.started = false
            }
            main.execute {
                synchronized(lock) {
                    gate.released(capture.ticket)
                    if (this.capture === capture) this.capture = null
                }
                report()
                reconcile()
            }
        }
    }

    /** API24 mute observation; API29 adds per-recorder privacy/concurrent-capture silencing.
     * Android's microphone privacy switch is not necessarily reflected by isMicrophoneMute(). */
    private fun observeAvailability(capture: Capture, wasSilenced: Boolean = false) {
        try {
            synchronized(capture) {
                if (!capture.started || !isRunning(capture)) return
                val record = capture.record ?: return
                if (wasSilenced) {
                    capture.configurationSeen = true
                    observed(capture, AudioCaptureAvailability.CLIENT_SILENCED)
                }
                val availability = when {
                    audioManager.isMicrophoneMute -> AudioCaptureAvailability.MICROPHONE_MUTED
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                        val configuration = record.activeRecordingConfiguration
                        // The service's initial recording notification can lag startRecording().
                        // Drop unconfirmed PCM during this bounded grace, rather than cancel a
                        // short batch capture on a transient null before its first read completes.
                        if (configuration == null && !capture.configurationSeen &&
                            System.nanoTime() - capture.startedNanos < 250_000_000L) return
                        if (configuration != null) capture.configurationSeen = true
                        when {
                            configuration == null -> AudioCaptureAvailability.CONFIGURATION_UNAVAILABLE
                            configuration.isClientSilenced -> AudioCaptureAvailability.CLIENT_SILENCED
                            else -> AudioCaptureAvailability.AVAILABLE
                        }
                    }
                    else -> AudioCaptureAvailability.AVAILABLE
                }
                observed(capture, availability)
            }
        } catch (error: Throwable) {
            failed(capture, "无法确认麦克风静音/录音状态", error)
        }
    }

    private fun observed(capture: Capture, availability: AudioCaptureAvailability) {
        if (synchronized(lock) { gate.observe(capture.ticket, availability) }) {
            if (availability != AudioCaptureAvailability.AVAILABLE) {
                AppLog.w("AudioCapture", "停止转发麦克风 PCM：${availability.reason}")
            }
            postReport()
        }
    }

    private fun permissionLost(capture: Capture) {
        val changed = synchronized(lock) {
            if (!gate.isRunning(capture.ticket)) false else {
                gate.setPermission(AudioPermission.REVOKED)
                true
            }
        }
        if (changed) {
            AppLog.w("AudioCapture", "RECORD_AUDIO 权限已撤销，停止采集")
            postReport()
        }
    }

    private fun failed(capture: Capture, reason: String, error: Throwable? = null) {
        if (synchronized(lock) { gate.fail(capture.ticket) }) {
            AppLog.w("AudioCapture", reason, error)
            postReport()
        }
    }

    private fun stopCapture() {
        val current = synchronized(lock) {
            gate.stop()
            capture
        } ?: return
        synchronized(current) {
            // Unblock a pending read; the worker is the only releaser.
            runCatching { current.record?.stop() }
        }
    }

    /** One bounded UI notification; even a quick mute/unmute invalidates old model contexts. */
    private fun postReport() {
        synchronized(lock) {
            if (closed || reportPosted) return
            reportPosted = true
        }
        main.execute {
            synchronized(lock) { reportPosted = false }
            report()
            reconcile()
        }
    }

    private fun report() {
        if (closed) return
        val status = synchronized(lock) { gate.report() } ?: return
        if (status.withdrawBeforeGrant) host.permissionChanged(status.session, AudioPermission.MICROPHONE_BUSY)
        host.permissionChanged(status.session, status.permission)
        synchronized(lock) { gate.acknowledge(status) }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun close() {
        closed = true
        synchronized(lock) {
            gate.setForeground(false)
            gate.setSubscribed(false)
            gate.setSession(null)
        }
        stopCapture()
    }
}

private fun audioReadError(code: Int): String = when (code) {
    AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
    AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
    AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
    AudioRecord.ERROR -> "ERROR"
    else -> "UNKNOWN_ERROR"
}
