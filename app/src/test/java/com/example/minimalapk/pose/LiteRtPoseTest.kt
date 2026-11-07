package com.example.minimalapk.pose

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test
    fun transposesValueMajorOutputIntoAnchorMajorRows() {
        val anchors = PosePostprocess.ANCHOR_COUNT
        val values = PosePostprocess.VALUES_PER_ANCHOR
        // onnx2tf emits [1, 56, 8400]: each of the 56 channels holds all 8400 anchors back to back.
        val raw = FloatArray(anchors * values) { it.toFloat() }

        val output = transposeOutput(raw)

        assertEquals(anchors * values, output.size)
        // Output is [1, 8400, 56]: anchor 0 takes the first value of every channel.
        assertArrayEquals(
            floatArrayOf(raw[0], raw[anchors], raw[2 * anchors], raw[values * anchors - 1]),
            floatArrayOf(output[0], output[1], output[2], output[anchors * values - 1]),
            0f,
        )
    }
}
