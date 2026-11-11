/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, audio/BatchAudioTasks.kt.
 */
package com.example.minimalapk.litert.audio

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Six offline audio tasks. ASR and TTS have [ZipformerEngine] and [MatchaEngine] respectively.
 * [directory] is the verified AssetCatalog set directory, not its parent. Keep its asset lease until
 * the engine closes (G2P and TIGER may load files lazily). Inputs are at most 30 s.
 * Engines are synchronous and thread-confined. Queue only one request per host; especially TIGER
 * is a long-running three-graph batch operation, not a per-microphone-frame callback.
 */
object AudioTasks {
    val ids: Set<String> = setOf(
        "audio-codec", "audio-classification", "pitch-detection", "music-transcription",
        "audio-source-separation", "speech-enhancement",
    )

    fun create(taskId: String, directory: File, backend: String = "gpu"): AudioTaskEngine = when (taskId) {
        "audio-codec" -> DacTask(directory, backend)
        "audio-classification" -> PannsTask(directory, backend)
        "pitch-detection" -> CrepeTask(directory, backend)
        "music-transcription" -> BasicPitchTask(directory, backend)
        "audio-source-separation" -> TigerTask(directory, backend)
        "speech-enhancement" -> CmganTask(directory, backend)
        else -> throw IllegalArgumentException("Unknown audio task: $taskId")
    }
}

/** Codes are book-major [12,50], each in 0..1023; values are not raw PCM or a serialized .dac file. */
data class DacCodeChunk(
    val inputStartSample: Int,
    val inputSamples: Int,
    val decodedSamples: Int,
    val codes: IntArray,
    val codebooks: Int = 12,
    val frames: Int = 50,
)

private abstract class BatchTask(preferred: String, details: String) : AudioTaskEngine {
    private val thread = AudioThread()
    protected val compiler = AudioCompiler(preferred, details)
    protected abstract val sampleRate: Int
    override val backend: BackendSnapshot get() = compiler.snapshot

    final override fun run(request: AudioTaskRequest): AudioTaskOutput = thread.run {
        val started = System.nanoTime()
        val control = AudioControl(request.isCancelled, request.onProgress)
        val audio = AudioDsp.atRate(request.samples, request.sampleRate, sampleRate, control)
        val output = process(audio, control)
        for (waveform in output.waveforms) {
            require(waveform.sampleRate in AudioLimits.MIN_SAMPLE_RATE..AudioLimits.MAX_SAMPLE_RATE)
            require(waveform.samples.size in 1..waveform.sampleRate * AudioLimits.MAX_SECONDS)
            requireFinite(waveform.samples, "${waveform.name} output")
        }
        control.checkpoint()
        val placement = backend
        output.copy(inferenceMs = elapsedAudioMs(started), metrics = output.metrics + mapOf(
            "backend" to placement.actual,
            "fallbackReason" to placement.fallbackReason,
            "backendDetails" to placement.details,
            "inputSampleRate" to request.sampleRate,
            "processedSampleRate" to sampleRate,
            "inputSeconds" to request.samples.size.toDouble() / request.sampleRate,
        ))
    }

    protected abstract fun process(audio: FloatArray, control: AudioControl): AudioTaskOutput
    protected abstract fun release()
    final override fun close() = thread.close { release() }
}

private fun formatted(value: Double, digits: Int = 2): String = String.format(Locale.US, "%.${digits}f", value)

private fun waveformMetrics(wave: NamedWaveform): Map<String, Any> {
    var energy = 0.0
    var peak = 0f
    for (sample in wave.samples) { energy += sample.toDouble() * sample; peak = maxOf(peak, abs(sample)) }
    return mapOf("name" to wave.name, "sampleRate" to wave.sampleRate, "samples" to wave.samples.size,
        "seconds" to wave.samples.size.toDouble() / wave.sampleRate, "peakAmplitude" to peak,
        "rms" to sqrt(energy / wave.samples.size))
}

