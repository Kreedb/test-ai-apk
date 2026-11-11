package com.example.minimalapk.examples

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AudioClassificationWindowTest {
    @Test
    fun wavUsesSameFixedWindowWithStereoMixResamplingAndShortFilePadding() {
        val values = shortArrayOf(16384, 0, 0, 16384, -16384, -16384)
        val bytes = wav(values, 8000, 2)
        val samples = yamnetWavWindow(bytes)
        assertEquals(YAMNET_WINDOW_SAMPLES, samples.size)
        assertArrayEquals(floatArrayOf(.25f, .25f, .25f, -.125f, -.5f, -.5f), samples.copyOf(6), 0f)
        assertTrue(samples.drop(6).all { it == 0f })
    }

    @Test
    fun longFileUsesOnlyOpeningWindowAndRejectsTruncatedPcm() {
        val samples = yamnetWavWindow(wav(ShortArray(20000) { if (it < YAMNET_WINDOW_SAMPLES) 0 else 20000 }, 16000, 1))
        assertTrue(samples.all { it == 0f })
        val incomplete = wav(shortArrayOf(1, 2, 3), 16000, 1).dropLast(1).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { yamnetWavWindow(incomplete) }
    }

    private fun wav(samples: ShortArray, rate: Int, channels: Int): ByteArray = ByteBuffer
        .allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate)
            putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2); samples.forEach { putShort(it) }
        }.array()
}
