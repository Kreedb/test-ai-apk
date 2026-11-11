/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/crepe/PitchDetector.kt.
 */
package com.example.minimalapk.litert.audio

import java.io.File
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class CrepeDetector(directory: File, compiler: AudioCompiler) : AutoCloseable {
    companion object {
        const val RATE = 16000
        const val WINDOW = 1024
        const val BINS = 360
        private val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun normalize(frame: FloatArray): FloatArray {
            requireFinite(frame, "CREPE PCM", WINDOW)
            var mean = 0f
            for (v in frame) mean += v
            mean /= WINDOW
            var variance = 0f
            for (v in frame) { val centered = v - mean; variance += centered * centered }
            val std = maxOf(sqrt(variance / WINDOW), 1e-10f)
            return FloatArray(WINDOW) { (frame[it] - mean) / std }
        }

        fun decode(activations: FloatArray): Pitch {
            requireFinite(activations, "CREPE activations", BINS)
            require(activations.all { it in -0.001f..1.001f }) { "Invalid CREPE activation probability" }
            val peak = activations.indices.maxBy { activations[it] }
            var numerator = 0.0
            var denominator = 0.0
            for (bin in maxOf(0, peak - 4)..minOf(BINS - 1, peak + 4)) {
                val value = maxOf(0f, activations[bin]).toDouble()
                numerator += value * bin
                denominator += value
            }
            if (denominator <= 1e-12) return Pitch(0f, 0f, "", 0, 0)
            val cents = 20 * numerator / denominator + 1997.3794084376191
            val hz = (10 * 2.0.pow(cents / 1200)).toFloat()
            val midi = 69 + 12 * log2(hz / 440.0)
            val nearest = midi.roundToInt()
            return Pitch(hz, activations[peak].coerceIn(0f, 1f), NOTES[Math.floorMod(nearest, 12)],
                nearest / 12 - 1, ((midi - nearest) * 100).roundToInt())
        }
    }

    data class Pitch(val hz: Float, val confidence: Float, val note: String, val octave: Int, val cents: Int)
    private val runner = compiler.model(modelFile(directory, "crepe_full_fp16.tflite"))

    fun detect(frame: FloatArray, control: AudioControl): Pitch {
        val normalized = normalize(frame)
        control.checkpoint()
        runner.writeInput(0, normalized); runner.run()
        val result = runner.readOutput(0)
        control.checkpoint()
        // Silence has no fundamental frequency, even if the network assigns a nonzero prior bin.
        val pitch = decode(result)
        return if (normalized.all { kotlin.math.abs(it) < 1e-8f }) Pitch(0f, 0f, "", 0, 0) else pitch
    }

    override fun close() = runner.close()
}
