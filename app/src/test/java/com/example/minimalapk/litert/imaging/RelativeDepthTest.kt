package com.example.minimalapk.litert.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeDepthTest {
    @Test
    fun relativeDepthHasPublishedNearAndFarSpectralColors() {
        val colors = RelativeDepth.pixels(FloatArray(100) { it + 1f })
        assertEquals(0xff9e0142.toInt(), colors.first())
        assertEquals(0xff5e4fa2.toInt(), colors.last())
        assertTrue(colors.toSet().size > 10)
    }

    @Test
    fun changingScaleDoesNotTurnRelativeColorsIntoMetricMeasurements() {
        val values = FloatArray(100) { it + 1f }
        assertArrayEquals(RelativeDepth.pixels(values), RelativeDepth.pixels(values.map { it * 2f }.toFloatArray()))
    }

    @Test
    fun invalidDepthIsBlackAndExcludedFromThePercentileRange() {
        val valid = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val colors = RelativeDepth.pixels(valid + floatArrayOf(Float.NaN, 0f, -1f, Float.POSITIVE_INFINITY))
        assertArrayEquals(RelativeDepth.pixels(valid), colors.copyOfRange(0, 5))
        assertTrue(colors.drop(5).all { it == 0xff000000.toInt() })
        assertThrows(IllegalArgumentException::class.java) { RelativeDepth.pixels(floatArrayOf(0f, Float.NaN)) }
    }

    @Test
    fun constantDepthIsNeutralRatherThanAnInventedDistanceGradient() {
        val colors = RelativeDepth.pixels(FloatArray(10) { 2f })
        assertEquals(1, colors.toSet().size)
        assertTrue(colors[0] != 0xff9e0142.toInt() && colors[0] != 0xff5e4fa2.toInt())
    }
}
