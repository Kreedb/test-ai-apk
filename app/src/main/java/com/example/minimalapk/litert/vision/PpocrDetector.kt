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

import android.graphics.Bitmap
import com.example.minimalapk.litert.CompiledModelRunner
import com.google.ai.edge.litert.Accelerator
import java.io.File

/** Pinned Model Zoo PP-OCRv5 DBNet detector and its axis-aligned component/unclip postprocessing. */
internal class PpocrDetector(file: File, accelerator: Accelerator) : AutoCloseable {
    private val runner = CompiledModelRunner.fromFile(file.absolutePath, accelerator)

    fun detect(square: Bitmap): List<Box> {
        require(square.width == SIZE && square.height == SIZE)
        runner.writeInput(0, VisionMath.imageNetChw(square.visionPixels()))
        runner.run()
        return boxes(runner.readOutput(0))
    }

    /** Unlike drawing edges, the detector's x1/y1 indices are inclusive. */
    data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val score: Float) {
        val width: Int get() = x1 - x0 + 1
        val height: Int get() = y1 - y0 + 1
    }

    override fun close() = runner.close()

    companion object {
        const val MODEL = "ppocr_det_fp16.tflite"
        const val SIZE = 640
        const val THRESH = 0.3f
        const val BOX_THRESH = 0.5f
        const val MIN_SIZE = 6

        fun boxes(probability: FloatArray): List<Box> {
            val n = SIZE * SIZE
            require(probability.size == n && probability.all { it.isFinite() }) { "Invalid OCR probability map" }
            val visited = BooleanArray(n)
            val stack = IntArray(n)
            val result = ArrayList<Box>()
            for (start in 0 until n) {
                if (visited[start] || probability[start] <= THRESH) continue
                var depth = 1
                stack[0] = start
                visited[start] = true
                var x0 = SIZE
                var y0 = SIZE
                var x1 = 0
                var y1 = 0
                var area = 0
                var sum = 0.0
                fun push(p: Int) {
                    if (!visited[p] && probability[p] > THRESH) {
                        visited[p] = true
                        stack[depth++] = p
                    }
                }
                while (depth > 0) {
                    val p = stack[--depth]
                    val x = p % SIZE
                    val y = p / SIZE
                    x0 = minOf(x0, x); x1 = maxOf(x1, x)
                    y0 = minOf(y0, y); y1 = maxOf(y1, y)
                    area++
                    sum += probability[p]
                    if (x > 0) push(p - 1)
                    if (x < SIZE - 1) push(p + 1)
                    if (y > 0) push(p - SIZE)
                    if (y < SIZE - 1) push(p + SIZE)
                }
                val width = x1 - x0 + 1
                val height = y1 - y0 + 1
                val score = (sum / area).toFloat()
                if (width < MIN_SIZE || height < MIN_SIZE || score < BOX_THRESH) continue
                // Preserve upstream's approximate DB dilation; do not pretend these are rotated polygons.
                val pad = (minOf(width, height) * 0.35f).toInt().coerceIn(2, 24)
                result.add(Box((x0 - pad).coerceAtLeast(0), (y0 - pad).coerceAtLeast(0),
                    (x1 + pad).coerceAtMost(SIZE - 1), (y1 + pad).coerceAtMost(SIZE - 1), score))
            }
            return result.sortedWith(compareBy({ it.y0 / 16 }, { it.x0 }))
        }
    }
}
