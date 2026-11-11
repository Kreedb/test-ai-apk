/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/{zipformer,panns,tiger,cmgan}.
 */
package com.example.minimalapk.litert.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Stateless batch conversion. Downsampling includes a low-pass filter instead of aliasing music. */
internal object AudioDsp {
    fun atRate(samples: FloatArray, fromRate: Int, toRate: Int, control: AudioControl): FloatArray {
        AudioLimits.validatePcm(samples, fromRate, control)
        require(toRate in AudioLimits.MIN_SAMPLE_RATE..AudioLimits.MAX_SAMPLE_RATE)
        val count = (samples.size.toLong() * toRate / fromRate).coerceAtLeast(1)
        require(count <= toRate.toLong() * AudioLimits.MAX_SECONDS)
        if (fromRate == toRate) {
            return FloatArray(samples.size) { i ->
                if (i % 4096 == 0) control.checkpoint()
                samples[i].coerceIn(-1f, 1f)
            }
        }
        val ratio = fromRate.toDouble() / toRate
        val cutoff = minOf(1.0, 1.0 / ratio)
        val radius = ceil(16.0 / cutoff).toInt()
        return FloatArray(count.toInt()) { i ->
            if (i % 256 == 0) control.checkpoint()
            val position = i * ratio
            val center = position.toInt()
            var value = 0.0
            var weight = 0.0
            for (j in center - radius + 1..center + radius) {
                val distance = position - j
                if (abs(distance) >= radius) continue
                val x = distance * cutoff
                val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                val w = cutoff * sinc * (0.5 + 0.5 * cos(PI * distance / radius))
                value += samples[j.coerceIn(0, samples.lastIndex)].coerceIn(-1f, 1f) * w
                weight += w
            }
            (value / weight).toFloat().coerceIn(-1f, 1f)
        }
    }

    fun windowCount(samples: Int, window: Int, hop: Int): Int {
        require(samples > 0 && window > 0 && hop in 1..window)
        return if (samples <= window) 1 else 1 + ((samples - window).toLong() + hop - 1).div(hop).toInt()
    }

    /** Zero-fill the final window, then reflect without duplicating either edge (torch center=True). */
    fun reflectedWindow(pcm: FloatArray, start: Int, window: Int, pad: Int, scale: Float = 1f): FloatArray {
        require(start in pcm.indices && window > pad && pad >= 0 && scale.isFinite())
        val result = FloatArray(window + 2 * pad)
        for (i in 0 until minOf(window, pcm.size - start)) result[pad + i] = pcm[start + i] * scale
        for (j in 1..pad) {
            result[pad - j] = result[pad + j]
            result[pad + window - 1 + j] = result[pad + window - 1 - j]
        }
        return result
    }

    /** Kaldi snip_edges=false duplicates the endpoint; unlike torch reflect, repeats work for n=1. */
    fun kaldiIndex(index: Int, size: Int): Int {
        require(size > 0)
        val period = size.toLong() * 2
        val p = ((index.toLong() % period) + period) % period
        return (if (p < size) p else period - 1 - p).toInt()
    }
}

internal class AudioFft(private val size: Int) {
    init { require(size in 2..4096 && size and (size - 1) == 0) }
    private val cosines = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sines = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }

    fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean = false) {
        require(real.size == size && imag.size == size)
        var j = 0
        for (i in 0 until size - 1) {
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val m = imag[i]; imag[i] = imag[j]; imag[j] = m
            }
            var bit = size shr 1
            while (bit in 1..j) { j -= bit; bit = bit shr 1 }
            j += bit
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val stride = size / length
            for (base in 0 until size step length) {
                for (pair in 0 until half) {
                    val a = base + pair
                    val b = a + half
                    val wr = cosines[pair * stride]
                    val wi = sines[pair * stride] * if (inverse) 1f else -1f
                    val r = real[b] * wr - imag[b] * wi
                    val m = real[b] * wi + imag[b] * wr
                    real[b] = real[a] - r; imag[b] = imag[a] - m
                    real[a] += r; imag[a] += m
                }
            }
            length *= 2
        }
        if (inverse) for (i in 0 until size) { real[i] /= size; imag[i] /= size }
    }
}