private class PannsTask(directory: File, preferred: String) : BatchTask(preferred,
    "CNN14; original float32 log-mel on CPU; Qiuqiang Kong et al., PANNs / AudioSet, CC-BY-4.0") {
    override val sampleRate = PannsMel.RATE
    private val tagger = PannsTagger(directory, compiler)

    override fun process(audio: FloatArray, control: AudioControl): AudioTaskOutput {
        val windows = AudioDsp.windowCount(audio.size, PannsMel.SAMPLES, PannsMel.SAMPLES)
        val probabilities = FloatArray(PannsTagger.CLASSES)
        val perWindow = mutableListOf<Map<String, Any>>()
        var melMs = 0.0
        var modelMs = 0.0
        for (window in 0 until windows) {
            control.progress("PANNs", window, windows)
            val start = window * PannsMel.SAMPLES
            val result = tagger.tag(audio.copyOfRange(start, minOf(audio.size, start + PannsMel.SAMPLES)), control)
            for (i in probabilities.indices) probabilities[i] = maxOf(probabilities[i], result.probabilities[i])
            val best = result.probabilities.indices.maxBy { result.probabilities[it] }
            perWindow += mapOf("startSeconds" to start.toDouble() / sampleRate,
                "label" to tagger.labels[best], "score" to result.probabilities[best])
            melMs += result.melMs; modelMs += result.modelMs
            control.progress("PANNs", window + 1, windows)
        }
        val indices = probabilities.indices.sortedByDescending { probabilities[it] }.take(5)
        val top = indices.map { mapOf("label" to tagger.labels[it], "score" to probabilities[it]) }
        return AudioTaskOutput(
            indices.joinToString("\n") { "${tagger.labels[it]}: ${formatted(probabilities[it] * 100.0)}%" },
            metrics = mapOf("top5" to top, "windows" to perWindow, "probabilities" to probabilities,
                "aggregation" to "maximum across 10-second windows; final window zero padded; multi-label sigmoid",
                "melMs" to melMs, "modelMs" to modelMs,
                "attribution" to "Qiuqiang Kong et al., PANNs CNN14 AudioSet (CC-BY-4.0)",
                "licenseUrl" to "https://creativecommons.org/licenses/by/4.0/"),
            inferenceMs = 0.0,
        )
    }

    override fun release() = tagger.close()
}

private class CrepeTask(directory: File, preferred: String) : BatchTask(preferred,
    "CREPE-full; per-frame normalization and weighted-argmax pitch decode on CPU") {
    override val sampleRate = CrepeDetector.RATE
    private val detector = CrepeDetector(directory, compiler)

    override fun process(audio: FloatArray, control: AudioControl): AudioTaskOutput {
        val hop = 1600 // bounded 10 Hz curve, as in the pinned Zoo batch host
        val frames = (audio.size + hop - 1) / hop
        val curve = ArrayList<Map<String, Any>>(frames)
        for (index in 0 until frames) {
            control.progress("CREPE", index, frames)
            val start = index * hop
            val frame = FloatArray(CrepeDetector.WINDOW)
            audio.copyInto(frame, 0, start, minOf(audio.size, start + frame.size))
            val pitch = detector.detect(frame, control)
            curve += mapOf("timeSeconds" to start.toDouble() / sampleRate, "hz" to pitch.hz,
                "confidence" to pitch.confidence, "note" to if (pitch.note.isEmpty()) "" else "${pitch.note}${pitch.octave}",
                "cents" to pitch.cents)
            control.progress("CREPE", index + 1, frames)
        }
        return AudioTaskOutput(
            curve.take(20).joinToString("\n") {
                "${formatted(it.getValue("timeSeconds") as Double)} s: ${formatted((it.getValue("hz") as Float).toDouble())} Hz · ${it["note"]}"
            },
            metrics = mapOf("curve" to curve, "frameCount" to frames, "hopSeconds" to 0.1,
                "f0Hz" to curve.map { it["hz"] }, "confidence" to curve.map { it["confidence"] },
                "pitchScope" to "Monophonic f0; low confidence is not a reliable note"), inferenceMs = 0.0,
        )
    }

    override fun release() = detector.close()
}

