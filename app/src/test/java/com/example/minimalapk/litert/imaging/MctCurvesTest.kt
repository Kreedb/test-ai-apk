package com.example.minimalapk.litert.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.abs

class MctCurvesTest {
    @Test
    fun identityCurveReconstructsOriginalRgbAtEveryIntensityWithOnePixelAxes() {
        val grid = 2
        val levels = 8
        val curves = FloatArray(9 * levels * grid * grid)
        for (c in 0..2) for (level in 0 until levels) for (pixel in 0 until grid * grid) {
            curves[((c * 3 + c) * levels + level) * grid * grid + pixel] = level * 2f / (levels - 1) - 1f
        }
        val source = IntArray(256) { i -> 0x80000000.toInt() or (i shl 16) or ((255 - i) shl 8) or (i / 2) }
        for ((w, h) in listOf(256 to 1, 1 to 256, 16 to 16)) {
            val result = applyMctCurves(source, w, h, curves, grid, levels)
            for (i in source.indices) {
                assertEquals(source[i] ushr 24, result[i] ushr 24)
                for (shift in intArrayOf(0, 8, 16)) {
                    val expected = (source[i] ushr shift) and 255
                    val actual = (result[i] ushr shift) and 255
                    org.junit.Assert.assertTrue(abs(expected - actual) <= 1)
                }
            }
        }
    }

    @Test
    fun curvesMixTheInputChannelsInTheCorrectOrder() {
        val curves = FloatArray(9 * 2)
        // R_out <- B_in; G_out <- R_in; B_out <- G_in.
        for ((out, input) in listOf(0 to 2, 1 to 0, 2 to 1)) {
            curves[(out * 3 + input) * 2] = -1f
            curves[(out * 3 + input) * 2 + 1] = 1f
        }
        assertEquals(0xff208040.toInt(), applyMctCurves(intArrayOf(0xff804020.toInt()), 1, 1, curves, 1, 2).single())
    }

    @Test
    fun alignCornersSpatialLookupIncludesBothEdgesAndTheCenter() {
        val curves = FloatArray(9 * 2 * 4)
        for (c in 0..2) for (level in 0..1) {
            val base = (c * 3 * 2 + level) * 4
            curves[base] = -1f
            curves[base + 1] = 1f
            curves[base + 2] = -1f
            curves[base + 3] = 1f
        }
        val result = applyMctCurves(IntArray(3) { 0xff334455.toInt() }, 3, 1, curves, 2, 2)
        assertEquals(0xff000000.toInt(), result[0])
        assertEquals(0xff808080.toInt(), result[1])
        assertEquals(0xffffffff.toInt(), result[2])
    }

    @Test
    fun malformedOrNonFiniteCurvesAreRejectedBeforeRendering() {
        assertThrows(IllegalArgumentException::class.java) {
            applyMctCurves(intArrayOf(-1), 1, 1, FloatArray(3), 1, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            applyMctCurves(intArrayOf(-1), 1, 1, FloatArray(18) { Float.NaN }, 1, 2)
        }
    }
}
