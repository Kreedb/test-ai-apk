package com.example.minimalapk.ui.screens

import com.example.minimalapk.plugins.cameraFrameRate
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFrameRateTest {
    @Test
    fun slowStreamKeepsItsLastTwoSamples() {
        val timestamps = ArrayDeque<Long>()
        timestamps.addLast(0L)
        timestamps.addLast(3_000_000_000L)

        assertEquals(1f / 3f, frameRate(timestamps, 3_000_000_000L), 0.0001f)
    }

    @Test
    fun trimsSamplesOutsideTheWindow() {
        val timestamps = ArrayDeque<Long>()
        (0L..5L).forEach { timestamps.addLast(it * 1_000_000_000L) }

        // Only samples from 3s..5s survive the two-second window.
        assertEquals(1f, frameRate(timestamps, 5_000_000_000L), 0.0001f)
    }

    @Test
    fun configurableWindowCanKeepSlowFrameSamples() {
        val timestamps = ArrayDeque<Long>()
        timestamps.addLast(0L)
        timestamps.addLast(3_000_000_000L)
        timestamps.addLast(6_000_000_000L)

        // A 10s window keeps all three samples, so the stream is still 1 frame every 3s.
        assertEquals(
            2f / 6f,
            cameraFrameRate(timestamps, 6_000_000_000L, 10_000_000_000L),
            0.0001f,
        )
        // The legacy 2s window deliberately keeps the last two samples for slow streams.
        assertEquals(1f / 3f, frameRate(timestamps, 6_000_000_000L), 0.0001f)
    }
}
