/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/panns/{AudioTagger,MelSpectrogram}.kt.
 * PANNs CNN14 weights: Qiuqiang Kong et al., CC-BY-4.0, https://github.com/qiuqiangkong/audioset_tagging_cnn
 */
package com.example.minimalapk.litert.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/** torchlibrosa power STFT -> Slaney mel -> 10 log10, kept float32 on CPU (fp16 power overflows). */
internal class PannsMel(bytes: ByteArray) {
    companion object {
        const val RATE = 32000
        const val FFT = 1024
        const val HOP = 320
        const val MELS = 64
        const val BINS = 513
        const val SAMPLES = 320000
        const val FRAMES = 1001
    }
    private val basis = audioFloats(bytes, MELS * BINS)
    private val hann = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / FFT)).toFloat() }
    private val fft = AudioFft(FFT)

    init { require(basis.all { it >= 0f }) { "PANNs mel basis contains negative weights" } }

    fun compute(audio: FloatArray, control: AudioControl = AudioControl()): FloatArray {
        require(audio.isNotEmpty() && audio.size <= SAMPLES)
        requireFinite(audio, "PANNs PCM")
        val x = audio.copyOf(SAMPLES)
        val padded = AudioDsp.reflectedWindow(x, 0, SAMPLES, FFT / 2)
        val result = FloatArray(FRAMES * MELS)
        val real = FloatArray(FFT)
        val imag = FloatArray(FFT)
        val power = FloatArray(BINS)
        for (t in 0 until FRAMES) {
            if (t % 8 == 0) control.checkpoint()
            for (i in 0 until FFT) real[i] = padded[t * HOP + i] * hann[i]
            imag.fill(0f)
            fft.transform(real, imag)
            for (k in 0 until BINS) power[k] = real[k] * real[k] + imag[k] * imag[k]
            for (m in 0 until MELS) {
                var value = 0f
                for (k in 0 until BINS) value += basis[m * BINS + k] * power[k]
                result[t * MELS + m] = (10 * log10(maxOf(value, 1e-10f).toDouble())).toFloat()
            }
        }
        return requireFinite(result, "PANNs log mel")
    }
}

internal class PannsTagger(directory: File, compiler: AudioCompiler) : AutoCloseable {
    companion object { const val CLASSES = 527 }
    val labels = readAudioBytes(modelFile(directory, "audioset_labels.txt"), 128 * 1024)
        .toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList().also {
            require(it.size == CLASSES && it.all { label -> label.length <= 512 }) { "PANNs requires 527 AudioSet labels" }
        }
    private val mel = PannsMel(readAudioBytes(modelFile(directory, "mel_basis.bin"), PannsMel.MELS * PannsMel.BINS * 4,
        PannsMel.MELS * PannsMel.BINS * 4))
    private val runner = compiler.model(modelFile(directory, "cnn14_audioset_fp16.tflite"))

    data class Result(val probabilities: FloatArray, val melMs: Double, val modelMs: Double)

    fun tag(audio: FloatArray, control: AudioControl): Result {
        val start = System.nanoTime()
        val input = mel.compute(audio, control)
        val frontendMs = elapsedAudioMs(start)
        control.checkpoint()
        val modelStart = System.nanoTime()
        runner.writeInput(0, input); runner.run()
        val probabilities = runner.readOutput(0)
        control.checkpoint()
        requireFinite(probabilities, "PANNs sigmoid probabilities", CLASSES)
        require(probabilities.all { it in -0.001f..1.001f }) { "PANNs did not return probabilities" }
        return Result(probabilities, frontendMs, elapsedAudioMs(modelStart))
    }

    override fun close() = runner.close()
}
