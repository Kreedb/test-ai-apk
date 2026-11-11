/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/matcha/MatchaSynthesizer.kt.
 */
package com.example.minimalapk.litert.audio

import com.example.minimalapk.litert.CompiledModelRunner
import java.io.File
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** Text encoder -> host duration expansion -> Euler CFM (CPU) -> HiFi-GAN vocoder. */
internal class MatchaSynthesizer(modelDir: File, compiler: AudioCompiler) : AutoCloseable {
    companion object {
        const val MAX_TEXT = 256
        const val MAX_MEL = 512
        const val FEATURES = 80
        const val CHANNELS = 192
        const val HOP = 256
        const val SAMPLE_RATE = 22050
        const val VOCAB = 178
        const val DEFAULT_STEPS = 10
        private const val LENGTH_SCALE = 0.95f
        private const val MEL_MEAN = -5.536622f
        private const val MEL_STD = 2.116101f

        fun sinPosEmb(time: Float): FloatArray {
            require(time.isFinite() && time in 0f..1f)
            val result = FloatArray(160)
            val k = -ln(10000.0) / 79
            for (i in 0 until 80) {
                val angle = 1000f * time * exp(i * k).toFloat()
                result[i] = sin(angle); result[80 + i] = cos(angle)
            }
            return result
        }

        fun intersperse(phonemes: IntArray): Pair<IntArray, FloatArray> {
            require(phonemes.isNotEmpty() && phonemes.size * 2L + 1 <= MAX_TEXT)
            require(phonemes.all { it in 1 until VOCAB }) { "Invalid Matcha phoneme id" }
            val ids = IntArray(MAX_TEXT)
            phonemes.forEachIndexed { index, id -> ids[2 * index + 1] = id }
            val mask = FloatArray(MAX_TEXT) { if (it < phonemes.size * 2 + 1) 1f else 0f }
            return ids to mask
        }

        data class Expansion(val mean: FloatArray, val mask: FloatArray, val frames: Int)

        /** ceil(exp(logw)) THEN length_scale, matching the pinned graph's fractional cumulative path. */
        fun expandDurations(mu: FloatArray, logw: FloatArray, textMask: FloatArray): Expansion {
            requireFinite(mu, "Matcha encoder mean", FEATURES * MAX_TEXT)
            requireFinite(logw, "Matcha durations", MAX_TEXT)
            require(textMask.size == MAX_TEXT && textMask.all { it == 0f || it == 1f })
            val cumulative = FloatArray(MAX_TEXT)
            var total = 0f
            for (i in 0 until MAX_TEXT) {
                // Skip masked tokens before exponentiation, avoiding 0 * infinity in corrupt output.
                val duration = if (textMask[i] == 0f) 0f else ceil(exp(logw[i])) * LENGTH_SCALE
                require(duration.isFinite() && duration <= MAX_MEL + 1f) { "Invalid Matcha duration" }
                total += duration
                cumulative[i] = total
            }
            require(total > 0f) { "Matcha predicted no speech duration" }
            val length = maxOf(1, total.toInt())
            require(total.isFinite() && length <= MAX_MEL) {
                "Predicted speech exceeds ${MAX_MEL * HOP / SAMPLE_RATE.toDouble()} seconds; split the phrase"
            }
            val mean = FloatArray(FEATURES * MAX_MEL)
            var token = 0
            for (f in 0 until length) {
                while (token < MAX_TEXT - 1 && cumulative[token] <= f) token++
                for (c in 0 until FEATURES) mean[c * MAX_MEL + f] = mu[c * MAX_TEXT + token]
            }
            return Expansion(mean, FloatArray(MAX_MEL) { if (it < length) 1f else 0f }, length)
        }

        internal fun eulerStep(state: FloatArray, velocity: FloatArray, dt: Float, frames: Int) {
            requireFinite(velocity, "Matcha CFM velocity", FEATURES * MAX_MEL)
            require(state.size == velocity.size && dt.isFinite() && dt > 0f && frames in 1..MAX_MEL)
            for (c in 0 until FEATURES) for (f in 0 until MAX_MEL) {
                val i = c * MAX_MEL + f
                state[i] = if (f < frames) state[i] + dt * velocity[i] else 0f
            }
            requireFinite(state, "Matcha CFM state")
        }
    }

