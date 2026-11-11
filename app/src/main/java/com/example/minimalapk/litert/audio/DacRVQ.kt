/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/dac/DacRVQ.kt.
 */
package com.example.minimalapk.litert.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** DAC residual vector quantizer: learned projections, normalized nearest codebook, residual update. */
internal class DacRVQ(bytes: ByteArray) {
    companion object {
        const val BOOKS = 12
        const val DIM = 8
        const val SIZE = 1024
        const val HIDDEN = 1024
        const val FRAMES = 50
        const val WEIGHT_BYTES = BOOKS * (SIZE * DIM + DIM * HIDDEN + DIM + HIDDEN * DIM + HIDDEN) * 4
    }

    private val codebooks = Array(BOOKS) { FloatArray(SIZE * DIM) }
    private val normalized = Array(BOOKS) { FloatArray(SIZE * DIM) }
    private val inputWeights = Array(BOOKS) { FloatArray(DIM * HIDDEN) }
    private val inputBiases = Array(BOOKS) { FloatArray(DIM) }
    private val outputWeights = Array(BOOKS) { FloatArray(HIDDEN * DIM) }
    private val outputBiases = Array(BOOKS) { FloatArray(HIDDEN) }

    init {
        require(bytes.size == WEIGHT_BYTES) { "dac_rvq.bin: ${bytes.size} bytes, expected $WEIGHT_BYTES" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        fun fill(array: FloatArray) { buffer.get(array); requireFinite(array, "DAC RVQ weights") }
        for (i in 0 until BOOKS) {
            fill(codebooks[i]); fill(inputWeights[i]); fill(inputBiases[i]); fill(outputWeights[i]); fill(outputBiases[i])
            for (k in 0 until SIZE) {
                var norm = 0f
                for (d in 0 until DIM) { val v = codebooks[i][k * DIM + d]; norm += v * v }
                norm = sqrt(norm).coerceAtLeast(1e-12f)
                require(norm.isFinite()) { "Invalid DAC codebook norm" }
                for (d in 0 until DIM) normalized[i][k * DIM + d] = codebooks[i][k * DIM + d] / norm
            }
        }
    }

    fun encode(latent: FloatArray, frames: Int = FRAMES, control: AudioControl = AudioControl()): IntArray {
        require(frames in 1..FRAMES)
        requireFinite(latent, "DAC latent", HIDDEN * frames)
        val residual = latent.copyOf()
        val codes = IntArray(BOOKS * frames)
        val projected = FloatArray(DIM)
        for (book in 0 until BOOKS) {
            for (t in 0 until frames) {
                control.checkpoint()
                var norm = 0f
                for (d in 0 until DIM) {
                    var value = inputBiases[book][d]
                    for (c in 0 until HIDDEN) value += inputWeights[book][d * HIDDEN + c] * residual[c * frames + t]
                    projected[d] = value
                    norm += value * value
                }
                norm = sqrt(norm).coerceAtLeast(1e-12f)
                require(norm.isFinite()) { "Invalid DAC projected norm" }
                for (d in 0 until DIM) projected[d] /= norm
                var best = -Float.MAX_VALUE
                var selected = 0
                for (k in 0 until SIZE) {
                    var similarity = 0f
                    for (d in 0 until DIM) similarity += projected[d] * normalized[book][k * DIM + d]
                    if (similarity > best) { best = similarity; selected = k }
                }
                codes[book * frames + t] = selected
                for (c in 0 until HIDDEN) {
                    var value = outputBiases[book][c]
                    for (d in 0 until DIM) value += outputWeights[book][c * DIM + d] * codebooks[book][selected * DIM + d]
                    residual[c * frames + t] -= value
                }
            }
        }
        requireFinite(residual, "DAC residual")
        return codes
    }

    fun decode(codes: IntArray, frames: Int = FRAMES, control: AudioControl = AudioControl()): FloatArray {
        require(frames in 1..FRAMES && codes.size == BOOKS * frames && codes.all { it in 0 until SIZE })
        val latent = FloatArray(HIDDEN * frames)
        for (book in 0 until BOOKS) {
            for (t in 0 until frames) {
                control.checkpoint()
                val code = codes[book * frames + t]
                for (c in 0 until HIDDEN) {
                    var value = outputBiases[book][c]
                    for (d in 0 until DIM) value += outputWeights[book][c * DIM + d] * codebooks[book][code * DIM + d]
                    latent[c * frames + t] += value
                }
            }
        }
        return requireFinite(latent, "DAC quantized latent")
    }
}
