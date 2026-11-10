package com.example.minimalapk.pose

/**
 * Decodes the `5+3K` pose head directly, in either channel-major `[1, 5+3K, N]` or anchor-major
 * `[1, N, 5+3K]` order. Channel 4 is the anchor score and the remaining channels are each keypoint's
 * x/y/confidence. Selecting and sorting anchors here avoids an intermediate table and its duplicate
 * filter and sort.
 */
object PoseDecoder {
    private const val SCORE_CHANNEL = 4
    private const val KEYPOINT_CHANNEL = 5
    private const val DETECTION_CAP = 300

    fun decode(raw: FloatArray, spec: ModelSpec): List<PoseDetection> {
        require(raw.size == spec.anchors * spec.valuesPerAnchor) {
            "模型输出大小 ${raw.size} 与配置的 ${spec.anchors}×${spec.valuesPerAnchor} 不符；" +
                "请在模型编辑页修正 anchor 数与关键点数"
        }
        // A primitive loop avoids boxing every anchor index the way filter/sortedByDescending do.
        val selected = ArrayList<Int>()
        for (anchor in 0 until spec.anchors) {
            if (raw[at(spec, SCORE_CHANNEL, anchor)] >= spec.scoreThreshold) {
                selected += anchor
            }
        }
        selected.sortByDescending { raw[at(spec, SCORE_CHANNEL, it)] }
        return selected.take(DETECTION_CAP).map { anchor ->
            val keypoints = ArrayList<PoseKeypoint>(spec.keypoints)
            for (keypoint in 0 until spec.keypoints) {
                val channel = KEYPOINT_CHANNEL + keypoint * 3
                keypoints += PoseKeypoint(
                    x = raw[at(spec, channel, anchor)],
                    y = raw[at(spec, channel + 1, anchor)],
                    confidence = raw[at(spec, channel + 2, anchor)],
                )
            }
            PoseDetection(
                left = boxLeft(raw, spec, anchor),
                top = boxTop(raw, spec, anchor),
                right = boxRight(raw, spec, anchor),
                bottom = boxBottom(raw, spec, anchor),
                score = raw[at(spec, SCORE_CHANNEL, anchor)],
                keypoints = keypoints,
            )
        }
    }

    /** Value index of one channel for one anchor in either layout. */
    private fun at(spec: ModelSpec, channel: Int, anchor: Int): Int =
        if (spec.outputLayout == OutputLayout.ANCHOR_MAJOR) {
            anchor * spec.valuesPerAnchor + channel
        } else {
            channel * spec.anchors + anchor
        }

    private fun boxLeft(raw: FloatArray, spec: ModelSpec, anchor: Int): Float =
        when (spec.boxFormat) {
            BoxFormat.XYXY -> raw[at(spec, 0, anchor)]
            BoxFormat.CENTER -> raw[at(spec, 0, anchor)] - raw[at(spec, 2, anchor)] / 2f
        }

    private fun boxTop(raw: FloatArray, spec: ModelSpec, anchor: Int): Float =
        when (spec.boxFormat) {
            BoxFormat.XYXY -> raw[at(spec, 1, anchor)]
            BoxFormat.CENTER -> raw[at(spec, 1, anchor)] - raw[at(spec, 3, anchor)] / 2f
        }

    private fun boxRight(raw: FloatArray, spec: ModelSpec, anchor: Int): Float =
        when (spec.boxFormat) {
            BoxFormat.XYXY -> raw[at(spec, 2, anchor)]
            BoxFormat.CENTER -> raw[at(spec, 0, anchor)] + raw[at(spec, 2, anchor)] / 2f
        }

    private fun boxBottom(raw: FloatArray, spec: ModelSpec, anchor: Int): Float =
        when (spec.boxFormat) {
            BoxFormat.XYXY -> raw[at(spec, 3, anchor)]
            BoxFormat.CENTER -> raw[at(spec, 1, anchor)] + raw[at(spec, 3, anchor)] / 2f
        }
}
