/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/tiger/{TigerSeparator,Istft}.kt.
 */
package com.example.minimalapk.litert.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.cos

internal object TigerIstft {
    const val WINDOW = 2048
    const val HOP = 512
    const val BINS = 1025
    private val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2 * PI * it / WINDOW)).toFloat() }

    /** Frequency-major one-sided spectrum; select one source plane without copying 8 MB. */
    fun reconstruct(
        real: FloatArray,
        imaginary: FloatArray,
        frames: Int,
        samples: Int,
        sourceOffset: Int = 0,
        control: AudioControl = AudioControl(),
    ): FloatArray {
        require(frames in 1..TigerSeparator.FRAMES && samples in 1..TigerSeparator.CHUNK)
        require(sourceOffset >= 0 && real.size == imaginary.size &&
            sourceOffset.toLong() + frames.toLong() * BINS <= real.size)
        val paddedLength = (frames - 1) * HOP + WINDOW
        require(WINDOW / 2 + samples <= paddedLength)
        val output = FloatArray(paddedLength)
        val weights = FloatArray(paddedLength)
        val re = FloatArray(WINDOW)
        val im = FloatArray(WINDOW)
        val fft = AudioFft(WINDOW)
        for (t in 0 until frames) {
            if (t % 8 == 0) control.checkpoint()
            for (f in 0 until BINS) {
                re[f] = real[sourceOffset + f * frames + t]
                im[f] = imaginary[sourceOffset + f * frames + t]
            }
            // The imaginary parts of DC and Nyquist do not contribute to a real inverse DFT.
            im[0] = 0f; im[WINDOW / 2] = 0f
            for (f in 1 until WINDOW / 2) { re[WINDOW - f] = re[f]; im[WINDOW - f] = -im[f] }
            fft.transform(re, im, inverse = true)
            for (n in 0 until WINDOW) {
                output[t * HOP + n] += re[n] * hann[n]
                weights[t * HOP + n] += hann[n] * hann[n]
            }
        }
        val result = FloatArray(samples) { i ->
            val index = WINDOW / 2 + i
            if (weights[index] > 1e-8f) output[index] / weights[index] else 0f
        }
        return requireFinite(result, "TIGER inverse STFT")
    }
}

/** Three fixed DnR graphs, one resident at a time; each owns exactly one of the returned stems. */
internal class TigerSeparator(private val directory: File, private val compiler: AudioCompiler) : AutoCloseable {
    companion object {
        const val RATE = 44100
        const val FRAMES = 1040
        const val CHUNK = (FRAMES - 1) * TigerIstft.HOP
        const val HOP = RATE * 10
        val STEMS = listOf("dialog", "effect", "music")
        private val SOURCES = intArrayOf(2, 1, 0)
    }

    init { STEMS.forEach { modelFile(directory, "tiger_${it}_fp16.tflite") } }

    fun separate(pcm: FloatArray, control: AudioControl): List<FloatArray> {
        require(pcm.size in 1..AudioLimits.MAX_SECONDS * RATE)
        requireFinite(pcm, "TIGER PCM")
        val windows = AudioDsp.windowCount(pcm.size, CHUNK, HOP)
        val stems = ArrayList<FloatArray>(3)
        for ((stemIndex, stem) in STEMS.withIndex()) {
            control.progress("TIGER $stem compile", 0, 1)
            // use/finally closes on this very thread even when a progress callback throws/cancels.
            compiler.model(modelFile(directory, "tiger_${stem}_fp16.tflite")).use { model ->
                control.progress("TIGER $stem compile", 1, 1)
                val output = FloatArray(pcm.size)
                val weight = FloatArray(pcm.size)
                for (window in 0 until windows) {
                    control.progress(stem, window, windows)
                    val start = window * HOP
                    val input = AudioDsp.reflectedWindow(pcm, start, CHUNK, TigerIstft.WINDOW / 2)
                    model.writeInput(0, input); model.run()
                    val real = model.readOutput(0)
                    val imag = model.readOutput(1)
                    control.checkpoint()
                    val plane = TigerIstft.BINS * FRAMES
                    requireFinite(real, "TIGER real spectrogram", 3 * plane)
                    requireFinite(imag, "TIGER imaginary spectrogram", 3 * plane)
                    val segment = TigerIstft.reconstruct(real, imag, FRAMES, CHUNK, SOURCES[stemIndex] * plane, control)
                    val count = minOf(CHUNK, pcm.size - start)
                    for (i in 0 until count) {
                        if (i % 8192 == 0) control.checkpoint()
                        output[start + i] += segment[i]
                        weight[start + i] += 1f
                    }
                    control.progress(stem, window + 1, windows)
                }
                for (i in output.indices) if (weight[i] > 1f) output[i] /= weight[i]
                stems += requireFinite(output, "TIGER $stem waveform")
            }
        }
        control.checkpoint()
        return stems
    }

    override fun close() = Unit // Every per-stem graph is scoped to separate().
}
