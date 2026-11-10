package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseDecoderTest {
    private val spec = ModelSpec(
        id = "test",
        name = "test",
        source = "asset:test.tflite",
        inputSize = 640,
        anchors = 8400,
    )

    private val fullPhoto = PhotoBounds(left = 0f, top = 0f, right = 640f, bottom = 640f)

    private fun index(anchor: Int, channel: Int) = channel * spec.anchors + anchor

    private fun outputWithDetection(score: Float = 0.9f): FloatArray {
        // The model output is value-major [1, 56, 8400].
        val output = FloatArray(spec.anchors * spec.valuesPerAnchor)
        output[index(0, 0)] = 100f
        output[index(0, 1)] = 100f
        output[index(0, 2)] = 300f
        output[index(0, 3)] = 500f
        output[index(0, 4)] = score
        for (keypoint in 0 until spec.keypoints) {
            val channel = 5 + keypoint * 3
            output[index(0, channel)] = 100f + keypoint
            output[index(0, channel + 1)] = 200f + keypoint
            output[index(0, channel + 2)] = 0.9f
        }
        return output
    }

    private fun pose(vararg points: Pair<Int, PoseKeypoint>): PoseDetection {
        val keypoints = MutableList(spec.keypoints) { PoseKeypoint(0f, 0f, 0f) }
        // Head and feet visible by default so measurement tests exercise the scale path.
        listOf(PoseNet.NOSE, PoseNet.LEFT_ANKLE, PoseNet.RIGHT_ANKLE).forEach {
            keypoints[it] = keypoint(100f, 500f)
        }
        points.forEach { (index, keypoint) -> keypoints[index] = keypoint }
        return PoseDetection(
            left = 0f,
            // Keep the box height at 400 while staying clear of the photo's top edge.
            top = 20f,
            right = 200f,
            bottom = 420f,
            score = 0.9f,
            keypoints = keypoints,
        )
    }

    private fun keypoint(x: Float, y: Float, confidence: Float = 0.9f) =
        PoseKeypoint(x, y, confidence)

    @Test
    fun decodesValueMajorOutputInPlace() {
        val detections = PoseDecoder.decode(outputWithDetection(), spec)

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
        assertTrue(PoseDecoder.decode(outputWithDetection(score = 0.1f), spec).isEmpty())
    }

    @Test
    fun sortsDetectionsByScore() {
        val output = outputWithDetection(score = 0.4f)
        output[index(1, 4)] = 0.9f

        val detections = PoseDecoder.decode(output, spec)

        assertEquals(2, detections.size)
        assertEquals(0.9f, detections[0].score)
        assertEquals(0.4f, detections[1].score)
    }

    @Test
    fun capsDetectionsAtTheModelLimit() {
        val output = FloatArray(spec.anchors * spec.valuesPerAnchor)
        // 400 anchors pass the threshold; only the 300 highest may be decoded.
        for (anchor in 0 until 400) output[index(anchor, 4)] = 1f - anchor * 0.001f

        val detections = PoseDecoder.decode(output, spec)

        assertEquals(300, detections.size)
        assertEquals(1f, detections.first().score, 0f)
        assertEquals(1f - 299 * 0.001f, detections.last().score, 0.0001f)
    }

    @Test
    fun convertsCenterWidthHeightBoxesToCorners() {
        val centered = spec.copy(boxFormat = BoxFormat.CENTER)
        val output = FloatArray(centered.anchors * centered.valuesPerAnchor)
        output[index(0, 0)] = 300f
        output[index(0, 1)] = 200f
        output[index(0, 2)] = 100f
        output[index(0, 3)] = 400f
        output[index(0, 4)] = 0.9f

        val detection = PoseDecoder.decode(output, centered).single()

        assertEquals(250f, detection.left)
        assertEquals(0f, detection.top)
        assertEquals(350f, detection.right)
        assertEquals(400f, detection.bottom)
    }

    @Test
    fun decodesModelsWithAnotherInputSizeAndAnchorCount() {
        val small = spec.copy(inputSize = 320, anchors = 2100)
        fun at(anchor: Int, channel: Int) = channel * small.anchors + anchor
        val output = FloatArray(small.anchors * small.valuesPerAnchor)
        output[at(5, 0)] = 10f
        output[at(5, 1)] = 20f
        output[at(5, 2)] = 110f
        output[at(5, 3)] = 220f
        output[at(5, 4)] = 0.8f
        output[at(5, 5 + 16 * 3)] = 60f

        val detection = PoseDecoder.decode(output, small).single()

        assertEquals(10f, detection.left)
        assertEquals(220f, detection.bottom)
        assertEquals(small.keypoints, detection.keypoints.size)
        assertEquals(60f, detection.keypoints[16].x)
    }

    @Test
    fun decodesAnchorMajorOutput() {
        val anchorMajor = spec.copy(outputLayout = OutputLayout.ANCHOR_MAJOR)
        fun at(anchor: Int, channel: Int) = anchor * anchorMajor.valuesPerAnchor + channel
        val output = FloatArray(anchorMajor.anchors * anchorMajor.valuesPerAnchor)
        output[at(3, 0)] = 10f
        output[at(3, 1)] = 20f
        output[at(3, 2)] = 110f
        output[at(3, 3)] = 220f
        output[at(3, 4)] = 0.8f
        output[at(3, 5 + 16 * 3)] = 60f

        val detection = PoseDecoder.decode(output, anchorMajor).single()

        assertEquals(10f, detection.left)
        assertEquals(220f, detection.bottom)
        assertEquals(60f, detection.keypoints[16].x)
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

        val measurement = PoseMeasurement.measure(detection, heightCm = 170f, bounds = fullPhoto)!!

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

        val measurement = PoseMeasurement.measure(detection, heightCm = 170f, bounds = fullPhoto)!!

        assertNull(measurement.armSpanCm)
        assertEquals(8.5f, measurement.eyeDistanceCm!!, 0.01f)
        assertNull(measurement.shoulderTiltDeg)
    }

    @Test
    fun measurementHidesValuesWhosePointsAreOutsideThePhoto() {
        val photo = PhotoBounds(left = 80f, top = 0f, right = 560f, bottom = 640f)
        val detection = pose(
            PoseNet.LEFT_EYE to keypoint(140f, 50f),
            PoseNet.RIGHT_EYE to keypoint(160f, 50f),
            PoseNet.LEFT_WRIST to keypoint(20f, 150f),
            PoseNet.RIGHT_WRIST to keypoint(250f, 150f),
        )

        val measurement = PoseMeasurement.measure(detection, heightCm = 170f, bounds = photo)!!

        assertNull(measurement.armSpanCm)
        assertEquals(8.5f, measurement.eyeDistanceCm!!, 0.01f)
    }

    @Test
    fun measurementUsesAcuteAngleForFrontFacingKeypointOrder() {
        val detection = pose(
            PoseNet.LEFT_SHOULDER to keypoint(200f, 110f),
            PoseNet.RIGHT_SHOULDER to keypoint(100f, 100f),
        )

        val measurement = PoseMeasurement.measure(detection, heightCm = 170f, bounds = fullPhoto)!!

        assertEquals(5.71f, measurement.shoulderTiltDeg!!, 0.1f)
    }

    @Test
    fun measurementRejectsInvalidHeight() {
        val detection = pose(PoseNet.NOSE to keypoint(100f, 100f))
        assertNull(PoseMeasurement.measure(detection, heightCm = 0f, bounds = fullPhoto))
        assertNull(PoseMeasurement.measure(detection, heightCm = 300f, bounds = fullPhoto))
    }

    @Test
    fun measurementWithholdsScaleWhenFeetAreCropped() {
        val detection = pose(
            PoseNet.NOSE to keypoint(100f, 50f),
            PoseNet.LEFT_ANKLE to keypoint(100f, 640f, confidence = 0.001f),
            PoseNet.RIGHT_ANKLE to keypoint(120f, 640f, confidence = 0.001f),
        )

        assertFalse(PoseMeasurement.isFullBodyVisible(detection, fullPhoto))
        assertNull(PoseMeasurement.measure(detection, heightCm = 170f, bounds = fullPhoto))
    }

    @Test
    fun fullBodyRequiresHeadAndFeetInsideFrame() {
        assertTrue(PoseMeasurement.isFullBodyVisible(pose(), fullPhoto))

        val outside = pose(PoseNet.LEFT_ANKLE to keypoint(100f, 700f))
        assertFalse(PoseMeasurement.isFullBodyVisible(outside, fullPhoto))
    }

    @Test
    fun measurementRejectsHeadInLetterboxPadding() {
        // A 640x480 source letterboxes 80px of padding at the top and bottom.
        val photo = PhotoBounds(left = 0f, top = 80f, right = 640f, bottom = 560f)
        val detection = pose(PoseNet.NOSE to keypoint(100f, 50f))

        assertFalse(PoseMeasurement.isFullBodyVisible(detection, photo))
        assertNull(PoseMeasurement.measure(detection, heightCm = 170f, bounds = photo))
    }

    @Test
    fun measurementRejectsFeetInLetterboxPadding() {
        val photo = PhotoBounds(left = 0f, top = 0f, right = 640f, bottom = 480f)
        val detection = pose(
            PoseNet.LEFT_ANKLE to keypoint(100f, 500f),
            PoseNet.RIGHT_ANKLE to keypoint(120f, 500f),
        )

        assertFalse(PoseMeasurement.isFullBodyVisible(detection, photo))
        assertNull(PoseMeasurement.measure(detection, heightCm = 170f, bounds = photo))
    }

    @Test
    fun measurementRejectsBoxTouchingPhotoEdge() {
        val detection = pose().copy(top = fullPhoto.top)

        assertFalse(PoseMeasurement.isFullBodyVisible(detection, fullPhoto))
        assertNull(PoseMeasurement.measure(detection, heightCm = 170f, bounds = fullPhoto))
    }
}
