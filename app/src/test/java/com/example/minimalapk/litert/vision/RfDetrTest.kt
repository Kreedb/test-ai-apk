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

package com.example.minimalapk.litert.vision

import org.junit.Assert.*
import org.junit.Test

class RfDetrTest {
    @Test
    fun proposalSelectionGathersCoordinatesInDescendingMaximumClassLogitOrder() {
        val classes = FloatArray(RfDetr.NPROP * RfDetr.NCLS) { -1000f }
        for (p in 0 until RfDetr.NPROP) classes[p * RfDetr.NCLS + 17] = p.toFloat()
        classes[5 * RfDetr.NCLS + 90] = 10000f
        val coords = FloatArray(RfDetr.NPROP * 4) { it.toFloat() }
        val selected = RfDetr.selectReferencePoints(classes, coords)
        assertEquals(RfDetr.NQ * 4, selected.size)
        assertArrayEquals(floatArrayOf(20f, 21f, 22f, 23f), selected.copyOfRange(0, 4), 0f)
        assertArrayEquals(floatArrayOf(2300f, 2301f, 2302f, 2303f), selected.copyOfRange(4, 8), 0f)
        assertArrayEquals(floatArrayOf(1108f, 1109f, 1110f, 1111f), selected.takeLast(4).toFloatArray(), 0f)
    }

    @Test
    fun detectionUsesSigmoidSparseCocoIdsAndClassAwareSuppression() {
        val boxes = FloatArray(RfDetr.NQ * 4)
        val logits = FloatArray(RfDetr.NQ * RfDetr.NCLS) { -100f }
        fun query(q: Int, cls: Int, logit: Float) {
            floatArrayOf(0.5f, 0.4f, 0.3f, 0.2f).copyInto(boxes, q * 4)
            logits[q * RfDetr.NCLS + cls] = logit
        }
        query(0, 1, 4f)
        query(1, 1, 3f) // same box and class: suppressed
        query(2, 17, 2f) // same box, different class: retained
        query(3, 12, 8f) // unused COCO category ID: ignored
        query(4, 90, -1f) // below threshold
        val detections = RfDetr.decode(boxes, logits)
        assertEquals(listOf(1, 17), detections.map { it.cls })
        assertEquals(0.98201376f, detections[0].score, 1e-6f)
        assertEquals(0.3f, detections[0].w, 0f)
        assertEquals("cat", COCO_LABELS[17])
        assertEquals("toothbrush", COCO_LABELS[90])
        assertEquals(80, COCO_LABELS.count { it.isNotEmpty() })
    }
}
