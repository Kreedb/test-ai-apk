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
    // Box height only equals body height when head and both feet are inside the frame.
    fun isFullBodyVisible(detection: PoseDetection): Boolean {
        fun visible(index: Int): Boolean {
            val keypoint = detection.keypoints.getOrNull(index) ?: return false
            return keypoint.confidence >= PoseNet.KEYPOINT_THRESHOLD &&
                keypoint.x in 0f..PoseNet.INPUT_SIZE.toFloat() &&
                keypoint.y in 0f..PoseNet.INPUT_SIZE.toFloat()
        }

        val headSeen = visible(PoseNet.NOSE) ||
            visible(PoseNet.LEFT_EYE) ||
            visible(PoseNet.RIGHT_EYE)
        return headSeen && visible(PoseNet.LEFT_ANKLE) && visible(PoseNet.RIGHT_ANKLE)
    }

    fun measure(detection: PoseDetection, heightCm: Float): BodyMeasurement? {
        if (heightCm !in 50f..250f || detection.height <= 0f) return null
        if (!isFullBodyVisible(detection)) return null
        val cmPerPixel = heightCm / detection.height

        fun point(index: Int): PoseKeypoint? =
            detection.keypoints.getOrNull(index)
                ?.takeIf { it.confidence >= PoseNet.KEYPOINT_THRESHOLD }

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
