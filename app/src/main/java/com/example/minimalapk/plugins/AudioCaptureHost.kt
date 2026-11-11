package com.example.minimalapk.plugins

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

/** Pure subscription/lifecycle gate. Capture never starts for a backgrounded, unsubscribed,
 * unpermissioned, or session-less camera. */
internal class AudioCaptureGate {
    var foreground: Boolean = false
        private set
    var subscribed: Boolean = false
        private set
    var session: Long? = null
        private set
    var permission: AudioPermission = AudioPermission.NOT_REQUESTED
        private set

    val shouldCapture: Boolean
        get() = foreground && subscribed && session != null && permission == AudioPermission.GRANTED

    fun setForeground(value: Boolean) {
        foreground = value
    }

    fun setSubscribed(value: Boolean) {
        subscribed = value
    }

    fun setSession(value: Long?) {
        session = value
    }

    fun setPermission(value: AudioPermission) {
        permission = value
    }
}

internal fun audioPermissionAfterCheck(
    granted: Boolean,
    requested: Boolean,
    previous: AudioPermission,
): AudioPermission = when {
    granted -> AudioPermission.GRANTED
    previous == AudioPermission.GRANTED -> AudioPermission.REVOKED
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
    private val lock = Any()
    private val gate = AudioCaptureGate()
    private var capture: Capture? = null
    @Volatile private var closed = false

    private class Capture(val session: Long, val record: AudioRecord) {
        @Volatile var running = true
    }

    fun permissionChanged(permission: AudioPermission) {
        synchronized(lock) { gate.setPermission(permission) }
        report()
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
            // A previous MICROPHONE_BUSY is retried once the host returns to the foreground.
            if (gate.permission == AudioPermission.MICROPHONE_BUSY) {
                gate.setPermission(AudioPermission.GRANTED)
            }
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
        val shouldCapture = synchronized(lock) { gate.shouldCapture }
        val session = synchronized(lock) { gate.session }
        if (shouldCapture && session != null) {
            if (capture?.session != session || capture?.running != true) startCapture(session)
        } else {
            stopCapture()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(session: Long) {
        if (!hasPermission()) {
            val next = synchronized(lock) {
                audioPermissionAfterCheck(false, requested = true, previous = gate.permission)
                    .also { gate.setPermission(it) }
            }
            report()
            if (next == AudioPermission.REVOKED) stopCapture()
            return
        }
        val chunkBytes = pcmChunkBytes()
        val minBuffer = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            reportMicrophoneBusy(session)
            return
        }
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, chunkBytes * 2))
                .build()
        } catch (error: Throwable) {
            AppLog.w("AudioCapture", "麦克风初始化失败", error)
            reportMicrophoneBusy(session)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            reportMicrophoneBusy(session)
            return
        }
        val next = Capture(session, record)
        synchronized(lock) {
            if (closed || !gate.shouldCapture || gate.session != session) {
                runCatching { record.release() }
                return
            }
            capture = next
        }
        Thread({ captureLoop(next) }, "AudioCapture").start()
    }

    private fun captureLoop(capture: Capture) {
        try {
            if (!capture.running) return
            capture.record.startRecording()
            if (capture.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                capture.running = false
                reportMicrophoneBusy(capture.session)
                return
            }
            val buffer = ByteArray(pcmChunkBytes())
            while (capture.running && !closed) {
                val read = capture.record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    // PcmBlock requires whole frames; a partial trailing sample is dropped.
                    val frameBytes = AUDIO_CHANNEL_COUNT * PcmFormat.PCM_16_LE.bytesPerSample
                    val aligned = read - read % frameBytes
                    if (aligned > 0) {
                        val samples = ByteBuffer.wrap(buffer.copyOf(aligned)).asReadOnlyBuffer()
                        host.audio(
                            capture.session,
                            PcmBlock(
                                samples, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT,
                                PcmFormat.PCM_16_LE, System.nanoTime(),
                            ),
                        )
                    }
                } else if (read < 0) {
                    break
                }
            }
        } catch (error: Throwable) {
            if (capture.running && !closed) {
                AppLog.w("AudioCapture", "麦克风采集失败", error)
                reportMicrophoneBusy(capture.session)
            }
        } finally {
            runCatching { capture.record.stop() }
            runCatching { capture.record.release() }
            synchronized(lock) { if (this.capture === capture) this.capture = null }
        }
    }

    private fun stopCapture() {
        val current = synchronized(lock) {
            val value = capture
            capture = null
            value
        } ?: return
        current.running = false
        // Unblocks a pending AudioRecord.read; the capture thread releases the record afterwards.
        runCatching { current.record.stop() }
    }

    /** Reports a session-scoped busy state without ever throwing from the capture thread. */
    private fun reportMicrophoneBusy(session: Long) {
        if (closed) return
        main.execute {
            if (closed) return@execute
            val sameSession = synchronized(lock) {
                gate.foreground && gate.session == session
            }
            if (!sameSession) return@execute
            synchronized(lock) { gate.setPermission(AudioPermission.MICROPHONE_BUSY) }
            report()
            reconcile()
        }
    }

    private fun report() {
        if (closed) return
        val (session, permission) = synchronized(lock) { gate.session to gate.permission }
        if (session != null) host.permissionChanged(session, permission)
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
