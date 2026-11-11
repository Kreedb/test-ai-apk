/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, audio/ZipformerEngine.kt and models/zipformer/ZipformerAsr.kt.
 */
package com.example.minimalapk.litert.audio

import android.content.Context
import java.io.File

data class AudioTextResult(
    val text: String,
    val inferenceMs: Double,
    val fbankMs: Long,
    val backend: String,
    val fallbackReason: String?,
    val windows: Int = 1,
    val language: String = "en",
    val modelFile: String = ZipformerEngine.SMALL_MODEL,
)

/**
 * English LibriSpeech CR-CTC. Fixed ~16 s windows with 2 s overlap, not native streaming.
 * The current catalog installs SMALL_MODEL. MEDIUM_MODEL uses the same five-input protocol but
 * needs its own verified installation; supplying its name explicitly prevents silently choosing it.
 * Construct/transcribe/close on one worker. Context is not retained and no microphone is opened.
 */
class ZipformerEngine(
    modelDir: File,
    preferredBackend: String = "gpu",
    private val modelName: String = SMALL_MODEL,
) : AutoCloseable {
    constructor(@Suppress("UNUSED_PARAMETER") context: Context, modelDir: File, preferredBackend: String = "gpu") :
        this(modelDir, preferredBackend)

    companion object {
        const val SMALL_MODEL = "zipformer_ctc_small_fp16.tflite"
        const val MEDIUM_MODEL = "zipformer_ctc_fp16.tflite"
        const val SAMPLE_RATE = 16000
        const val MAX_WINDOW_SAMPLES = ZipformerFbank.MAX_SAMPLES
        private const val WINDOW_HOP = MAX_WINDOW_SAMPLES - 2 * SAMPLE_RATE
        internal const val CLASSES = 500
        internal const val OUTPUT_FRAMES = 398
        private val BIAS_LENGTHS = intArrayOf(796, 398, 199, 100)
        private val DOWNSAMPLING = intArrayOf(1, 2, 4, 8)
        private const val OUTPUT_HOP_SAMPLES = 640 // 25 Hz CTC

        internal fun parseTokens(text: String): Map<Int, String> {
            val result = linkedMapOf<Int, String>()
            for (line in text.lineSequence().filter { it.isNotBlank() }) {
                val cut = line.lastIndexOf(' ')
                require(cut > 0) { "Malformed Zipformer token row" }
                val id = line.substring(cut + 1).trim().toInt()
                val piece = line.substring(0, cut)
                // icefall tokens.txt includes FST disambiguation symbols #0/#1 after the 500
                // network classes. They never appear in logits but are valid catalog resources.
                require((id in 0 until CLASSES || (id in CLASSES..CLASSES + 15 && piece == "#${id - CLASSES}")) &&
                    result.put(id, piece) == null) {
                    "Invalid/duplicate Zipformer token id: $id"
                }
            }
            require((0 until CLASSES).all { it in result }) { "Zipformer needs all 500 BPE pieces" }
            return result
        }

        internal data class CtcWord(val text: String, val firstFrame: Int, val lastFrame: Int)

        internal fun decode(logits: FloatArray, validOut: Int, pieces: Map<Int, String>): String =
            decodeWords(logits, validOut, pieces).joinToString(" ") { it.text }

        /** Keep emission frame bounds so an actually repeated phrase outside the overlap survives. */
        internal fun decodeWords(logits: FloatArray, validOut: Int, pieces: Map<Int, String>): List<CtcWord> {
            require(validOut in 0..OUTPUT_FRAMES && logits.size >= validOut * CLASSES)
            requireFinite(logits, "Zipformer logits")
            val words = mutableListOf<CtcWord>()
            val word = StringBuilder()
            var firstFrame = 0
            var lastFrame = 0
            fun finishWord() {
                if (word.isNotEmpty()) words += CtcWord(word.toString(), firstFrame, lastFrame)
                word.setLength(0)
            }
            var previous = -1
            for (t in 0 until validOut) {
                var arg = 0
                for (c in 1 until CLASSES) if (logits[t * CLASSES + c] > logits[t * CLASSES + arg]) arg = c
                if (arg != 0 && arg != previous) {
                    for (character in requireNotNull(pieces[arg])) {
                        if (character == '▁' || character.isWhitespace()) finishWord() else {
                            if (word.isEmpty()) firstFrame = t
                            word.append(character)
                            lastFrame = t
                        }
                    }
                } else if (arg != 0 && word.isNotEmpty()) lastFrame = t
                previous = arg // a blank resets repetition, so A blank A is two pieces
            }
            finishWord()
            return words
        }

        /** Bounded exact word overlap for adjacent independent windows; no state crosses requests. */
        internal fun mergeOverlap(previous: String, next: String, maxWords: Int = 32): String {
            if (previous.isBlank()) return next.trim()
            if (next.isBlank()) return previous.trim()
            val a = previous.trim().split(Regex("\\s+"))
            val b = next.trim().split(Regex("\\s+"))
            for (n in minOf(32, a.size, b.size, maxWords) downTo 1) {
                if ((0 until n).all { a[a.size - n + it].equals(b[it], ignoreCase = true) }) {
                    return (a + b.drop(n)).joinToString(" ")
                }
            }
            return "$previous $next"
        }

        internal fun mergeWindow(
            transcript: String,
            previous: List<CtcWord>,
            next: List<CtcWord>,
            previousStart: Int,
            nextStart: Int,
        ): String {
            val nextText = next.joinToString(" ") { it.text }
            // Fixed-window context can move emissions a few frames. Only words within the actual
            // two-second shared audio (+200 ms edge tolerance) are eligible for text deduplication.
            val tolerance = SAMPLE_RATE / 5
            val tail = previous.count {
                previousStart + it.lastFrame * OUTPUT_HOP_SAMPLES >= nextStart - tolerance
            }
            val head = next.count {
                nextStart + it.firstFrame * OUTPUT_HOP_SAMPLES <= previousStart + MAX_WINDOW_SAMPLES + tolerance
            }
            return mergeOverlap(transcript, nextText, minOf(tail, head))
        }

        internal fun attentionBias(valid50: Int, index: Int): FloatArray =
            FloatArray(BIAS_LENGTHS[index]) { if (it * DOWNSAMPLING[index] < valid50) 0f else -1000f }
    }

    private val thread = AudioThread()
    private val compiler = AudioCompiler(preferredBackend, "English; Kaldi fbank and greedy BPE-CTC on CPU")
    private val fbank = ZipformerFbank()
    private val pieces = parseTokens(readAudioBytes(modelFile(modelDir, "tokens.txt"), 128 * 1024).toString(Charsets.UTF_8))
    private val runner = run {
        require(modelName == SMALL_MODEL || modelName == MEDIUM_MODEL) { "Unsupported Zipformer graph: $modelName" }
        compiler.model(modelFile(modelDir, modelName))
    }
    private val slots: IntArray

    init {
        try {
            val inputSizes = runner.inputBuffers.map { it.readFloat().size }
            require(inputSizes.size == 5 && runner.outputBuffers.size == 1) { "Invalid Zipformer tensor count" }
            slots = (listOf(1600 * 80) + BIAS_LENGTHS.toList()).map { size ->
                inputSizes.indexOf(size).also { require(it >= 0) { "Missing Zipformer tensor of size $size" } }
            }.toIntArray()
            require(runner.readOutput(0).size == OUTPUT_FRAMES * CLASSES) { "Invalid Zipformer logits layout" }
        } catch (failure: Throwable) {
            runCatching { runner.close() }
            throw failure
        }
    }

    val backend: BackendSnapshot get() = compiler.snapshot

    fun transcribe(audio: FloatArray): AudioTextResult = transcribe(AudioTaskRequest(audio, SAMPLE_RATE))

    fun transcribe(request: AudioTaskRequest): AudioTextResult = thread.run {
        val control = AudioControl(request.isCancelled, request.onProgress)
        val started = System.nanoTime()
        val audio = AudioDsp.atRate(request.samples, request.sampleRate, SAMPLE_RATE, control)
        require(audio.size >= ZipformerFbank.WIN) { "Record at least 25 ms before transcribing" }
        val windows = AudioDsp.windowCount(audio.size, MAX_WINDOW_SAMPLES, WINDOW_HOP)
        var text = ""
        var frontendNanos = 0L
        var previousWords = emptyList<CtcWord>()
        var previousStart = 0
        for (window in 0 until windows) {
            control.progress("Zipformer", window, windows)
            val start = window * WINDOW_HOP
            val clip = audio.copyOfRange(start, minOf(audio.size, start + MAX_WINDOW_SAMPLES))
            val beforeFbank = System.nanoTime()
            val mel = fbank.compute(clip, control)
            val input = FloatArray(1600 * 80) { ZipformerFbank.LOG_PAD }
            mel.forEachIndexed { t, row -> row.copyInto(input, t * 80) }
            val valid50 = ((mel.size - 7) / 2).coerceAtLeast(0)
            frontendNanos += System.nanoTime() - beforeFbank
            control.checkpoint()
            runner.writeInput(slots[0], input)
            for (i in BIAS_LENGTHS.indices) runner.writeInput(slots[i + 1], attentionBias(valid50, i))
            runner.run()
            val logits = runner.readOutput(0)
            control.checkpoint()
            requireFinite(logits, "Zipformer logits", OUTPUT_FRAMES * CLASSES)
            val words = decodeWords(logits, minOf((valid50 + 1) / 2, OUTPUT_FRAMES), pieces)
            text = if (window == 0) words.joinToString(" ") { it.text }
                else mergeWindow(text, previousWords, words, previousStart, start)
            previousWords = words
            previousStart = start
            control.progress("Zipformer", window + 1, windows)
        }
        control.checkpoint()
        AudioTextResult(text, elapsedAudioMs(started), frontendNanos / 1_000_000,
            backend.actual, backend.fallbackReason, windows, modelFile = modelName)
    }

    override fun close() = thread.close { runner.close() }
}
