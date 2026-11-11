package com.example.minimalapk.plugins

import com.example.minimalapk.models.ConfiguredPcmContract
import kotlin.math.floor

/** One bounded, latest full waveform window; fractional resampling continues across PCM blocks. */
internal class ConfiguredPcmWindow(private val contract: ConfiguredPcmContract) {
    init {
        require(contract.sampleRate in 8_000..48_000)
        require(contract.samples in 1..minOf(480_000, contract.sampleRate * 30))
    }

    private val ring = FloatArray(contract.samples)
    private var written = 0
    private var available = 0
    private var sourceRate = 0
    private var sourceFrames = 0L
    private var nextPosition = 0.0
    private var previous = 0f
    private var lastTimestamp = Long.MIN_VALUE
    private var previousDuration = 0L

    fun append(block: PcmBlock): FloatArray? {
        require(block.sampleRate in 8_000..192_000) { "不支持的 PCM 采样率 ${block.sampleRate}" }
        val mono = pcmToMono(block)
        require(mono.all { it.isFinite() && it in -1f..1f }) { "PCM 必须是有限的 [-1,1] 单位振幅" }
        if (mono.isEmpty()) return null
        val duration = mono.size * 1_000_000_000L / block.sampleRate
        // Timestamp is the block end. Never join samples across a stopped/discontinuous capture.
        if (sourceRate != block.sampleRate || (lastTimestamp != Long.MIN_VALUE &&
                (block.timestampNanos <= lastTimestamp ||
                    block.timestampNanos - lastTimestamp > maxOf(duration, previousDuration) + 250_000_000L))) reset()
        sourceRate = block.sampleRate
        lastTimestamp = block.timestampNanos
        previousDuration = duration
        val start = sourceFrames
        val end = start + mono.size - 1
        val step = sourceRate.toDouble() / contract.sampleRate
        // Defer fractional positions needing the next block rather than duplicating its last sample.
        while (nextPosition <= end) {
            val left = floor(nextPosition).toLong()
            val fraction = nextPosition - left
            if (fraction > 1e-9 && left == end) break
            val index = (left - start).toInt()
            val a = if (index < 0) previous else mono[index]
            val b = if (fraction <= 1e-9) a else mono[index + 1]
            ring[written] = (a * (1 - fraction) + b * fraction).toFloat()
            written = (written + 1) % ring.size
            available = minOf(available + 1, ring.size)
            nextPosition += step
        }
        previous = mono.last()
        sourceFrames += mono.size
        if (available < ring.size) return null
        available = 0
        return FloatArray(ring.size) { ring[(written + it) % ring.size] }
    }

    fun reset() {
        written = 0
        available = 0
        sourceRate = 0
        sourceFrames = 0L
        nextPosition = 0.0
        previous = 0f
        lastTimestamp = Long.MIN_VALUE
        previousDuration = 0L
    }
}
