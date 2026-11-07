package com.example.minimalapk.pose

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LiteRtPoseTest {
    @Test
    fun convertsArgbPixelsToInterleavedRgbFloats() {
        val pixels = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt())
        val output = FloatArray(pixels.size * 3)

        argbToRgbFloat(pixels, output)

        assertArrayEquals(
            floatArrayOf(
                0x11 / 255f, 0x22 / 255f, 0x33 / 255f,
                0x44 / 255f, 0x55 / 255f, 0x66 / 255f,
            ),
            output,
            1e-6f,
        )
    }
}
