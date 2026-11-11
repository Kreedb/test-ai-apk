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

import kotlin.math.exp

/** Pixel transforms adapted from the pinned Model Zoo wrappers; no Android/native dependency. */
internal object VisionMath {
    private val imageNetMean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val imageNetStd = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun imageNetChw(pixels: IntArray): FloatArray = chw(pixels, normalize = true)

    fun chw(pixels: IntArray, normalize: Boolean = false, bgr: Boolean = false): FloatArray {
        val n = pixels.size
        val result = FloatArray(n * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            for (c in 0..2) {
                val shift = if (bgr) c * 8 else (2 - c) * 8
                val value = ((p ushr shift) and 255) / 255f
                result[c * n + i] = if (normalize) {
                    (value - imageNetMean[c]) / imageNetStd[c]
                } else value
            }
        }
        return result
    }

    /** NIMA is NHWC and uses [-1, 1], unlike the NCHW/ImageNet classifiers. */
    fun mobileNetNhwc(pixels: IntArray): FloatArray {
        val result = rgb255(pixels)
        for (i in result.indices) result[i] = result[i] / 127.5f - 1f
        return result
    }

    fun rgb255(pixels: IntArray): FloatArray {
        val result = FloatArray(pixels.size * 3)
        for (i in pixels.indices) {
            result[i * 3] = ((pixels[i] ushr 16) and 255).toFloat()
            result[i * 3 + 1] = ((pixels[i] ushr 8) and 255).toFloat()
            result[i * 3 + 2] = (pixels[i] and 255).toFloat()
        }
        return result
    }

    data class ScoredIndex(val index: Int, val probability: Float)

    /** Normalize over every class, not only the displayed top-k. Stable at large logits. */
    fun topK(logits: FloatArray, k: Int): List<ScoredIndex> {
        require(k >= 0)
        require(logits.isNotEmpty() && logits.all { it.isFinite() }) { "Invalid class logits" }
        val maximum = logits.maxOrNull()!!
        val probabilities = DoubleArray(logits.size) { exp((logits[it] - maximum).toDouble()) }
        val sum = probabilities.sum()
        return logits.indices.sortedByDescending { logits[it] }.take(k).map {
            ScoredIndex(it, (probabilities[it] / sum).toFloat())
        }
    }
}
