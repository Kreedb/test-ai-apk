package com.example.minimalapk.pose

/**
 * The ncnn model ends at the raw pose head: 8400 anchors of 56 values
 * (box x1/y1/x2/y2, score, 17 keypoints x/y/confidence). Select the 300 best anchors and
 * insert the class index expected by [PoseDecoder].
 */
internal object PosePostprocess {
    const val ANCHOR_COUNT = 8400
    const val VALUES_PER_ANCHOR = 56

    fun topDetections(
        raw: FloatArray,
        count: Int = PoseNet.DETECTION_COUNT,
    ): FloatArray {
        require(raw.size >= ANCHOR_COUNT * VALUES_PER_ANCHOR) {
            "Expected at least ${ANCHOR_COUNT * VALUES_PER_ANCHOR} values, got ${raw.size}"
        }
        require(count <= ANCHOR_COUNT) { "Cannot select $count of $ANCHOR_COUNT anchors" }

        val selected = (0 until ANCHOR_COUNT)
            .sortedByDescending { raw[it * VALUES_PER_ANCHOR + 4] }
            .take(count)

        val output = FloatArray(count * PoseNet.VALUES_PER_DETECTION)
        for ((rank, anchor) in selected.withIndex()) {
            val source = anchor * VALUES_PER_ANCHOR
            val target = rank * PoseNet.VALUES_PER_DETECTION
            raw.copyInto(output, target, source, source + 5)
            // COCO pose is a single-class model, so the class index is always 0.
            output[target + 5] = 0f
            raw.copyInto(
                output,
                target + 6,
                source + 5,
                source + 5 + PoseNet.KEYPOINT_COUNT * 3,
            )
        }
        return output
    }
}
