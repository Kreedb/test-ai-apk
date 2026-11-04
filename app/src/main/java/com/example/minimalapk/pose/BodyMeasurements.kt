package com.example.minimalapk.pose

import kotlin.math.hypot

data class Keypoint(
    val x: Float,
    val y: Float,
    val confidence: Float,
)

data class PosePerson(
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val score: Float,
    val keypoints: List<Keypoint>,
)

data class BodyMeasurements(
    val pixelHeight: Float,
    val cmPerPixel: Float,
    val shoulderWidthCm: Float?,
    val leftArmCm: Float?,
    val rightArmCm: Float?,
    val hipWidthCm: Float?,
    val leftLegCm: Float?,
    val rightLegCm: Float?,
)

// COCO-17 order used by YOLO26-pose.
enum class PoseKeypoint(val index: Int) {
    NOSE(0),
    LEFT_EYE(1),
    RIGHT_EYE(2),
    LEFT_EAR(3),
    RIGHT_EAR(4),
    LEFT_SHOULDER(5),
    RIGHT_SHOULDER(6),
    LEFT_ELBOW(7),
    RIGHT_ELBOW(8),
    LEFT_WRIST(9),
    RIGHT_WRIST(10),
    LEFT_HIP(11),
    RIGHT_HIP(12),
    LEFT_KNEE(13),
    RIGHT_KNEE(14),
    LEFT_ANKLE(15),
    RIGHT_ANKLE(16),
}

const val MIN_KEYPOINT_CONFIDENCE = 0.3f

/**
 * Scales pixel distances with the person's bounding-box height, which is assumed
 * to match the height entered by the user.
 */
fun measureBody(
    heightCm: Float,
    person: PosePerson,
    minKeypointConfidence: Float = MIN_KEYPOINT_CONFIDENCE,
): BodyMeasurements? {
    if (heightCm <= 0f) return null
    val pixelHeight = person.boxBottom - person.boxTop
    if (pixelHeight <= 0f) return null
    val cmPerPixel = heightCm / pixelHeight

    fun point(keypoint: PoseKeypoint): Keypoint? =
        person.keypoints.getOrNull(keypoint.index)?.takeIf { it.confidence >= minKeypointConfidence }

    fun segment(from: PoseKeypoint, to: PoseKeypoint): Float? {
        val start = point(from) ?: return null
        val end = point(to) ?: return null
        return hypot((start.x - end.x).toDouble(), (start.y - end.y).toDouble()).toFloat()
    }

    fun chain(vararg keypoints: PoseKeypoint): Float? {
        var total = 0f
        for (index in 0 until keypoints.lastIndex) {
            total += segment(keypoints[index], keypoints[index + 1]) ?: return null
        }
        return total
    }

    return BodyMeasurements(
        pixelHeight = pixelHeight,
        cmPerPixel = cmPerPixel,
        shoulderWidthCm = segment(PoseKeypoint.LEFT_SHOULDER, PoseKeypoint.RIGHT_SHOULDER)
            ?.times(cmPerPixel),
        leftArmCm = chain(
            PoseKeypoint.LEFT_SHOULDER,
            PoseKeypoint.LEFT_ELBOW,
            PoseKeypoint.LEFT_WRIST,
        )?.times(cmPerPixel),
        rightArmCm = chain(
            PoseKeypoint.RIGHT_SHOULDER,
            PoseKeypoint.RIGHT_ELBOW,
            PoseKeypoint.RIGHT_WRIST,
        )?.times(cmPerPixel),
        hipWidthCm = segment(PoseKeypoint.LEFT_HIP, PoseKeypoint.RIGHT_HIP)
            ?.times(cmPerPixel),
        leftLegCm = chain(
            PoseKeypoint.LEFT_HIP,
            PoseKeypoint.LEFT_KNEE,
            PoseKeypoint.LEFT_ANKLE,
        )?.times(cmPerPixel),
        rightLegCm = chain(
            PoseKeypoint.RIGHT_HIP,
            PoseKeypoint.RIGHT_KNEE,
            PoseKeypoint.RIGHT_ANKLE,
        )?.times(cmPerPixel),
    )
}
