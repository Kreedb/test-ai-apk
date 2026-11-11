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

class VisionMathTest {
    @Test
    fun channelOrderAndLayoutMatchTheThreeDifferentModelContracts() {
        val redBlue = intArrayOf(0xffff0000.toInt(), 0xff0000ff.toInt())
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f), VisionMath.chw(redBlue), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f), VisionMath.chw(redBlue, bgr = true), 0f)
        assertArrayEquals(floatArrayOf(1f, -1f, -1f, -1f, -1f, 1f), VisionMath.mobileNetNhwc(redBlue), 0f)
        val normalized = VisionMath.imageNetChw(redBlue)
        assertEquals((1f - 0.485f) / 0.229f, normalized[0], 1e-6f)
        assertEquals(-0.456f / 0.224f, normalized[2], 1e-6f)
        assertEquals((1f - 0.406f) / 0.225f, normalized[5], 1e-6f)
    }

    @Test
    fun topKNormalizesAcrossAllClassesAndDoesNotDuplicateWhenKIsTooLarge() {
        val logits = floatArrayOf(999f, 1000f, 998f)
        val top = VisionMath.topK(logits, 1).single()
        assertEquals(1, top.index)
        assertEquals(0.66524094f, top.probability, 1e-6f)
        val all = VisionMath.topK(logits, 10)
        assertEquals(listOf(1, 0, 2), all.map { it.index })
        assertEquals(1.0, all.sumOf { it.probability.toDouble() }, 1e-6)
        assertTrue(VisionMath.topK(logits, 0).isEmpty())
    }

    @Test
    fun corruptLogitsFailRatherThanShowingAnArbitraryHighConfidenceLabel() {
        assertThrows(IllegalArgumentException::class.java) { VisionMath.topK(floatArrayOf(Float.NaN, 1f), 1) }
    }

    @Test
    fun plantNetLabelsUseLexicographicImageFolderOrderNotNumericOrder() {
        assertEquals(listOf("one", "ten", "two"), PlantClassifier.speciesLabels(mapOf("2" to "two", "10" to "ten", "1" to "one")))
    }

    @Test
    fun rwkvRuntimeDistancesUseFlattenedTokenDistance() {
        val d = VrwkvClassifier.tokenDistances()
        val n = VrwkvClassifier.TOKENS
        assertEquals(0f, d[42 * n + 42], 0f)
        assertEquals(195f, d[195], 0f)
        assertEquals(195f, d[195 * n], 0f)
        assertEquals(1f, d[13 * n + 14], 0f)
    }

    @Test
    fun densityIntegralUsesRawValuesAndNeverDisplayAreaScaling() {
        assertEquals(2f, CrowdCounter.sumDensity(floatArrayOf(0f, 0.25f, 0.5f, 1.25f)), 0f)
        assertEquals(16f, CrowdCounter.sumDensity(FloatArray(64 * 64) { 1f / 256 }), 0f)
    }

    @Test
    fun nimaMeanUsesOneBasedAestheticBins() {
        assertEquals(5.5f, NimaScorer.meanScore(FloatArray(10) { 0.1f }), 1e-6f)
        assertEquals(10f, NimaScorer.meanScore(FloatArray(10) { if (it == 9) 1f else 0f }), 0f)
        assertThrows(IllegalArgumentException::class.java) { NimaScorer.meanScore(FloatArray(10)) }
    }
}
