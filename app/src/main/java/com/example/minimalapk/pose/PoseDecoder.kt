package com.example.minimalapk.pose

/**
 * Decodes the value-major `[1, 56, 8400]` pose head directly. Channel 4 is the anchor score and
 * channels 5..55 are the 17 keypoints' x/y/confidence. Selecting and sorting anchors here avoids
 * the old intermediate `300x57` table and its duplicate filter and sort.
 */
object PoseDecoder {
    private const val SCORE_CHANNEL = 4
    private const val KEYPOINT_CHANNEL = 5

    fun decode(raw: FloatArray): List<PoseDetection> {
        require(raw.size == PoseNet.ANCHOR_COUNT * PoseNet.VALUES_PER_ANCHOR) {
            "Unexpected model output size: ${raw.size}"
        }
        // A primitive loop avoids boxing every anchor index the way filter/sortedByDescending do.
        val selected = ArrayList<Int>()
        for (anchor in 0 until PoseNet.ANCHOR_COUNT) {
            if (raw[SCORE_CHANNEL * PoseNet.ANCHOR_COUNT + anchor] >= PoseNet.SCORE_THRESHOLD) {
                selected += anchor
            }
        }
        selected.sortByDescending { raw[SCORE_CHANNEL * PoseNet.ANCHOR_COUNT + it] }
        return selected.take(PoseNet.DETECTION_COUNT).map { anchor ->
            val keypoints = ArrayList<PoseKeypoint>(PoseNet.KEYPOINT_COUNT)
            for (keypoint in 0 until PoseNet.KEYPOINT_COUNT) {
                val channel = KEYPOINT_CHANNEL + keypoint * 3
                keypoints += PoseKeypoint(
                    x = raw[channel * PoseNet.ANCHOR_COUNT + anchor],
                    y = raw[(channel + 1) * PoseNet.ANCHOR_COUNT + anchor],
                    confidence = raw[(channel + 2) * PoseNet.ANCHOR_COUNT + anchor],
                )
            }
            PoseDetection(
                left = raw[anchor],
                top = raw[PoseNet.ANCHOR_COUNT + anchor],
                right = raw[2 * PoseNet.ANCHOR_COUNT + anchor],
                bottom = raw[3 * PoseNet.ANCHOR_COUNT + anchor],
                score = raw[SCORE_CHANNEL * PoseNet.ANCHOR_COUNT + anchor],
                keypoints = keypoints,
            )
        }
    }
}
