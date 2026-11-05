package com.example.minimalapk.pose

object PoseDecoder {
    fun decode(
        output: FloatArray,
        detectionCount: Int = PoseNet.DETECTION_COUNT,
        valuesPerDetection: Int = PoseNet.VALUES_PER_DETECTION,
        scoreThreshold: Float = PoseNet.SCORE_THRESHOLD,
    ): List<PoseDetection> {
        require(output.size >= detectionCount * valuesPerDetection) {
            "Expected at least ${detectionCount * valuesPerDetection} values, got ${output.size}"
        }
        val detections = ArrayList<PoseDetection>()
        for (index in 0 until detectionCount) {
            val offset = index * valuesPerDetection
            val score = output[offset + 4]
            if (score < scoreThreshold) continue
            val keypoints = ArrayList<PoseKeypoint>(PoseNet.KEYPOINT_COUNT)
            for (keypoint in 0 until PoseNet.KEYPOINT_COUNT) {
                val keypointOffset = offset + 6 + keypoint * 3
                keypoints += PoseKeypoint(
                    x = output[keypointOffset],
                    y = output[keypointOffset + 1],
                    confidence = output[keypointOffset + 2],
                )
            }
            detections += PoseDetection(
                left = output[offset],
                top = output[offset + 1],
                right = output[offset + 2],
                bottom = output[offset + 3],
                score = score,
                keypoints = keypoints,
            )
        }
        return detections.sortedByDescending { it.score }
    }
}
