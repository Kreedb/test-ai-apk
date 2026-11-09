package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseInputTest {
    private fun assertLayout(
        expectedRotation: Int,
        expectedScale: Float,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedLeft: Int,
        expectedTop: Int,
        layout: PoseInputLayout,
    ) {
        assertEquals(expectedRotation, layout.rotationDegrees)
        assertEquals(expectedScale, layout.scale, 1e-6f)
        assertEquals(expectedWidth, layout.targetWidth)
        assertEquals(expectedHeight, layout.targetHeight)
        assertEquals(expectedLeft, layout.left)
        assertEquals(expectedTop, layout.top)
        assertEquals(expectedLeft + expectedWidth / 2f, layout.centerX, 1e-6f)
        assertEquals(expectedTop + expectedHeight / 2f, layout.centerY, 1e-6f)
    }

    @Test
    fun landscapeFrameIsLetterboxedTopAndBottom() {
        assertLayout(0, 1f, 640, 480, 0, 80, poseInputLayout(640, 480, 0))
    }

    @Test
    fun ninetyDegreesSwapsSourceDimensions() {
        assertLayout(90, 1f, 480, 640, 80, 0, poseInputLayout(640, 480, 90))
    }

    @Test
    fun oneEightyDegreesKeepsSourceDimensions() {
        assertLayout(180, 1f, 640, 480, 0, 80, poseInputLayout(640, 480, 180))
    }

    @Test
    fun twoSeventyDegreesSwapsSourceDimensions() {
        assertLayout(270, 1f, 480, 640, 80, 0, poseInputLayout(640, 480, 270))
    }

    @Test
    fun negativeAndFullTurnRotationsNormalize() {
        assertLayout(270, 1f, 480, 640, 80, 0, poseInputLayout(640, 480, -90))
        assertLayout(0, 1f, 640, 480, 0, 80, poseInputLayout(640, 480, 360))
    }

    @Test
    fun largerFrameIsScaledDownAfterRotation() {
        // 1280x720 rotated 90 is 720x1280, so the long side drives the 0.5 scale.
        assertLayout(90, 0.5f, 360, 640, 140, 0, poseInputLayout(1280, 720, 90))
    }

    @Test
    fun squareFrameNeedsNoLetterbox() {
        assertLayout(0, 1f, 640, 640, 0, 0, poseInputLayout(640, 640, 0))
        assertLayout(90, 1f, 640, 640, 0, 0, poseInputLayout(640, 640, 90))
    }
}
