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

class RamAndDinoTest {
    @Test
    fun ramThresholdsAreLittleEndianAndStrictlyPerClassNotTopKOnly() {
        val thresholds = RamTagger.thresholds(byteArrayOf(0, 0, 0, 0x3f, 0, 0, 0x40, 0x3f))
        assertArrayEquals(floatArrayOf(0.5f, 0.75f), thresholds, 0f)
        assertTrue(RamTagger.selectTags(floatArrayOf(0f, 1f), listOf("a", "b"), thresholds, 40).isEmpty())
        val tags = RamTagger.selectTags(floatArrayOf(4f, 2f, 3f), listOf("a", "b", "c"), floatArrayOf(0.99f, 0.5f, 0.5f), 1)
        assertEquals("c", tags.single().name) // highest logit a is below its own stricter threshold
        assertEquals(0.95257413f, tags.single().probability, 1e-6f)
        assertEquals(2, tags.single().index)
    }

    @Test
    fun dinoPcaCentersChannelsAndGivesIdenticalPatchesIdenticalColors() {
        val features = floatArrayOf(1f, 1f, 2f, 2f, 2f, 2f, 3f, 3f)
        val colors = DinoPca.rgb(features, 4, 2)
        assertEquals(colors[1], colors[2])
        assertNotEquals(colors[0], colors[3])
        // A per-channel translation must not change PCA: it is removed by centering.
        val shifted = FloatArray(features.size) { features[it] + if (it % 2 == 0) 10f else -30f }
        assertArrayEquals(colors, DinoPca.rgb(shifted, 4, 2))
    }

    @Test
    fun dinoPcaConstantTokensDoNotDivideByZeroOrInventStructure() {
        val colors = DinoPca.rgb(FloatArray(12) { if (it % 3 == 0) 7f else -2f }, 4, 3)
        assertEquals(1, colors.toSet().size)
        assertEquals(255, colors[0] ushr 24)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), DinoPca.normalize(floatArrayOf(3f, 4f)), 1e-6f)
        assertArrayEquals(FloatArray(2), DinoPca.normalize(FloatArray(2)), 0f)
    }

    @Test
    fun dinoLeadingComponentCanSpanChannelsWhoseIndividualVarianceIsSmaller() {
        // Covariance diag(10, [6 6; 6 6]); the leading eigenvalue is 12, not the largest diagonal 10.
        val a = kotlin.math.sqrt(2.5f)
        val b = kotlin.math.sqrt(1.5f)
        val colors = DinoPca.rgb(floatArrayOf(a, b, b, -a, b, b, a, -b, -b, -a, -b, -b), 4, 3)
        val red = colors.map { (it ushr 16) and 255 }
        assertTrue(kotlin.math.abs(red[0] - red[1]) <= 1)
        assertTrue(kotlin.math.abs(red[2] - red[3]) <= 1)
        assertTrue(kotlin.math.abs(red[0] - red[2]) >= 250)
    }
}
