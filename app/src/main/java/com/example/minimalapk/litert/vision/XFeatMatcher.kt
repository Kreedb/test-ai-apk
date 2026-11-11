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
import kotlin.math.exp
import kotlin.math.sqrt

/** Model Zoo XFeat graph + grayscale instance norm, cell decoding, descriptor sampling and MNN. */
internal class XFeatMatcher(file: File, accelerator: Accelerator) : AutoCloseable {
    private val runner = CompiledModelRunner.fromFile(file.absolutePath, accelerator)

    fun extract(bitmap: Bitmap): Features {
        runner.writeInput(0, grayscale(resizedPixels(bitmap, W, H)))
        runner.run()
        return decode(runner.readOutput(0), runner.readOutput(2), runner.readOutput(1))
    }

    data class Features(val xs: FloatArray, val ys: FloatArray, val scores: FloatArray, val desc: Array<FloatArray>)
    data class Match(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val sim: Float)
    override fun close() = runner.close()

    companion object {
        const val W = 640
        const val H = 480
        const val GW = 80
        const val GH = 60
        const val D = 64
        const val TOP_K = 800
        const val MIN_COSSIM = 0.82f

        fun grayscale(pixels: IntArray): FloatArray {
            require(pixels.isNotEmpty())
            val gray = FloatArray(pixels.size) { i ->
                val p = pixels[i]
                0.299f * ((p ushr 16) and 255) + 0.587f * ((p ushr 8) and 255) + 0.114f * (p and 255)
            }
            val mean = gray.sumOf { it.toDouble() } / gray.size
            val variance = gray.sumOf { (it - mean) * (it - mean) } / gray.size
            val inverse = (1.0 / sqrt(variance + 1e-5)).toFloat()
            for (i in gray.indices) gray[i] = (gray[i] - mean.toFloat()) * inverse
            return gray
        }

        fun decode(features: FloatArray, reliability: FloatArray, keypointLogits: FloatArray): Features {
            require(features.size == D * GH * GW && reliability.size == GH * GW && keypointLogits.size == 65 * GH * GW)
            require(features.all { it.isFinite() } && reliability.all { it.isFinite() } && keypointLogits.all { it.isFinite() })
            val scoreMap = FloatArray(W * H)
            val cell = FloatArray(65)
            for (cy in 0 until GH) for (cx in 0 until GW) {
                var maximum = -Float.MAX_VALUE
                for (c in 0 until 65) {
                    cell[c] = keypointLogits[(c * GH + cy) * GW + cx]
                    maximum = maxOf(maximum, cell[c])
                }
                var sum = 0f
                for (c in 0 until 65) {
                    cell[c] = exp(cell[c] - maximum)
                    sum += cell[c]
                }
                // Include dustbin in the denominator, but never turn it into a keypoint.
                for (c in 0 until 64) {
                    scoreMap[(cy * 8 + c / 8) * W + cx * 8 + c % 8] = cell[c] / sum * reliability[cy * GW + cx]
                }
            }
            val candidates = ArrayList<Int>()
            for (y in 0 until H) for (x in 0 until W) {
                val score = scoreMap[y * W + x]
                if (score < 1e-4f) continue
                var maximum = true
                neighbors@ for (dy in -2..2) for (dx in -2..2) {
                    val yy = y + dy
                    val xx = x + dx
                    if (yy in 0 until H && xx in 0 until W && scoreMap[yy * W + xx] > score) {
                        maximum = false
                        break@neighbors
                    }
                }
                if (maximum) candidates.add(y * W + x)
            }
            candidates.sortByDescending { scoreMap[it] }
            val points = candidates.take(TOP_K)
            val xs = FloatArray(points.size) { (points[it] % W).toFloat() }
            val ys = FloatArray(points.size) { (points[it] / W).toFloat() }
            return Features(xs, ys, FloatArray(points.size) { scoreMap[points[it]] },
                Array(points.size) { sampleDescriptor(features, xs[it], ys[it]) })
        }

        /** Upstream half-cell bilinear sampling in the channel-first feature map, then L2. */
        fun sampleDescriptor(features: FloatArray, x: Float, y: Float): FloatArray {
            require(features.size == D * GH * GW)
            val fx = (x / 8f - 0.5f).coerceIn(0f, GW - 1.001f)
            val fy = (y / 8f - 0.5f).coerceIn(0f, GH - 1.001f)
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val ax = fx - x0
            val ay = fy - y0
            val descriptor = FloatArray(D) { d ->
                val base = d * GH * GW + y0 * GW + x0
                features[base] * (1 - ax) * (1 - ay) + features[base + 1] * ax * (1 - ay) +
                    features[base + GW] * (1 - ax) * ay + features[base + GW + 1] * ax * ay
            }
            val norm = sqrt(descriptor.sumOf { it.toDouble() * it }).toFloat() + 1e-9f
            for (d in descriptor.indices) descriptor[d] /= norm
            return descriptor
        }

        fun match(a: Features, b: Features): List<Match> {
            fun validate(f: Features) {
                require(f.xs.size == f.desc.size && f.ys.size == f.desc.size && f.scores.size == f.desc.size)
                require(f.desc.all { it.size == D })
            }
            validate(a)
            validate(b)
            if (a.desc.isEmpty() || b.desc.isEmpty()) return emptyList()
            val bestB = IntArray(a.desc.size) { -1 }
            val scoreB = FloatArray(a.desc.size) { -2f }
            val bestA = IntArray(b.desc.size) { -1 }
            val scoreA = FloatArray(b.desc.size) { -2f }
            for (i in a.desc.indices) for (j in b.desc.indices) {
                var similarity = 0f
                for (d in 0 until D) similarity += a.desc[i][d] * b.desc[j][d]
                if (similarity > scoreB[i]) { scoreB[i] = similarity; bestB[i] = j }
                if (similarity > scoreA[j]) { scoreA[j] = similarity; bestA[j] = i }
            }
            return a.desc.indices.mapNotNull { i ->
                val j = bestB[i]
                if (j >= 0 && bestA[j] == i && scoreB[i] >= MIN_COSSIM) {
                    Match(a.xs[i], a.ys[i], b.xs[j], b.ys[j], scoreB[i])
                } else null
            }
        }
    }
}
