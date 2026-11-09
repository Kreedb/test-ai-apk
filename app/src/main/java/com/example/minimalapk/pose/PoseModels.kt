package com.example.minimalapk.pose

data class PoseKeypoint(
    val x: Float,
    val y: Float,
    val confidence: Float,
)

/** The real photo's rectangle inside the padded, letterboxed 640×640 model input. */
data class PhotoBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class PoseDetection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val keypoints: List<PoseKeypoint>,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

object PoseNet {
    const val INPUT_SIZE = 640
    const val ANCHOR_COUNT = 8400
    const val VALUES_PER_ANCHOR = 56
    const val DETECTION_COUNT = 300
    const val KEYPOINT_COUNT = 17
    const val SCORE_THRESHOLD = 0.35f
    const val KEYPOINT_THRESHOLD = 0.5f
    const val LETTERBOX_COLOR = 114

    const val NOSE = 0
    const val LEFT_EYE = 1
    const val RIGHT_EYE = 2
    const val LEFT_SHOULDER = 5
    const val RIGHT_SHOULDER = 6
    const val LEFT_ELBOW = 7
    const val RIGHT_ELBOW = 8
    const val LEFT_WRIST = 9
    const val RIGHT_WRIST = 10
    const val LEFT_HIP = 11
    const val RIGHT_HIP = 12
    const val LEFT_KNEE = 13
    const val RIGHT_KNEE = 14
    const val LEFT_ANKLE = 15
    const val RIGHT_ANKLE = 16

    val skeletonEdges = listOf(
        LEFT_SHOULDER to RIGHT_SHOULDER,
        LEFT_SHOULDER to LEFT_ELBOW,
        LEFT_ELBOW to LEFT_WRIST,
        RIGHT_SHOULDER to RIGHT_ELBOW,
        RIGHT_ELBOW to RIGHT_WRIST,
        LEFT_SHOULDER to LEFT_HIP,
        RIGHT_SHOULDER to RIGHT_HIP,
        LEFT_HIP to RIGHT_HIP,
        LEFT_HIP to LEFT_KNEE,
        LEFT_KNEE to LEFT_ANKLE,
        RIGHT_HIP to RIGHT_KNEE,
        RIGHT_KNEE to RIGHT_ANKLE,
        NOSE to LEFT_EYE,
        NOSE to RIGHT_EYE,
        LEFT_EYE to LEFT_SHOULDER,
        RIGHT_EYE to RIGHT_SHOULDER,
    )
}
