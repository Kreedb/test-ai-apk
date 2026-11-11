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
import kotlin.math.sqrt

/** Model Zoo DINOv2 ViT-S/14: 448² RGB/ImageNet -> 32×32 patch tokens, each 384-dimensional. */
internal class Dinov2Features(file: File, accelerator: Accelerator) : AutoCloseable {
    private val runner = CompiledModelRunner.fromFile(file.absolutePath, accelerator)

    fun featureMap(bitmap: Bitmap): Bitmap {
        runner.writeInput(0, VisionMath.imageNetChw(resizedPixels(bitmap, SIZE)))
        runner.run()
        val colors = DinoPca.rgb(runner.readOutput(0), GRID * GRID, DIM)
        return Bitmap.createBitmap(colors, GRID, GRID, Bitmap.Config.ARGB_8888)
    }

    override fun close() = runner.close()
    companion object {
        const val SIZE = 448
        const val GRID = 32
        const val DIM = 384
    }
}

/**
 * The upstream host PCA: channel centering, covariance, three components via power iteration and deflation,
 * independent min/max RGB scaling. Colors are relative within one image, not class labels.
 */
internal object DinoPca {
    fun rgb(features: FloatArray, rows: Int, dimensions: Int): IntArray {
        require(rows > 0 && dimensions > 0 && features.size == rows * dimensions)
        require(features.all { it.isFinite() }) { "Non-finite DINOv2 patch features" }
        val mean = DoubleArray(dimensions)
        for (row in 0 until rows) for (d in 0 until dimensions) mean[d] += features[row * dimensions + d]
        for (d in mean.indices) mean[d] /= rows.toDouble()
        val centered = FloatArray(features.size) { (features[it] - mean[it % dimensions]).toFloat() }
        val covariance = FloatArray(dimensions * dimensions)
        for (row in 0 until rows) {
            val base = row * dimensions
            for (a in 0 until dimensions) {
                val value = centered[base + a]
                if (value == 0f) continue
                for (b in a until dimensions) covariance[a * dimensions + b] += value * centered[base + b]
            }
        }
        for (a in 0 until dimensions) for (b in a + 1 until dimensions) {
            covariance[b * dimensions + a] = covariance[a * dimensions + b]
        }
        val channels = Array(3) { IntArray(rows) }
        val previous = mutableListOf<FloatArray>()
        repeat(minOf(3, dimensions)) { component ->
            // Seed the whole space: a single diagonal pivot can miss a stronger independent block.
            // Non-uniform weights also avoid the upstream alternating seed cancelling equal channels.
            val pivot = (0 until dimensions).maxByOrNull { covariance[it * dimensions + it] }!!
            var vector = normalize(FloatArray(dimensions) { (it + 1f) / dimensions })
            repeat(40) {
                var next = multiply(covariance, vector)
                if (next.all { value -> kotlin.math.abs(value) < 1e-12f }) {
                    next = FloatArray(dimensions) { covariance[it * dimensions + pivot] }
                }
                for (basis in previous) {
                    val projection = dot(next, basis)
                    for (d in next.indices) next[d] -= projection * basis[d]
                }
                vector = normalize(next)
            }
            val projected = FloatArray(rows) { row ->
                var sum = 0f
                for (d in 0 until dimensions) sum += centered[row * dimensions + d] * vector[d]
                sum
            }
            val low = projected.minOrNull()!!
            val high = projected.maxOrNull()!!
            val span = high - low
            if (span > 1e-6f) {
                channels[component] = IntArray(rows) { ((projected[it] - low) / span * 255).toInt().coerceIn(0, 255) }
            }
            val lambda = dot(vector, multiply(covariance, vector))
            for (a in 0 until dimensions) for (b in 0 until dimensions) {
                covariance[a * dimensions + b] -= lambda * vector[a] * vector[b]
            }
            previous.add(vector)
        }
        return IntArray(rows) { (255 shl 24) or (channels[0][it] shl 16) or (channels[1][it] shl 8) or channels[2][it] }
    }

    fun normalize(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { it.toDouble() * it })
        return if (norm < 1e-12) FloatArray(values.size) else FloatArray(values.size) { (values[it] / norm).toFloat() }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float = a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat()

    private fun multiply(matrix: FloatArray, vector: FloatArray): FloatArray = FloatArray(vector.size) { row ->
        var sum = 0f
        for (d in vector.indices) sum += matrix[row * vector.size + d] * vector[d]
        sum
    }
}
