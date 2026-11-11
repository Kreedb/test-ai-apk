/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.minimalapk.litert.imaging

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/** Host stages from pinned Zoo models/rfdetrseg/RfDetrSeg.kt; masks stay attached to their query. */
internal data class RfSegDetection(
    val cls: Int,
    val score: Float,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val mask: FloatArray,
    val query: Int,
)

internal object RfDetrSegMath {
    const val SIZE = 312
    const val GRID = 26
    const val PROPOSALS = GRID * GRID
    const val QUERIES = 100
    const val CLASSES = 91
    const val HIDDEN = 256
    const val MASK = 78
    const val SCORE_THRESHOLD = .5f
    const val IOU_THRESHOLD = .6f
    const val PRIOR_WH = .05f

    fun littleEndianFloats(bytes: ByteArray, count: Int, name: String): FloatArray {
        require(bytes.size == count * 4) { "$name: expected ${count * 4} bytes, got ${bytes.size}" }
        return FloatArray(count).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
            requireImagingFloats(it, count, name)
        }
    }

    /** Capacity matching is converter-order independent; ambiguity is a contract error. */
    fun slot(sizes: List<Int>, count: Int): Int {
        val matches = sizes.indices.filter { sizes[it] == count }
        require(matches.size == 1) { "RF-DETR-Seg expected one $count-float tensor, got $sizes" }
        return matches.single()
    }

    fun referencePoints(
        logits: FloatArray,
        deltas: FloatArray,
        embedding: FloatArray,
        grid: Int = GRID,
        classes: Int = CLASSES,
        queries: Int = QUERIES,
    ): FloatArray {
        require(grid > 0 && classes > 0 && queries in 1..grid * grid)
        val proposals = grid * grid
        requireImagingFloats(logits, proposals * classes, "RF-DETR encoder class logits")
        requireImagingFloats(deltas, proposals * 4, "RF-DETR encoder deltas")
        requireImagingFloats(embedding, queries * 4, "RF-DETR reference embedding")
        val maxScore = FloatArray(proposals) { p ->
            var best = -Float.MAX_VALUE
            for (c in 0 until classes) best = maxOf(best, logits[p * classes + c])
            best
        }
        val order = (0 until proposals).sortedByDescending { maxScore[it] }
        val result = FloatArray(queries * 4)
        for (q in 0 until queries) {
            val p = order[q]
            val d = p * 4
            val r = q * 4
            val cx = deltas[d] * PRIOR_WH + (p % grid + .5f) / grid
            val cy = deltas[d + 1] * PRIOR_WH + (p / grid + .5f) / grid
            val width = exp(deltas[d + 2].toDouble()).toFloat() * PRIOR_WH
            val height = exp(deltas[d + 3].toDouble()).toFloat() * PRIOR_WH
            // Embedding is indexed by decoder query rank, not encoder proposal index.
            result[r] = embedding[r] * width + cx
            result[r + 1] = embedding[r + 1] * height + cy
            result[r + 2] = exp(embedding[r + 2].toDouble()).toFloat() * width
            result[r + 3] = exp(embedding[r + 3].toDouble()).toFloat() * height
        }
        requireImagingFloats(result, queries * 4, "RF-DETR reference points")
        return result
    }

    /** Graph A deliberately emits memory*2 to avoid an aliased GPU output being zeroed. */
    fun restoreMemory(doubled: FloatArray, expected: Int = PROPOSALS * HIDDEN): FloatArray {
        requireImagingFloats(doubled, expected, "RF-DETR doubled memory")
        for (i in doubled.indices) doubled[i] *= .5f
        return doubled
    }

    fun decode(
        boxes: FloatArray,
        logits: FloatArray,
        masks: FloatArray,
        queries: Int = QUERIES,
        classes: Int = CLASSES,
        maskSize: Int = MASK,
    ): List<RfSegDetection> {
        require(queries > 0 && classes > 1 && maskSize > 0)
        requireImagingFloats(boxes, queries * 4, "RF-DETR boxes")
        requireImagingFloats(logits, queries * classes, "RF-DETR class logits")
        requireImagingFloats(masks, queries * maskSize * maskSize, "RF-DETR masks")
        val detections = buildList {
            for (q in 0 until queries) {
                var cls = 0
                for (c in 1 until classes) if (logits[q * classes + c] > logits[q * classes + cls]) cls = c
                val score = (1.0 / (1.0 + exp(-logits[q * classes + cls].toDouble()))).toFloat()
                if (cls == 0 || score < SCORE_THRESHOLD || boxes[q * 4 + 2] <= 0f || boxes[q * 4 + 3] <= 0f) continue
                add(RfSegDetection(cls, score, boxes[q * 4], boxes[q * 4 + 1], boxes[q * 4 + 2], boxes[q * 4 + 3],
                    masks.copyOfRange(q * maskSize * maskSize, (q + 1) * maskSize * maskSize), q))
            }
        }
        return nms(detections)
    }

    fun nms(detections: List<RfSegDetection>): List<RfSegDetection> {
        val result = mutableListOf<RfSegDetection>()
        for (detection in detections.sortedByDescending { it.score }) {
            if (result.none { it.cls == detection.cls && iou(it, detection) > IOU_THRESHOLD }) result += detection
        }
        return result
    }

    fun iou(a: RfSegDetection, b: RfSegDetection): Float {
        val left = maxOf(a.cx - a.width / 2, b.cx - b.width / 2)
        val top = maxOf(a.cy - a.height / 2, b.cy - b.height / 2)
        val right = minOf(a.cx + a.width / 2, b.cx + b.width / 2)
        val bottom = minOf(a.cy + a.height / 2, b.cy + b.height / 2)
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union > 0f) intersection / union else 0f
    }

    /** Raw mask logits use >0 (sigmoid >.5), not >.5 on logits. */
    fun maskPixels(mask: FloatArray, color: Int): IntArray = IntArray(mask.size) {
        if (mask[it] > 0f) (110 shl 24) or (color and 0xffffff) else 0
    }

    /** Match verify_rf_detr_seg_nano.py: resize logits first, THEN threshold in source space. */
    fun sourceMaskPixels(
        mask: FloatArray,
        color: Int,
        width: Int,
        height: Int,
        maskSize: Int = MASK,
    ): IntArray {
        require(width > 0 && height > 0 && maskSize > 0)
        requireImagingFloats(mask, maskSize * maskSize, "RF-DETR instance mask")
        val tint = (110 shl 24) or (color and 0xffffff)
        return IntArray(Math.multiplyExact(width, height)) { i ->
            val x = (i % width + .5f) * maskSize / width - .5f
            val y = (i / width + .5f) * maskSize / height - .5f
            if (sampleImagingGrid(mask, maskSize, maskSize, x, y) > 0f) tint else 0
        }
    }
}
