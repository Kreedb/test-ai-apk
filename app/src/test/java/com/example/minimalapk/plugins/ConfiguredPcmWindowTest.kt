package com.example.minimalapk.plugins

import com.example.minimalapk.models.ConfiguredPcmContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ConfiguredPcmWindowTest {
    private fun block(values: FloatArray, rate: Int = 16000, timestamp: Long = 1_000_000L, channels: Int = 1): PcmBlock {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { bytes.putFloat(it) }
        bytes.flip()
        return PcmBlock(bytes.asReadOnlyBuffer(), rate, channels, PcmFormat.PCM_FLOAT_LE, timestamp)
    }

    @Test
    fun emitsExactlyOneLatestFullWindowWithoutPaddingOrReplaying() {
        val window = ConfiguredPcmWindow(ConfiguredPcmContract(4, 16000))
        assertNull(window.append(block(floatArrayOf(.1f, .2f, .3f))))
        assertArrayEquals(floatArrayOf(.2f, .3f, .4f, .5f),
            window.append(block(floatArrayOf(.4f, .5f), timestamp = 2_000_000L))!!, 1e-6f)
        assertNull(window.append(block(floatArrayOf(.6f, .7f, .8f), timestamp = 3_000_000L)))
        assertArrayEquals(floatArrayOf(.6f, .7f, .8f, .9f),
            window.append(block(floatArrayOf(.9f), timestamp = 4_000_000L))!!, 1e-6f)
    }

    @Test
    fun interpolationCarriesTheBoundarySampleAcrossBlocksRatherThanClampingIt() {
        val split = ConfiguredPcmWindow(ConfiguredPcmContract(5, 16000))
        assertNull(split.append(block(floatArrayOf(0f, .5f), rate = 8000)))
        val splitOutput = split.append(block(floatArrayOf(1f), rate = 8000, timestamp = 2_000_000L))!!
        assertArrayEquals(floatArrayOf(0f, .25f, .5f, .75f, 1f), splitOutput, 1e-6f)
        val whole = ConfiguredPcmWindow(ConfiguredPcmContract(5, 16000))
        assertArrayEquals(whole.append(block(floatArrayOf(0f, .5f, 1f), rate = 8000))!!, splitOutput, 0f)
    }

    @Test
    fun downsamplingAndStereoMixingUseConfiguredRateAndDoNotConsumeSharedBuffer() {
        val window = ConfiguredPcmWindow(ConfiguredPcmContract(3, 8000))
        val shared = block(floatArrayOf(0f, .2f, .4f, .6f, .8f, 1f), channels = 2)
        assertNull(window.append(shared)) // Three stereo frames -> output positions 0 and 2.
        assertEquals(0, shared.samples.position())
        assertArrayEquals(floatArrayOf(.1f, .9f, .5f),
            window.append(block(floatArrayOf(.2f, .2f, .5f, .5f), channels = 2, timestamp = 2_000_000L))!!, 1e-6f)
    }

    @Test
    fun discontinuousOrRateChangedCaptureDropsTheOldPartialWindow() {
        val window = ConfiguredPcmWindow(ConfiguredPcmContract(4, 16000))
        assertNull(window.append(block(floatArrayOf(.9f, .9f))))
        assertNull(window.append(block(floatArrayOf(.1f, .2f), timestamp = 500_000_000L)))
        assertArrayEquals(floatArrayOf(.1f, .2f, .3f, .4f),
            window.append(block(floatArrayOf(.3f, .4f), timestamp = 501_000_000L))!!, 1e-6f)
        assertNull(window.append(block(floatArrayOf(.9f, .9f), timestamp = 502_000_000L)))
        assertNull(window.append(block(floatArrayOf(.1f, .2f), rate = 8000, timestamp = 503_000_000L)))
        // After the rate change, the entire new window comes only from 8kHz samples.
        assertArrayEquals(floatArrayOf(.15f, .2f, .25f, .3f),
            window.append(block(floatArrayOf(.3f), rate = 8000, timestamp = 504_000_000L))!!, 1e-6f)
    }

    @Test
    fun resetDropsPartialSamplesAndFractionalResamplerPosition() {
        val window = ConfiguredPcmWindow(ConfiguredPcmContract(4, 16000))
        assertNull(window.append(block(floatArrayOf(.9f, .9f), rate = 8000)))
        window.reset()
        assertNull(window.append(block(floatArrayOf(.1f, .2f, .3f))))
        assertArrayEquals(floatArrayOf(.1f, .2f, .3f, .4f),
            window.append(block(floatArrayOf(.4f), timestamp = 2_000_000L))!!, 1e-6f)
    }

    @Test
    fun badPcmAndOversizedContractsFailExplicitly() {
        val window = ConfiguredPcmWindow(ConfiguredPcmContract(1, 16000))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, 1.1f)) {
            assertThrows(IllegalArgumentException::class.java) { window.append(block(floatArrayOf(invalid))) }
        }
        assertThrows(IllegalArgumentException::class.java) { ConfiguredPcmWindow(ConfiguredPcmContract(240001, 8000)) }
        assertThrows(IllegalArgumentException::class.java) { ConfiguredPcmWindow(ConfiguredPcmContract(1024, 96000)) }
    }
}
