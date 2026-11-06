package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseDecoderTest {
    private fun outputWithDetection(score: Float = 0.9f): FloatArray {
        val output = FloatArray(PoseNet.DETECTION_COUNT * PoseNet.VALUES_PER_DETECTION)
        output[0] = 100f
        output[1] = 100f
        output[2] = 300f
        output[3] = 500f
        output[4] = score
        output[5] = 0f
        for (keypoint in 0 until PoseNet.KEYPOINT_COUNT) {
            val offset = 6 + keypoint * 3
            output[offset] = 100f + keypoint
            output[offset + 1] = 200f + keypoint
            output[offset + 2] = 0.9f
        }
        return output
    }

    private fun pose(vararg points: Pair<Int, PoseKeypoint>): PoseDetection {
        val keypoints = MutableList(PoseNet.KEYPOINT_COUNT) { PoseKeypoint(0f, 0f, 0f) }
        // Head and feet visible by default so measurement tests exercise the scale path.
        listOf(PoseNet.NOSE, PoseNet.LEFT_ANKLE, PoseNet.RIGHT_ANKLE).forEach {
            keypoints[it] = keypoint(100f, 500f)
        }
        points.forEach { (index, keypoint) -> keypoints[index] = keypoint }
        return PoseDetection(
            left = 0f,
            top = 0f,
            right = 200f,
            bottom = 400f,
            score = 0.9f,
            keypoints = keypoints,
        )
    }

    private fun keypoint(x: Float, y: Float, confidence: Float = 0.9f) =
        PoseKeypoint(x, y, confidence)

    @Test
    fun decodesEndToEndOutputLayout() {
        val detections = PoseDecoder.decode(outputWithDetection())

        assertEquals(1, detections.size)
        val detection = detections.first()
        assertEquals(100f, detection.left)
        assertEquals(100f, detection.top)
        assertEquals(300f, detection.right)
        assertEquals(500f, detection.bottom)
        assertEquals(400f, detection.height)
        assertEquals(17, detection.keypoints.size)
        assertEquals(100f, detection.keypoints[PoseNet.NOSE].x)
        assertEquals(101f, detection.keypoints[PoseNet.LEFT_EYE].x)
    }

    @Test
    fun filtersLowConfidenceDetections() {
        assertTrue(PoseDecoder.decode(outputWithDetection(score = 0.1f)).isEmpty())
    }

    @Test
    fun sortsDetectionsByScore() {
        val output = outputWithDetection(score = 0.4f)
        output[PoseNet.VALUES_PER_DETECTION + 4] = 0.9f

        val detections = PoseDecoder.decode(output)

        assertEquals(2, detections.size)
        assertEquals(0.9f, detections[0].score)
        assertEquals(0.4f, detections[1].score)
    }

    @Test
    fun measurementUsesUserHeightForScale() {
        val detection = pose(
            PoseNet.LEFT_EYE to keypoint(160f, 50f),
            PoseNet.RIGHT_EYE to keypoint(140f, 50f),
            PoseNet.LEFT_SHOULDER to keypoint(200f, 100f),
            PoseNet.RIGHT_SHOULDER to keypoint(100f, 100f),
            PoseNet.LEFT_WRIST to keypoint(250f, 150f),
            PoseNet.RIGHT_WRIST to keypoint(50f, 150f),
            PoseNet.LEFT_HIP to keypoint(200f, 250f),
            PoseNet.RIGHT_HIP to keypoint(100f, 250f),
        )

        val measurement = PoseMeasurement.measure(detection, heightCm = 170f)!!

        assertEquals(0.425f, measurement.cmPerPixel, 0.001f)
        assertEquals(85f, measurement.armSpanCm!!, 0.01f)
        assertEquals(8.5f, measurement.eyeDistanceCm!!, 0.01f)
        assertEquals(0f, measurement.shoulderTiltDeg!!, 0.01f)
        assertEquals(0f, measurement.hipTiltDeg!!, 0.01f)
        assertEquals(0f, measurement.torsoOffsetCm!!, 0.01f)
    }

    @Test
    fun measurementSkipsLowConfidenceKeypoints() {
        val detection = pose(
            PoseNet.LEFT_EYE to keypoint(140f, 50f),
            PoseNet.RIGHT_EYE to keypoint(160f, 50f),
            PoseNet.LEFT_WRIST to keypoint(50f, 150f, confidence = 0.1f),
            PoseNet.RIGHT_WRIST to keypoint(250f, 150f),
        )

        val measurement = PoseMeasurement.measure(detection, heightCm = 170f)!!

        assertNull(measurement.armSpanCm)
        assertEquals(8.5f, measurement.eyeDistanceCm!!, 0.01f)
        assertNull(measurement.shoulderTiltDeg)
    }

    @Test
    fun measurementUsesAcuteAngleForFrontFacingKeypointOrder() {
        val detection = pose(
            PoseNet.LEFT_SHOULDER to keypoint(200f, 110f),
            PoseNet.RIGHT_SHOULDER to keypoint(100f, 100f),
        )

        val measurement = PoseMeasurement.measure(detection, heightCm = 170f)!!

        assertEquals(5.71f, measurement.shoulderTiltDeg!!, 0.1f)
    }

    @Test
    fun measurementRejectsInvalidHeight() {
        val detection = pose(PoseNet.NOSE to keypoint(100f, 100f))
        assertNull(PoseMeasurement.measure(detection, heightCm = 0f))
        assertNull(PoseMeasurement.measure(detection, heightCm = 300f))
    }

    @Test
    fun measurementWithholdsScaleWhenFeetAreCropped() {
        val detection = pose(
            PoseNet.NOSE to keypoint(100f, 50f),
            PoseNet.LEFT_ANKLE to keypoint(100f, 640f, confidence = 0.001f),
            PoseNet.RIGHT_ANKLE to keypoint(120f, 640f, confidence = 0.001f),
        )

        assertFalse(PoseMeasurement.isFullBodyVisible(detection))
        assertNull(PoseMeasurement.measure(detection, heightCm = 170f))
    }

    @Test
    fun fullBodyRequiresHeadAndFeetInsideFrame() {
        assertTrue(PoseMeasurement.isFullBodyVisible(pose()))

        val outside = pose(PoseNet.LEFT_ANKLE to keypoint(100f, 700f))
        assertFalse(PoseMeasurement.isFullBodyVisible(outside))
    }
}
