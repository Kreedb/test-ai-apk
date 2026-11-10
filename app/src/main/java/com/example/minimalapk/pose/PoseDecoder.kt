package com.example.minimalapk.pose

/**
 * Decodes the value-major `[1, 5+3K, N]` pose head directly. Channel 4 is the anchor score and the
 * remaining channels are each keypoint's x/y/confidence. Selecting and sorting anchors here avoids
 * an intermediate table and its duplicate filter and sort.
 */
object PoseDecoder {
    private const val SCORE_CHANNEL = 4
    private const val KEYPOINT_CHANNEL = 5
    private const val DETECTION_CAP = 300

    fun decode(raw: FloatArray, spec: ModelSpec): List<PoseDetection> {
        require(raw.size == spec.anchors * spec.valuesPerAnchor) {
            "Unexpected model output size: ${raw.size} for ${spec.name}"
        }
        // A primitive loop avoids boxing every anchor index the way filter/sortedByDescending do.
        val selected = ArrayList<Int>()
        for (anchor in 0 until spec.anchors) {
            if (raw[SCORE_CHANNEL * spec.anchors + anchor] >= spec.scoreThreshold) {
                selected += anchor
            }
        }
        selected.sortByDescending { raw[SCORE_CHANNEL * spec.anchors + it] }
        return selected.take(DETECTION_CAP).map { anchor ->
            val keypoints = ArrayList<PoseKeypoint>(spec.keypoints)
            for (keypoint in 0 until spec.keypoints) {
                val channel = KEYPOINT_CHANNEL + keypoint * 3
                keypoints += PoseKeypoint(
                    x = raw[channel * spec.anchors + anchor],
                    y = raw[(channel + 1) * spec.anchors + anchor],
                    confidence = raw[(channel + 2) * spec.anchors + anchor],
                )
            }
            PoseDetection(
                left = boxLeft(raw, spec, anchor),
                top = boxTop(raw, spec, anchor),
                right = boxRight(raw, spec, anchor),
                bottom = boxBottom(raw, spec, anchor),
                score = raw[SCORE_CHANNEL * spec.anchors + anchor],
                keypoints = keypoints,
            )
        }
    }

    private fun boxLeft(raw: FloatArray, spec: ModelSpec, anchor: Int): Float =
        when (spec.boxFormat) {
            BoxFormat.XYXY -> raw[anchor]
            BoxFormat.CENTER -> raw[anchor] - raw[2 * spec.anchors + anchor] / 2f
        }

    private fun boxTop(raw: FloatArray, spec: ModelSpec, anchor: Int): Float =
        when (spec.boxFormat) {
            BoxFormat.XYXY -> raw[spec.anchors + anchor]
            BoxFormat.CENTER -> raw[spec.anchors + anchor] - raw[3 * spec.anchors + anchor] / 2f
        }

    private fun boxRight(raw: FloatArray, spec: ModelSpec, anchor: Int): Float =
        when (spec.boxFormat) {
            BoxFormat.XYXY -> raw[2 * spec.anchors + anchor]
            BoxFormat.CENTER -> raw[anchor] + raw[2 * spec.anchors + anchor] / 2f
        }

    private fun boxBottom(raw: FloatArray, spec: ModelSpec, anchor: Int): Float =
        when (spec.boxFormat) {
            BoxFormat.XYXY -> raw[3 * spec.anchors + anchor]
            BoxFormat.CENTER -> raw[spec.anchors + anchor] + raw[3 * spec.anchors + anchor] / 2f
        }
}
