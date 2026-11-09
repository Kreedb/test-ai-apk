package com.example.minimalapk.pose

/**
 * The TFLite model ends at the raw pose head: 8400 anchors of 56 values
 * (box x1/y1/x2/y2, score, 17 keypoints x/y/confidence). The tensor is value-major
 * `[1, 56, 8400]`, so every value channel holds all anchors back to back. Select the 300 best
 * anchors and insert the class index expected by [PoseDecoder].
 */
internal object PosePostprocess {
    const val ANCHOR_COUNT = 8400
    const val VALUES_PER_ANCHOR = 56

    private const val SCORE_INDEX = 4

    fun topDetections(
        raw: FloatArray,
        count: Int = PoseNet.DETECTION_COUNT,
    ): FloatArray {
        require(raw.size == ANCHOR_COUNT * VALUES_PER_ANCHOR) {
            "Unexpected model output size: ${raw.size}"
        }
        require(count in 0..ANCHOR_COUNT) { "Cannot select $count of $ANCHOR_COUNT anchors" }

        // Drop anchors the decoder would discard anyway before paying for a full sort.
        val selected = (0 until ANCHOR_COUNT)
            .filter { raw[SCORE_INDEX * ANCHOR_COUNT + it] >= PoseNet.SCORE_THRESHOLD }
            .sortedByDescending { raw[SCORE_INDEX * ANCHOR_COUNT + it] }
            .take(count)

        val output = FloatArray(count * PoseNet.VALUES_PER_DETECTION)
        for ((rank, anchor) in selected.withIndex()) {
            val target = rank * PoseNet.VALUES_PER_DETECTION
            // Box values 0..4 keep their positions; value 4 is the score.
            for (value in 0..4) output[target + value] = raw[value * ANCHOR_COUNT + anchor]
            // COCO pose is a single-class model, so the class index is always 0.
            output[target + 5] = 0f
            // Values 5..55 are the 17 keypoints x/y/confidence, shifted one slot by the class.
            for (value in 5 until VALUES_PER_ANCHOR) {
                output[target + 1 + value] = raw[value * ANCHOR_COUNT + anchor]
            }
        }
        return output
    }
}