private class DacTask(directory: File, preferred: String) : BatchTask(preferred,
    "DAC16k convolutional encoder/decoder; residual vector quantization on CPU") {
    override val sampleRate = 16000
    private val codec = DacCodec(directory, compiler)

    override fun process(audio: FloatArray, control: AudioControl): AudioTaskOutput {
        val windows = AudioDsp.windowCount(audio.size, DacCodec.SAMPLES, DacCodec.SAMPLES)
        val decodedChunks = ArrayList<FloatArray>(windows)
        val codeChunks = ArrayList<DacCodeChunk>(windows)
        var signal = 0.0
        var noise = 0.0
        for (window in 0 until windows) {
            control.progress("DAC encode / RVQ / decode", window, windows)
            val start = window * DacCodec.SAMPLES
            val count = minOf(DacCodec.SAMPLES, audio.size - start)
            val input = FloatArray(DacCodec.SAMPLES)
            audio.copyInto(input, 0, start, start + count)
            val result = codec.roundTrip(input, control)
            val decoded = result.audio.copyOf(minOf(count, result.audio.size))
            decodedChunks += decoded
            codeChunks += DacCodeChunk(start, count, decoded.size, result.codes)
            for (i in decoded.indices) {
                signal += input[i].toDouble() * input[i]
                val difference = input[i].toDouble() - decoded[i]
                noise += difference * difference
            }
            control.progress("DAC encode / RVQ / decode", window + 1, windows)
        }
        val decoded = FloatArray(decodedChunks.sumOf { it.size })
        var offset = 0
        for (chunk in decodedChunks) { chunk.copyInto(decoded, offset); offset += chunk.size }
        val waves = listOf(NamedWaveform("Original", audio, sampleRate), NamedWaveform("Decoded", decoded, sampleRate))
        val codeCount = codeChunks.sumOf { it.codes.size }
        val snr = if (signal > 0.0 && noise > 0.0) 10 * log10(signal / noise) else null
        return AudioTaskOutput("DAC16k: ${formatted(decoded.size / 16000.0)} s decoded · $codeCount codes", waves,
            mapOf("waveforms" to waves.map(::waveformMetrics), "codeCount" to codeCount, "codeChunks" to codeChunks,
                "codeBits" to 10, "nominalCodecBitsPerSecond" to 6000, "snrDb" to snr,
                "snrAlignment" to "Per 16000-sample input chunk, first min(input,output) samples; no lag compensation",
                "decoderLengthNote" to "Pinned decoder emits 15992 samples per full second; concatenated without invented tail PCM"), 0.0)
    }

    override fun release() = codec.close()
}

private class CmganTask(directory: File, preferred: String) : BatchTask(preferred,
    "CMGAN graph; whole-utterance RMS, compressed complex inverse STFT and overlap averaging on CPU") {
    override val sampleRate = CmganEnhancer.RATE
    private val enhancer = CmganEnhancer(directory, compiler)

    override fun process(audio: FloatArray, control: AudioControl): AudioTaskOutput {
        val enhanced = enhancer.enhance(audio, control)
        val waves = listOf(NamedWaveform("Before", audio, sampleRate), NamedWaveform("Enhanced", enhanced, sampleRate))
        return AudioTaskOutput("CMGAN: ${formatted(enhanced.size / 16000.0)} s enhanced", waves,
            mapOf("waveforms" to waves.map(::waveformMetrics)), 0.0)
    }

    override fun release() = enhancer.close()
}

private class TigerTask(directory: File, preferred: String) : BatchTask(preferred,
    "TIGER-DnR; one stem graph resident at a time; timing includes compilation and host inverse STFT") {
    override val sampleRate = TigerSeparator.RATE
    private val separator = TigerSeparator(directory, compiler)

    override fun process(audio: FloatArray, control: AudioControl): AudioTaskOutput {
        val stems = separator.separate(audio, control)
        val waves = stems.mapIndexed { i, samples -> NamedWaveform(TigerSeparator.STEMS[i], samples, sampleRate) }
        return AudioTaskOutput(waves.joinToString("\n") { "${it.name}: ${formatted(it.samples.size / 44100.0)} s" },
            waves, mapOf("waveforms" to waves.map(::waveformMetrics), "compileInsideRun" to true), 0.0)
    }

    override fun release() = separator.close()
}

private class BasicPitchTask(directory: File, preferred: String) : BatchTask(preferred,
    "Basic Pitch conv-CQT graph; overlap stitching and onset-triggered note decode on CPU") {
    override val sampleRate = BasicPitchTranscriber.RATE
    private val transcriber = BasicPitchTranscriber(directory, compiler)

    override fun process(audio: FloatArray, control: AudioControl): AudioTaskOutput {
        val (note, onset) = transcriber.posteriorgrams(audio, control)
        val duration = audio.size.toDouble() / sampleRate
        val events = BasicPitchTranscriber.decode(note, onset, control = control)
            .filter { it.startSec < duration }.map { it.copy(endSec = minOf(it.endSec, duration)) }
        val values = events.map { mapOf("pitch" to it.midi, "onsetSeconds" to it.startSec,
            "endSeconds" to it.endSec, "amplitude" to it.amplitude) }
        return AudioTaskOutput("${events.size} notes\n" + events.take(10).joinToString("\n") {
            "MIDI ${it.midi}: ${formatted(it.startSec)}–${formatted(it.endSec)} s"
        }, metrics = mapOf("noteCount" to events.size, "notes" to values, "first10Notes" to values.take(10),
            "posteriorFrames" to note.size), inferenceMs = 0.0)
    }

    override fun release() = transcriber.close()
}