    private val embedding = audioFloats(readAudioBytes(modelFile(modelDir, "emb.bin"), VOCAB * CHANNELS * 4,
        VOCAB * CHANNELS * 4), VOCAB * CHANNELS)
    private val textEncoder: CompiledModelRunner
    private val decoder: CompiledModelRunner
    private val vocoder: CompiledModelRunner

    init {
        val created = mutableListOf<CompiledModelRunner>()
        try {
            textEncoder = compiler.model(modelFile(modelDir, "matcha_textenc_fp16.tflite")).also { created += it }
            // Deliberate CPU placement: upstream found full-graph GPU transformer mis-fusion
            // (velocity correlation ~0.006). Successful compilation is NOT evidence of correctness.
            decoder = compiler.model(modelFile(modelDir, "matcha_decoder_fp16.tflite"), cpuOnly = true).also { created += it }
            vocoder = compiler.model(modelFile(modelDir, "matcha_vocoder_fp16.tflite")).also { created += it }
        } catch (failure: Throwable) {
            created.asReversed().forEach { runCatching { it.close() } }
            throw failure
        }
    }

    fun synthesize(phonemes: IntArray, steps: Int, seed: Long?, control: AudioControl): FloatArray {
        require(steps in 1..32) { "Matcha CFM steps must be between 1 and 32" }
        val (ids, textMask) = intersperse(phonemes)
        val input = FloatArray(MAX_TEXT * CHANNELS)
        for (i in ids.indices) embedding.copyInto(input, i * CHANNELS, ids[i] * CHANNELS, (ids[i] + 1) * CHANNELS)
        control.progress("Matcha text encoder", 0, 1)
        textEncoder.writeInput(0, input); textEncoder.writeInput(1, textMask)
        textEncoder.run()
        val mu = textEncoder.readOutput(0)
        val logw = textEncoder.readOutput(1)
        control.checkpoint()
        val expanded = expandDurations(mu, logw, textMask)
        control.progress("Matcha text encoder", 1, 1)
        val random = if (seed == null) Random() else Random(seed)
        val state = FloatArray(FEATURES * MAX_MEL)
        for (c in 0 until FEATURES) for (f in 0 until expanded.frames) state[c * MAX_MEL + f] = random.nextGaussian().toFloat()
        val dt = 1f / steps
        var time = 0f
        for (step in 0 until steps) {
            control.progress("Matcha CFM (CPU)", step, steps)
            decoder.writeInput(0, state); decoder.writeInput(1, expanded.mean)
            decoder.writeInput(2, sinPosEmb(time)); decoder.writeInput(3, expanded.mask)
            decoder.run()
            val velocity = decoder.readOutput(0)
            control.checkpoint()
            eulerStep(state, velocity, dt, expanded.frames)
            time += dt
        }
        control.progress("Matcha CFM (CPU)", steps, steps)
        val mel = FloatArray(FEATURES * MAX_MEL)
        for (c in 0 until FEATURES) for (f in 0 until expanded.frames) {
            val i = c * MAX_MEL + f
            mel[i] = state[i] * MEL_STD + MEL_MEAN
        }
        control.progress("Matcha vocoder", 0, 1)
        vocoder.writeInput(0, mel); vocoder.run()
        val waveform = vocoder.readOutput(0)
        control.checkpoint()
        requireFinite(waveform, "Matcha waveform", MAX_MEL * HOP)
        val audio = FloatArray(expanded.frames * HOP) { waveform[it].coerceIn(-1f, 1f) }
        control.progress("Matcha vocoder", 1, 1)
        return audio
    }

    override fun close() = closeAudioModels(vocoder, decoder, textEncoder)
}
