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

class XFeatTest {
    @Test
    fun grayscaleInstanceNormHandlesConstantImagesAndChannelWeights() {
        assertArrayEquals(FloatArray(4), XFeatMatcher.grayscale(IntArray(4) { 0xff777777.toInt() }), 0f)
        val gray = XFeatMatcher.grayscale(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt()))
        assertTrue(gray[1] > gray[0] && gray[0] > gray[2])
        assertEquals(0.0, gray.sumOf { it.toDouble() } / gray.size, 1e-6)
        assertEquals(1.0, gray.sumOf { it.toDouble() * it } / gray.size, 1e-5)
    }

    @Test
    fun decodingUsesChannelFirstCellOffsetsDustbinAndReliability() {
        val cells = XFeatMatcher.GW * XFeatMatcher.GH
        val features = FloatArray(XFeatMatcher.D * cells)
        for (i in 0 until cells) features[i] = 1f // descriptor e0
        val reliability = FloatArray(cells)
        val logits = FloatArray(65 * cells) { -30f }
        for (i in 0 until cells) logits[64 * cells + i] = 30f // dustbin everywhere
        val cell = 3 * XFeatMatcher.GW + 2
        reliability[cell] = 0.75f
        logits[64 * cells + cell] = -30f
        logits[19 * cells + cell] = 30f // local x=3, y=2, global x=19, y=26
        val decoded = XFeatMatcher.decode(features, reliability, logits)
        assertEquals(1, decoded.xs.size)
        assertEquals(19f, decoded.xs[0], 0f)
        assertEquals(26f, decoded.ys[0], 0f)
        assertEquals(0.75f, decoded.scores[0], 1e-6f)
        assertEquals(1f, decoded.desc[0][0], 1e-6f)
        assertTrue(decoded.desc[0].drop(1).all { it == 0f })
    }

    @Test
    fun descriptorSamplingUsesHalfCellBilinearCoordinatesThenNormalizes() {
        val cells = XFeatMatcher.GW * XFeatMatcher.GH
        val features = FloatArray(XFeatMatcher.D * cells)
        // At input (8,8), feature coordinate is (.5,.5). First two channels average to 3 and 4.
        features[0] = 12f
        features[cells + XFeatMatcher.GW + 1] = 16f
        val descriptor = XFeatMatcher.sampleDescriptor(features, 8f, 8f)
        assertEquals(0.6f, descriptor[0], 1e-6f)
        assertEquals(0.8f, descriptor[1], 1e-6f)
    }

    @Test
    fun matchingRequiresMutualNearestNeighborAndCosineFloor() {
        fun features(descriptors: Array<FloatArray>) = XFeatMatcher.Features(
            FloatArray(descriptors.size) { it * 10f }, FloatArray(descriptors.size) { it * 20f },
            FloatArray(descriptors.size) { 1f }, descriptors)
        val x = FloatArray(64) { if (it == 0) 1f else 0f }
        val y = FloatArray(64) { if (it == 1) 1f else 0f }
        val matches = XFeatMatcher.match(features(arrayOf(x, y)), features(arrayOf(y, x)))
        assertEquals(2, matches.size)
        assertEquals(10f, matches[0].x1, 0f)
        assertEquals(1f, matches[0].sim, 0f)
        assertEquals(1, XFeatMatcher.match(features(arrayOf(x, x)), features(arrayOf(x))).size)
        assertTrue(XFeatMatcher.match(features(arrayOf(x)), features(arrayOf(y))).isEmpty())
        assertTrue(XFeatMatcher.match(features(emptyArray()), features(arrayOf(x))).isEmpty())
    }
}
