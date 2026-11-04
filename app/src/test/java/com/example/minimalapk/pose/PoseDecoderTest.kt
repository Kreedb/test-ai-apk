package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PoseDecoderTest {

    private fun detection(
        values: FloatArray,
        index: Int,
        box: List<Float>,
        score: Float,
        keypoint: Int,
        keypointValues: List<Float>,
    ) {
        val base = index * OUTPUT_COLUMNS
        box.forEachIndexed { offset, value -> values[base + offset] = value }
        values[base + 4] = score
        values[base + 5] = 0f
        val offset = base + 6 + keypoint * 3
        keypointValues.forEachIndexed { i, value -> values[offset + i] = value }
    }

    @Test
    fun picksHighestScoringPersonAndScalesToPixels() {
        val values = FloatArray(DETECTION_COUNT * OUTPUT_COLUMNS)
        detection(values, 0, listOf(0.1f, 0.2f, 0.5f, 0.8f), 0.5f, 0, listOf(0.3f, 0.4f, 0.001f))
        detection(values, 1, listOf(0.2f, 0.25f, 0.6f, 0.9f), 0.9f, 5, listOf(0.4f, 0.5f, 0.0015f))

        val result = decodeDetections(values, sourceWidth = 200, sourceHeight = 400)

        assertNotNull(result)
        result!!
        assertEquals(0.9f, result.score, 0.001f)
        assertEquals(40f, result.boxLeft, 0.001f)
        assertEquals(100f, result.boxTop, 0.001f)
        assertEquals(120f, result.boxRight, 0.001f)
        assertEquals(360f, result.boxBottom, 0.001f)
        assertEquals(80f, result.keypoints[5].x, 0.001f)
        assertEquals(200f, result.keypoints[5].y, 0.001f)
        assertEquals(0.96f, result.keypoints[5].confidence, 0.001f)
    }

    @Test
    fun ignoresDetectionsBelowThreshold() {
        val values = FloatArray(DETECTION_COUNT * OUTPUT_COLUMNS)
        detection(values, 0, listOf(0.1f, 0.2f, 0.5f, 0.8f), 0.1f, 0, listOf(0.3f, 0.4f, 0.001f))

        assertNull(decodeDetections(values, sourceWidth = 200, sourceHeight = 400))
    }
}
