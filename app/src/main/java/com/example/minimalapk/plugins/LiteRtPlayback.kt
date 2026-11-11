package com.example.minimalapk.plugins

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CancellationException

/** One playback focus owner for system TTS and neural/processed speech. Capture observes this. */
internal object PluginAudioFocus {
    private val lock = Any()
    private var current: Lease? = null
    private val mutablePlaying = MutableStateFlow(false)
    val playing = mutablePlaying.asStateFlow()

    @Suppress("DEPRECATION")
    fun acquire(context: Context, stop: () -> Unit): Lease {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val lease = Lease(manager, stop)
        val (previous, granted) = synchronized(lock) {
            val old = current
            current = lease
            mutablePlaying.value = true
            old to manager.requestAudioFocus(lease.listener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        previous?.revoke()
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            lease.close()
            error("无法取得音频焦点，请停止其他播放后重试")
        }
        if (synchronized(lock) { current !== lease }) {
            lease.close()
            throw CancellationException("音频焦点请求已失效")
        }
        return lease
    }

    internal class Lease(private val manager: AudioManager, private val stop: () -> Unit) : AutoCloseable {
        @Volatile private var closed = false
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change < 0) revoke()
        }
        fun revoke() {
            if (closed) return
            try { stop() } finally { close() }
        }
        @Suppress("DEPRECATION")
        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                if (current === this) {
                    current = null
                    mutablePlaying.value = false
                }
            }
            manager.abandonAudioFocus(listener)
        }
    }
}

/** Streaming AudioTrack with bounded owned PCM. stop() unblocks writes; release stays on its worker. */
internal class LiteRtPlayback(private val application: Context) : AutoCloseable {
    private data class Request(
        val generation: Long,
        val samples: FloatArray,
        val rate: Int,
        val valid: () -> Boolean,
        val finished: (String) -> Unit,
    )
    private val lock = Any()
    private val gate = LiteRtRequestGate()
    private var track: AudioTrack? = null
    private var focus: PluginAudioFocus.Lease? = null
    private val worker = BoundedFrameWorker<Request>("LiteRtPlayback", failed = { request, error ->
        if (gate.accepts(request.generation) && request.valid()) request.finished(error.message ?: "播放失败")
    }, consume = ::playOnWorker)

    fun play(samples: FloatArray, rate: Int, valid: () -> Boolean, finished: (String) -> Unit) {
        require(rate in 8_000..48_000 && samples.isNotEmpty() && samples.size <= rate * 30) {
            "音频必须是有限的 30 秒以内单声道波形"
        }
        stop()
        // Model results are independently owned immutable arrays; retaining one is sufficient.
        worker.offer(Request(gate.current(), samples, rate, valid, finished))
    }

    private fun playOnWorker(request: Request) {
        fun valid() = gate.accepts(request.generation) && request.valid()
        if (!valid()) return
        require(request.samples.all { it.isFinite() }) { "音频包含非有限样本" }
        val lease = PluginAudioFocus.acquire(application) { stop(request.generation) }
        var audio: AudioTrack? = null
        try {
            if (!valid()) return
            val bufferBytes = maxOf(4096, AudioTrack.getMinBufferSize(request.rate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
            audio = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(request.rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferBytes).build()
            val active = checkNotNull(audio)
            if (!valid()) return
            synchronized(lock) {
                // Do not call the external validity callback under this lock: cancellation may
                // hold the request-owner lock while stopping playback.
                if (!gate.accepts(request.generation)) return
                track = active
                focus = lease
                active.play()
            }
            val pcm = ShortArray(1024)
            var offset = 0
            while (offset < request.samples.size && valid()) {
                val count = minOf(pcm.size, request.samples.size - offset)
                repeat(count) { pcm[it] = (request.samples[offset + it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                var written = 0
                while (written < count && valid()) {
                    val n = active.write(pcm, written, count - written, AudioTrack.WRITE_BLOCKING)
                    check(n > 0) { "音频写入失败（$n）" }
                    written += n
                }
                offset += written
            }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (valid() && active.playbackHeadPosition.toLong() < offset && System.nanoTime() < deadline) Thread.sleep(20)
            if (valid()) request.finished("播放完成")
        } finally {
            synchronized(lock) {
                if (track === audio) track = null
                if (focus === lease) focus = null
            }
            runCatching { audio?.pause() }
            runCatching { audio?.flush() }
            runCatching { audio?.release() }
            lease.close()
        }
    }

    fun stop() = stop(null)

    private fun stop(expectedGeneration: Long?) {
        val lease = synchronized(lock) {
            if (expectedGeneration != null && !gate.accepts(expectedGeneration)) return
            gate.next()
            worker.clear()
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.stop() }
            focus.also { focus = null }
        }
        lease?.close()
    }

    override fun close() { stop(); worker.close() }
}
