/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/dac/DacCodec.kt.
 */
package com.example.minimalapk.litert.audio

import com.example.minimalapk.litert.CompiledModelRunner
import java.io.File

/** DAC16k learned encoder -> 12-book RVQ codes -> learned deconvolution decoder. */
internal class DacCodec(directory: File, compiler: AudioCompiler) : AutoCloseable {
    companion object {
        const val SAMPLES = 16000
        const val DECODED_SAMPLES = 15992
    }
    private val rvq = DacRVQ(readAudioBytes(modelFile(directory, "dac_rvq.bin"), DacRVQ.WEIGHT_BYTES, DacRVQ.WEIGHT_BYTES))
    private val encoder: CompiledModelRunner
    private val decoder: CompiledModelRunner

    init {
        var first: CompiledModelRunner? = null
        try {
            encoder = compiler.model(modelFile(directory, "dac_16khz_encoder_fp16.tflite")).also { first = it }
            decoder = compiler.model(modelFile(directory, "dac_16khz_deconly_zs_fp16.tflite"))
        } catch (failure: Throwable) {
            runCatching { first?.close() }
            throw failure
        }
    }

    data class Result(val audio: FloatArray, val codes: IntArray)

    fun roundTrip(audio: FloatArray, control: AudioControl): Result {
        requireFinite(audio, "DAC PCM", SAMPLES)
        control.checkpoint()
        encoder.writeInput(0, audio); encoder.run()
        val latent = encoder.readOutput(0)
        control.checkpoint()
        val codes = rvq.encode(latent, control = control)
        val quantized = rvq.decode(codes, control = control)
        control.checkpoint()
        decoder.writeInput(0, quantized); decoder.run()
        val result = decoder.readOutput(0)
        control.checkpoint()
        return Result(requireFinite(result, "DAC decoded waveform", DECODED_SAMPLES), codes)
    }

    override fun close() = closeAudioModels(decoder, encoder)
}
