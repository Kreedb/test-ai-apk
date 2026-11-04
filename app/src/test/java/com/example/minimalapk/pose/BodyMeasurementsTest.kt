package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BodyMeasurementsTest {

    private fun person(
        keypointOverrides: Map<Int, Keypoint> = emptyMap(),
        boxTop: Float = 0f,
        boxBottom: Float = 1000f,
    ): PosePerson {
        val keypoints = MutableList(KEYPOINT_COUNT) { Keypoint(0f, 0f, 0f) }
        keypointOverrides.forEach { (index, keypoint) -> keypoints[index] = keypoint }
        return PosePerson(
            boxLeft = 0f,
            boxTop = boxTop,
            boxRight = 500f,
            boxBottom = boxBottom,
            score = 0.9f,
            keypoints = keypoints,
        )
    }

    private val fullBody = mapOf(
        5 to Keypoint(300f, 200f, 0.9f),
        6 to Keypoint(400f, 200f, 0.9f),
        7 to Keypoint(300f, 300f, 0.9f),
        8 to Keypoint(400f, 300f, 0.9f),
        9 to Keypoint(300f, 400f, 0.9f),
        10 to Keypoint(400f, 400f, 0.9f),
        11 to Keypoint(320f, 500f, 0.9f),
        12 to Keypoint(380f, 500f, 0.9f),
        13 to Keypoint(320f, 700f, 0.9f),
        14 to Keypoint(380f, 700f, 0.9f),
        15 to Keypoint(320f, 900f, 0.9f),
        16 to Keypoint(380f, 900f, 0.9f),
    )

    @Test
    fun measuresScaledBodySegments() {
        val result = measureBody(heightCm = 200f, person = person(fullBody))
        assertNotNull(result)
        result!!

        assertEquals(1000f, result.pixelHeight, 0.001f)
        assertEquals(0.2f, result.cmPerPixel, 0.001f)
        assertEquals(20f, result.shoulderWidthCm!!, 0.001f)
        assertEquals(40f, result.leftArmCm!!, 0.001f)
        assertEquals(40f, result.rightArmCm!!, 0.001f)
        assertEquals(12f, result.hipWidthCm!!, 0.001f)
        assertEquals(80f, result.leftLegCm!!, 0.001f)
        assertEquals(80f, result.rightLegCm!!, 0.001f)
    }

    @Test
    fun ignoresLowConfidenceKeypoints() {
        val overrides = fullBody + (9 to Keypoint(300f, 400f, 0.1f))
        val result = measureBody(heightCm = 200f, person = person(overrides))
        assertNotNull(result)
        result!!

        assertNull(result.leftArmCm)
        assertEquals(40f, result.rightArmCm!!, 0.001f)
        assertEquals(20f, result.shoulderWidthCm!!, 0.001f)
    }

    @Test
    fun rejectsInvalidHeightAndBox() {
        assertNull(measureBody(heightCm = 0f, person = person(fullBody)))
        assertNull(measureBody(heightCm = 200f, person = person(fullBody, boxTop = 500f, boxBottom = 500f)))
    }
}
