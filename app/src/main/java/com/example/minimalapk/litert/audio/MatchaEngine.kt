/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, audio/MatchaEngine.kt.
 */
package com.example.minimalapk.litert.audio

import java.io.File

data class AudioSpeechResult(
    val samples: FloatArray,
    val sampleRate: Int,
    val inferenceMs: Double,
    val backend: String,
    val fallbackReason: String?,
    val backendDetails: String,
    val language: String = "en-US",
)

/** English LJSpeech voice. One short phrase per call; returns PCM for the host's playback worker. */
class MatchaEngine(modelDir: File, backend: String = "gpu") : AutoCloseable {
    private val thread = AudioThread()
    private val compiler = AudioCompiler(backend, "English LJSpeech; G2P and CFM decoder CPU by design")
    private val g2p = MatchaG2P(modelDir, compiler)
    private val synthesizer = try { MatchaSynthesizer(modelDir, compiler) } catch (e: Throwable) {
        runCatching { g2p.close() }
        throw e
    }

    val backendSnapshot: BackendSnapshot get() = compiler.snapshot

    fun synthesize(
        text: String,
        control: AudioControl = AudioControl(),
        seed: Long? = null,
        steps: Int = MatchaSynthesizer.DEFAULT_STEPS,
    ): AudioSpeechResult = thread.run {
        val start = System.nanoTime()
        control.progress("Matcha G2P", 0, 1)
        val ids = g2p.phonemize(text, control)
        control.progress("Matcha G2P", 1, 1)
        val samples = synthesizer.synthesize(ids, steps, seed, control)
        control.checkpoint()
        val placement = compiler.snapshot
        AudioSpeechResult(samples, MatchaSynthesizer.SAMPLE_RATE, elapsedAudioMs(start),
            placement.actual, placement.fallbackReason, placement.details)
    }

    override fun close() = thread.close { closeAudioModels(synthesizer, g2p) }
}
