/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/cmgan/NoiseSuppressor.kt.
 */
package com.example.minimalapk.litert.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** CMGAN compressed-complex graph plus power decompression and 400-point Hamming inverse DFT. */
internal class CmganEnhancer(directory: File, compiler: AudioCompiler) : AutoCloseable {
    companion object {
        const val RATE = 16000
        const val FFT = 400
        const val HOP = 100
        const val BINS = 201
        const val CHUNK = 32000
        const val FRAMES = 321
        const val CHUNK_HOP = 28000
        private val cosines = Array(BINS) { k -> FloatArray(FFT) { n -> cos(2 * PI * k * n / FFT).toFloat() } }
        private val sines = Array(BINS) { k -> FloatArray(FFT) { n -> sin(2 * PI * k * n / FFT).toFloat() } }
        private val hamming = FloatArray(FFT) { (0.54 - 0.46 * cos(2 * PI * it / FFT)).toFloat() }

        fun inverse(real: FloatArray, imaginary: FloatArray, control: AudioControl = AudioControl()): FloatArray {
            requireFinite(real, "CMGAN compressed real", FRAMES * BINS)
            requireFinite(imaginary, "CMGAN compressed imaginary", FRAMES * BINS)
            val padded = FloatArray(CHUNK + FFT)
            val weights = FloatArray(padded.size)
            val re = FloatArray(BINS)
            val im = FloatArray(BINS)
            for (t in 0 until FRAMES) {
                control.checkpoint()
                for (k in 0 until BINS) {
                    val r = real[t * BINS + k]
                    val i = imaginary[t * BINS + k]
                    val magnitudeSquared = r * r + i * i
                    // Compressed mag^0.3 -> original: multiply the complex value by |z|^(7/3).
                    val scale = if (magnitudeSquared > 1e-12f) exp(7.0 / 6 * ln(magnitudeSquared.toDouble())).toFloat() else 0f
                    re[k] = r * scale; im[k] = i * scale
                }
                for (n in 0 until FFT) {
                    var sample = re[0]
                    for (k in 1 until BINS - 1) sample += 2f * (re[k] * cosines[k][n] - im[k] * sines[k][n])
                    sample += re[BINS - 1] * cosines[BINS - 1][n]
                    val index = t * HOP + n
                    padded[index] += sample / FFT * hamming[n]
                    weights[index] += hamming[n] * hamming[n]
                }
            }
            return requireFinite(FloatArray(CHUNK) { i ->
                val index = FFT / 2 + i
                if (weights[index] > 1e-8f) padded[index] / weights[index] else 0f
            }, "CMGAN inverse STFT")
        }
    }

    private val runner = compiler.model(modelFile(directory, "cmgan_fp16.tflite"))

    fun enhance(pcm: FloatArray, control: AudioControl): FloatArray {
        require(pcm.size in 1..AudioLimits.MAX_SECONDS * RATE)
        requireFinite(pcm, "CMGAN PCM")
        var energy = 0.0
        for (value in pcm) energy += value.toDouble() * value
        // Avoid amplifying digital silence/noise to unit RMS. Explicit silent output, not fake inference.
        if (energy / pcm.size < 1e-16) {
            control.progress("CMGAN silence", 1, 1)
            return FloatArray(pcm.size)
        }
        val scale = sqrt(pcm.size / (energy + 1e-12)).toFloat()
        val output = FloatArray(pcm.size)
        val weights = FloatArray(pcm.size)
        val windows = AudioDsp.windowCount(pcm.size, CHUNK, CHUNK_HOP)
        for (window in 0 until windows) {
            control.progress("CMGAN", window, windows)
            val start = window * CHUNK_HOP
            val input = AudioDsp.reflectedWindow(pcm, start, CHUNK, FFT / 2, scale)
            runner.writeInput(0, input); runner.run()
            val real = runner.readOutput(0)
            val imag = runner.readOutput(1)
            control.checkpoint()
            val segment = inverse(real, imag, control)
            for (i in 0 until minOf(CHUNK, pcm.size - start)) {
                output[start + i] += segment[i] / scale
                weights[start + i] += 1f
            }
            control.progress("CMGAN", window + 1, windows)
        }
        for (i in output.indices) if (weights[i] > 1f) output[i] /= weights[i]
        control.checkpoint()
        return requireFinite(output, "CMGAN waveform")
    }

    override fun close() = runner.close()
}
