package com.example.minimalapk.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioWindowTest {
    private fun block(
        samples: ShortArray,
        rate: Int = 16_000,
        channels: Int = 1,
        timestamp: Long = 100,
    ): PcmBlock {
        require(samples.size % channels == 0)
        val buffer = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        buffer.rewind()
        return PcmBlock(
            buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
            rate, channels, PcmFormat.PCM_16_LE, timestamp,
        )
    }

    private fun assertSamples(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals("sample $index", expected[index], actual[index], 0.0001f)
        }
    }

    @Test
    fun exactWindowIsEmittedOnceAndHasTheRequestedLength() {
        val window = AudioSampleWindow(4, 16_000)
        assertNull(window.append(block(shortArrayOf(1, 2, 3))))
        assertEquals(3, window.bufferedSamples)
        val emitted = window.append(block(shortArrayOf(4)))
        assertNotNull(emitted)
        assertSamples(
            floatArrayOf(1f / 32768f, 2f / 32768f, 3f / 32768f, 4f / 32768f),
            emitted!!,
        )
        assertEquals(0, window.bufferedSamples)
        assertNull(window.append(block(shortArrayOf(5))))
    }

    @Test
    fun overflowDropsOldestSamplesAndKeepsTheNewestWindow() {
        val singleBlock = AudioSampleWindow(4, 16_000)
        val emitted = singleBlock.append(block(shortArrayOf(1, 2, 3, 4, 5, 6)))
        assertSamples(
            floatArrayOf(3f / 32768f, 4f / 32768f, 5f / 32768f, 6f / 32768f),
            emitted!!,
        )

        val partial = AudioSampleWindow(4, 16_000)
        assertNull(partial.append(block(shortArrayOf(1, 2, 3))))
        val combined = partial.append(block(shortArrayOf(4, 5)))
        assertSamples(
            floatArrayOf(2f / 32768f, 3f / 32768f, 4f / 32768f, 5f / 32768f),
            combined!!,
        )
    }

    @Test
    fun monoAndStereoPcmAreDownmixedAveraged() {
        val mono = AudioSampleWindow(1, 16_000)
        val monoWindow = mono.append(block(shortArrayOf(16384)))
        assertEquals(0.5f, monoWindow!!.single(), 0.0001f)

        val stereo = AudioSampleWindow(2, 16_000)
        // Frames: (L=0.5, R=0) and (L=0, R=0.5); each frame averages to 0.25.
        val stereoWindow = stereo.append(block(shortArrayOf(16384, 0, 0, 16384), channels = 2))
        assertSamples(floatArrayOf(0.25f, 0.25f), stereoWindow!!)
    }

    @Test
    fun resetDropsPartialWindowAndResamplerState() {
        val window = AudioSampleWindow(4, 16_000)
        assertNull(window.append(block(shortArrayOf(1, 2))))
        window.reset()
        assertEquals(0, window.bufferedSamples)
        assertNull(window.append(block(shortArrayOf(3, 4, 5))))
        assertEquals(3, window.bufferedSamples)
        assertNotNull(window.append(block(shortArrayOf(6))))
        assertEquals(0, window.bufferedSamples)
    }

    @Test
    fun sampleRateConversionProducesTheWindowAtTheModelRate() {
        val upsampled = AudioSampleWindow(8, 16_000)
        val up = upsampled.append(block(shortArrayOf(0, 16384, 32767, 16384), rate = 8_000))
        assertEquals(8, up!!.size)
        assertEquals(0f, up.first(), 0.0001f)

        val downsampled = AudioSampleWindow(4, 16_000)
        val down = downsampled.append(block(ShortArray(8) { 1000 }, rate = 32_000))
        assertEquals(4, down!!.size)
    }

    @Test
    fun resultGateRejectsStaleGenerationsAndOutOfOrderTimestamps() {
        val gate = AudioResultGate()
        val first = gate.reset()
        assertTrue(gate.isCurrent(first))
        assertTrue(gate.accept(first, 100))
        assertFalse(gate.accept(first, 90))

        val second = gate.reset()
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.accept(first, 200))
        assertTrue(gate.accept(second, 50))
        // A late window from the previous generation cannot be accepted after the reset.
        assertFalse(gate.accept(first, 300))
    }
}
