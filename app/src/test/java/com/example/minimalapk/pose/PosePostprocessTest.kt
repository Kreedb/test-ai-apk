package com.example.minimalapk.pose

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosePostprocessTest {
    @Test
    fun selectsHighestScoresAndRebuildsDetectionRows() {
        val raw = FloatArray(PosePostprocess.ANCHOR_COUNT * PosePostprocess.VALUES_PER_ANCHOR)
        val topAnchor = 42
        val secondAnchor = 7
        raw[topAnchor * PosePostprocess.VALUES_PER_ANCHOR + 4] = 0.9f
        raw[secondAnchor * PosePostprocess.VALUES_PER_ANCHOR + 4] = 0.8f
        repeat(4) { i ->
            raw[topAnchor * PosePostprocess.VALUES_PER_ANCHOR + i] = (i + 1).toFloat()
            raw[secondAnchor * PosePostprocess.VALUES_PER_ANCHOR + i] = (i + 20).toFloat()
        }
        val keypointStart = topAnchor * PosePostprocess.VALUES_PER_ANCHOR + 5
        raw[keypointStart] = 1.5f
        raw[keypointStart + 1] = 2.5f
        raw[keypointStart + 2] = 0.75f

        val output = PosePostprocess.topDetections(raw, count = 2)

        assertEquals(2 * PoseNet.VALUES_PER_DETECTION, output.size)
        assertEquals(1f, output[0], 0f)
        assertEquals(4f, output[3], 0f)
        assertEquals(0.9f, output[4], 0f)
        assertEquals(0f, output[5], 0f)
        assertEquals(1.5f, output[6], 0f)
        assertEquals(2.5f, output[7], 0f)
        assertEquals(0.75f, output[8], 0f)
        assertEquals(20f, output[PoseNet.VALUES_PER_DETECTION], 0f)
        assertEquals(0.8f, output[PoseNet.VALUES_PER_DETECTION + 4], 0f)
    }

    @Test
    fun fillsAllRequestedRowsWhenFewerAnchorsHaveScores() {
        val raw = FloatArray(PosePostprocess.ANCHOR_COUNT * PosePostprocess.VALUES_PER_ANCHOR)
        raw[4] = 1f

        val output = PosePostprocess.topDetections(raw)

        assertEquals(PoseNet.DETECTION_COUNT * PoseNet.VALUES_PER_DETECTION, output.size)
        assertEquals(1f, output[4], 0f)
        assertEquals(0f, output[PoseNet.VALUES_PER_DETECTION + 4], 0f)
        assertArrayEquals(FloatArray(PoseNet.KEYPOINT_COUNT * 3), output.copyOfRange(6, 6 + 51), 0f)
        assertTrue(output.max() > 0f)
    }
}
