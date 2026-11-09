package com.example.minimalapk.pose

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

data class BodyMeasurement(
    val cmPerPixel: Float,
    val armSpanCm: Float?,
    val eyeDistanceCm: Float?,
    val shoulderTiltDeg: Float?,
    val hipTiltDeg: Float?,
    val torsoOffsetCm: Float?,
)

object PoseMeasurement {
    // Box height only equals body height when head and both feet are inside the real photo (not
    // the gray letterbox padding), and the box does not touch the photo's top or bottom edge.
    fun isFullBodyVisible(detection: PoseDetection, bounds: PhotoBounds): Boolean {
        fun visible(index: Int) = detection.visiblePoint(index, bounds) != null

        val headSeen = visible(PoseNet.NOSE) ||
            visible(PoseNet.LEFT_EYE) ||
            visible(PoseNet.RIGHT_EYE)
        return headSeen && visible(PoseNet.LEFT_ANKLE) && visible(PoseNet.RIGHT_ANKLE) &&
            detection.top > bounds.top && detection.bottom < bounds.bottom
    }

    fun measure(
        detection: PoseDetection,
        heightCm: Float,
        bounds: PhotoBounds,
    ): BodyMeasurement? {
        if (heightCm !in 50f..250f || detection.height <= 0f) return null
        if (!isFullBodyVisible(detection, bounds)) return null
        val cmPerPixel = heightCm / detection.height

        // Every measured point must be a confident detection inside the real photo; a cropped
        // wrist or a guess in the gray padding would otherwise produce a plausible distance.
        fun point(index: Int): PoseKeypoint? = detection.visiblePoint(index, bounds)

        fun distance(first: Int, second: Int): Float? {
            val a = point(first) ?: return null
            val b = point(second) ?: return null
            return hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
        }

        fun tilt(first: Int, second: Int): Float? {
            val a = point(first) ?: return null
            val b = point(second) ?: return null
            return (atan2(
                abs((b.y - a.y).toDouble()),
                abs((b.x - a.x).toDouble()),
            ) * 180.0 / PI).toFloat()
        }

        fun midpointX(first: Int, second: Int): Float? {
            val a = point(first) ?: return null
            val b = point(second) ?: return null
            return (a.x + b.x) / 2f
        }

        val shoulderMidX = midpointX(PoseNet.LEFT_SHOULDER, PoseNet.RIGHT_SHOULDER)
        val hipMidX = midpointX(PoseNet.LEFT_HIP, PoseNet.RIGHT_HIP)

        return BodyMeasurement(
            cmPerPixel = cmPerPixel,
            armSpanCm = distance(PoseNet.LEFT_WRIST, PoseNet.RIGHT_WRIST)?.times(cmPerPixel),
            eyeDistanceCm = distance(PoseNet.LEFT_EYE, PoseNet.RIGHT_EYE)?.times(cmPerPixel),
            shoulderTiltDeg = tilt(PoseNet.LEFT_SHOULDER, PoseNet.RIGHT_SHOULDER),
            hipTiltDeg = tilt(PoseNet.LEFT_HIP, PoseNet.RIGHT_HIP),
            torsoOffsetCm = if (shoulderMidX != null && hipMidX != null) {
                abs(shoulderMidX - hipMidX) * cmPerPixel
            } else {
                null
            },
        )
    }
}

private fun PoseDetection.visiblePoint(index: Int, bounds: PhotoBounds): PoseKeypoint? =
    keypoints.getOrNull(index)?.takeIf {
        it.confidence >= PoseNet.KEYPOINT_THRESHOLD && it.isInside(bounds)
    }

private fun PoseKeypoint.isInside(bounds: PhotoBounds): Boolean =
    x > bounds.left && x < bounds.right && y > bounds.top && y < bounds.bottom
