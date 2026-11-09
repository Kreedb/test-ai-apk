package com.example.minimalapk.ui.screens

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
}
