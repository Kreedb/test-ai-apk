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

class PpocrTest {
    @Test
    fun ctcDropsBlankAndAdjacentRepeatsButPreservesSeparatedRepeatsAndDictionarySpaces() {
        val chars = PpocrRecognizer.characters(listOf("A", "中"))
        assertEquals(listOf("", "A", "中", " "), chars)
        val path = intArrayOf(1, 1, 0, 1, 2, 2, 3, 0)
        val probabilities = FloatArray(path.size * chars.size) { 0.05f }
        path.forEachIndexed { time, token -> probabilities[time * chars.size + token] = 0.85f }
        val text = PpocrRecognizer.decode(probabilities, chars)
        assertEquals("AA中 ", text.text)
        assertEquals(4, text.tokenCount)
        assertEquals(0.85f, text.confidence, 1e-6f) // already-softmax output must not be softmaxed again
    }

    @Test
    fun ctcConfidenceExcludesBlanksAndSuppressedDuplicates() {
        val result = PpocrRecognizer.decode(floatArrayOf(0.1f, 0.9f, 0.2f, 0.8f, 0.99f, 0.01f, 0.3f, 0.7f), listOf("", "A"))
        assertEquals("AA", result.text)
        assertEquals(0.8f, result.confidence, 1e-6f)
        assertEquals(0f, PpocrRecognizer.decode(floatArrayOf(1f, 0f), listOf("", "A")).confidence, 0f)
        assertThrows(IllegalArgumentException::class.java) { PpocrRecognizer.decode(FloatArray(5), listOf("", "A")) }
    }

    @Test
    fun textComponentsExpandAndClipButRejectLowConfidenceAndTinyRegions() {
        val map = FloatArray(PpocrDetector.SIZE * PpocrDetector.SIZE)
        fun rectangle(x: Int, y: Int, width: Int, height: Int, score: Float) {
            for (dy in 0 until height) for (dx in 0 until width) map[(y + dy) * PpocrDetector.SIZE + x + dx] = score
        }
        rectangle(30, 20, 8, 8, 0.9f)
        rectangle(50, 50, 8, 8, 0.4f)
        rectangle(100, 100, 5, 5, 0.99f)
        rectangle(0, 0, 6, 6, 0.8f)
        val boxes = PpocrDetector.boxes(map)
        assertEquals(2, boxes.size)
        assertEquals(listOf(0, 0, 7, 7), boxes[0].let { listOf(it.x0, it.y0, it.x1, it.y1) })
        assertEquals(listOf(28, 18, 39, 29), boxes[1].let { listOf(it.x0, it.y0, it.x1, it.y1) })
        assertEquals(0.9f, boxes[1].score, 1e-6f)
    }

    @Test
    fun floodFillDoesNotJoinOppositeImageEdges() {
        val map = FloatArray(640 * 640)
        for (y in 20..27) for (x in 0..7) {
            map[y * 640 + x] = 1f
            map[y * 640 + 632 + x] = 1f
        }
        assertEquals(2, PpocrDetector.boxes(map).size)
    }

    @Test
    fun readingOrderGroupsBaselinesThenReadsLeftToRight() {
        val boxes = listOf(
            OcrDisplayBox(100, 11, 150, 31, "world"),
            OcrDisplayBox(10, 50, 100, 70, "second row"),
            OcrDisplayBox(10, 8, 80, 28, "Hello"),
        )
        assertEquals(listOf("Hello world", "second row"), OcrReadingOrder.lines(boxes))
    }
}
