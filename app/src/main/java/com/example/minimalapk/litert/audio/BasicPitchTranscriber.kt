/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/basicpitch/Transcriber.kt.
 */
package com.example.minimalapk.litert.audio

import java.io.File

/** Spotify Basic Pitch: graph contains conv-CQT, host stitches overlapping note/onset posteriorgrams. */
internal class BasicPitchTranscriber(directory: File, compiler: AudioCompiler) : AutoCloseable {
    companion object {
        const val RATE = 22050
        const val SAMPLES = 43844
        const val FRAMES = 172
        const val NOTES = 88
        const val FFT_HOP = 256
        const val OVERLAP = 30 * FFT_HOP
        const val WINDOW_HOP = SAMPLES - OVERLAP
        const val FRAME_SECONDS = FFT_HOP.toDouble() / RATE

        /** At a joint the later window owns its center; never average away narrow onset peaks. */
        internal fun keptFrames(window: Int, windows: Int, totalFrames: Int): IntRange {
            require(window in 0 until windows && totalFrames > 0)
            val base = window * WINDOW_HOP / FFT_HOP
            val from = if (window == 0) 0 else OVERLAP / FFT_HOP / 2
            val until = if (window == windows - 1) FRAMES else
                (window + 1) * WINDOW_HOP / FFT_HOP + OVERLAP / FFT_HOP / 2 - base
            return from until minOf(until, totalFrames - base)
        }

        fun decode(
            note: Array<FloatArray>,
            onset: Array<FloatArray>,
            onsetThreshold: Float = 0.5f,
            frameThreshold: Float = 0.3f,
            minimumFrames: Int = 3,
            control: AudioControl = AudioControl(),
        ): List<Note> {
            require(note.size == onset.size && note.size <= AudioLimits.MAX_SECONDS * RATE / FFT_HOP + 1)
            require(onsetThreshold in 0f..1f && frameThreshold in 0f..1f && minimumFrames > 0)
            val events = ArrayList<Note>()
            val active = IntArray(NOTES) { -1 }
            val peak = FloatArray(NOTES)
            fun finish(key: Int, frame: Int) {
                if (active[key] >= 0 && frame - active[key] >= minimumFrames) {
                    events += Note(active[key] * FRAME_SECONDS, frame * FRAME_SECONDS, key + 21, peak[key])
                }
                active[key] = -1
            }
            for (t in note.indices) {
                if (t % 32 == 0) control.checkpoint()
                requireFinite(note[t], "Basic Pitch note frame", NOTES)
                requireFinite(onset[t], "Basic Pitch onset frame", NOTES)
                for (key in 0 until NOTES) {
                    val attack = onset[t][key] >= onsetThreshold && (t == 0 || onset[t - 1][key] < onsetThreshold)
                    if (active[key] >= 0 && (note[t][key] < frameThreshold || attack)) finish(key, t)
                    if (active[key] < 0 && onset[t][key] >= onsetThreshold && note[t][key] >= frameThreshold) {
                        active[key] = t
                        peak[key] = note[t][key]
                    } else if (active[key] >= 0) peak[key] = maxOf(peak[key], note[t][key])
                }
            }
            for (key in 0 until NOTES) finish(key, note.size)
            return events.sortedWith(compareBy<Note> { it.startSec }.thenBy { it.midi })
        }
    }

    data class Note(val startSec: Double, val endSec: Double, val midi: Int, val amplitude: Float)
    private val runner = compiler.model(modelFile(directory, "basicpitch.tflite"))

    fun posteriorgrams(pcm: FloatArray, control: AudioControl): Pair<Array<FloatArray>, Array<FloatArray>> {
        require(pcm.size in 1..AudioLimits.MAX_SECONDS * RATE)
        requireFinite(pcm, "Basic Pitch PCM")
        val windows = AudioDsp.windowCount(pcm.size, SAMPLES, WINDOW_HOP)
        val totalFrames = pcm.size / FFT_HOP + 1
        val note = Array(totalFrames) { FloatArray(NOTES) }
        val onset = Array(totalFrames) { FloatArray(NOTES) }
        val input = FloatArray(SAMPLES)
        for (window in 0 until windows) {
            control.progress("Basic Pitch", window, windows)
            val start = window * WINDOW_HOP
            input.fill(0f)
            pcm.copyInto(input, 0, start, minOf(pcm.size, start + SAMPLES))
            runner.writeInput(0, input); runner.run()
            // Pinned converter order: contour[172,264], note[172,88], onset[172,88].
            val notes = runner.readOutput(1)
            val onsets = runner.readOutput(2)
            control.checkpoint()
            requireFinite(notes, "Basic Pitch note posterior", FRAMES * NOTES)
            requireFinite(onsets, "Basic Pitch onset posterior", FRAMES * NOTES)
            require(notes.all { it in -0.001f..1.001f } && onsets.all { it in -0.001f..1.001f })
            val base = start / FFT_HOP
            for (t in keptFrames(window, windows, totalFrames)) {
                notes.copyInto(note[base + t], 0, t * NOTES, (t + 1) * NOTES)
                onsets.copyInto(onset[base + t], 0, t * NOTES, (t + 1) * NOTES)
            }
            control.progress("Basic Pitch", window + 1, windows)
        }
        return note to onset
    }

    override fun close() = runner.close()
}
