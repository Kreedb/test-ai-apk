package com.example.minimalapk.plugins

import java.nio.ByteBuffer
import kotlin.math.floor

/** Linear resampler with one fractional position carried across blocks; identity when rates match. */
internal class LinearResampler(private val targetRate: Int) {
    private var position = 0.0

    fun convert(input: FloatArray, sourceRate: Int): FloatArray {
        require(sourceRate in 8_000..192_000) { "不支持的采样率 $sourceRate" }
        if (input.isEmpty()) return input
        if (sourceRate == targetRate) return input
        val step = sourceRate.toDouble() / targetRate
        val output = ArrayList<Float>((input.size * targetRate / sourceRate) + 2)
        var index = position
        while (index < input.size) {
            val left = floor(index).toInt()
            val fraction = (index - left)
            val a = input[left]
            val b = input.getOrElse(left + 1) { a }
            output += (a * (1 - fraction) + b * fraction).toFloat()
            index += step
        }
        position = index - input.size
        return output.toFloatArray()
    }

    fun reset() {
        position = 0.0
    }
}

/** Mono float samples from one PCM block; stereo channels are averaged, never concatenated. */
internal fun pcmToMono(block: PcmBlock): FloatArray {
    val buffer = block.samples
    val frameBytes = block.channels * block.format.bytesPerSample
    val frames = buffer.remaining() / frameBytes
    val output = FloatArray(frames)
    for (frame in 0 until frames) {
        var sum = 0f
        repeat(block.channels) {
            sum += when (block.format) {
                PcmFormat.PCM_16_LE -> buffer.short / 32768f
                PcmFormat.PCM_FLOAT_LE -> buffer.float
            }
        }
        output[frame] = sum / block.channels
    }
    return output
}

/**
 * Bounded mono window at the model's sample rate. A full window is emitted exactly once and the
 * buffer is cleared; when more samples arrive than fit, the oldest are dropped, so a slow consumer
 * cannot grow memory or replay stale audio.
 */
internal class AudioSampleWindow(
    val windowSamples: Int,
    val sampleRate: Int = AUDIO_SAMPLE_RATE,
) {
    init {
        require(windowSamples in 1..MAX_WINDOW_SAMPLES) { "音频窗口长度无效：$windowSamples" }
        require(sampleRate in 8_000..48_000) { "不支持的采样率 $sampleRate" }
    }

    private val resampler = LinearResampler(sampleRate)
    private val pending = FloatArray(windowSamples)
    private var size = 0
    var filledTimestampNanos: Long = 0L
        private set

    val bufferedSamples: Int get() = size

    fun append(block: PcmBlock): FloatArray? =
        appendMono(pcmToMono(block), block.sampleRate, block.timestampNanos)

    fun appendMono(samples: FloatArray, sourceRate: Int, timestampNanos: Long): FloatArray? {
        val converted = resampler.convert(samples, sourceRate)
        if (converted.isEmpty()) return null
        // A block larger than the window can only contribute its newest window.
        if (converted.size >= windowSamples) {
            converted.copyInto(pending, 0, converted.size - windowSamples, converted.size)
            size = windowSamples
        } else {
            val overflow = size + converted.size - windowSamples
            if (overflow > 0) {
                pending.copyInto(pending, 0, overflow, size)
                size -= overflow
            }
            converted.copyInto(pending, size)
            size += converted.size
        }
        if (size < windowSamples) return null
        filledTimestampNanos = timestampNanos
        val window = pending.copyOf()
        size = 0
        return window
    }

    fun reset() {
        size = 0
        filledTimestampNanos = 0L
        resampler.reset()
    }

    companion object {
        /** 30 s at 16 kHz; the YAMNet window is under 1 s. */
        const val MAX_WINDOW_SAMPLES = 480_000
    }
}

/**
 * Rejects results from a previous window/session generation and results older than the newest
 * accepted window. Reset on stop and every configuration change, matching the host token.
 */
internal class AudioResultGate {
    private var generation = 0L
    private var lastAccepted = Long.MIN_VALUE

    @Synchronized
    fun reset(): Long {
        generation++
        lastAccepted = Long.MIN_VALUE
        return generation
    }

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun isCurrent(value: Long): Boolean = value == generation

    @Synchronized
    fun accept(value: Long, timestampNanos: Long): Boolean {
        if (value != generation || timestampNanos < lastAccepted) return false
        lastAccepted = timestampNanos
        return true
    }
}
