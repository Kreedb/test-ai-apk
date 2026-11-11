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

import java.util.concurrent.CancellationException

data class AudioStageProgress(val stem: String, val chunk: Int, val totalChunks: Int)

/** Mono float PCM. The caller retains the array and must not mutate it during [AudioTaskEngine.run]. */
data class AudioTaskRequest(
    val samples: FloatArray,
    val sampleRate: Int,
    val onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
    val isCancelled: () -> Boolean = { false },
) {
    // Keep the upstream AudioTaskRequest(pcm, rate) { stage, current, total -> ... } spelling.
    constructor(samples: FloatArray, sampleRate: Int, onProgress: (String, Int, Int) -> Unit) :
        this(samples, sampleRate, onProgress, { false })
}

data class NamedWaveform(val name: String, val samples: FloatArray, val sampleRate: Int)

data class BackendSnapshot(
    val actual: String,
    val fallbackReason: String? = null,
    val details: String = "",
)

data class AudioTaskOutput(
    val summary: String,
    val waveforms: List<NamedWaveform> = emptyList(),
    val metrics: Map<String, Any?> = emptyMap(),
    val inferenceMs: Double,
)

/** Construct, run, and close on one dedicated worker thread (including GPU fallback and readback). */
interface AudioTaskEngine : AutoCloseable {
    val backend: BackendSnapshot
    fun run(request: AudioTaskRequest): AudioTaskOutput
}

/**
 * Cooperative cancellation; the callback may read an atomic generation/token owned by the host.
 * A native invocation cannot be interrupted. Cancellation is checked before invocation, after
 * readback, inside DSP loops, and before returning a result. Callbacks run on the inference worker.
 * Stop the host's input subscription immediately, then close the engine on its worker after run exits.
 */
class AudioControl(
    val isCancelled: () -> Boolean = { false },
    val onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
) {
    fun checkpoint() {
        if (Thread.currentThread().isInterrupted || isCancelled()) {
            throw CancellationException("Audio inference cancelled")
        }
    }

    internal fun progress(stage: String, completed: Int, total: Int) {
        checkpoint()
        onProgress(stage, completed, total)
        checkpoint()
    }
}

/** Local bounds, independent of downloader limits. Long recordings must be explicitly segmented. */
object AudioLimits {
    const val MAX_SECONDS = 30
    const val MIN_SAMPLE_RATE = 8_000
    const val MAX_SAMPLE_RATE = 192_000
    const val MAX_TEXT_CHARS = 1_000

    internal fun validatePcm(samples: FloatArray, rate: Int, control: AudioControl) {
        require(rate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "Unsupported sample rate: $rate" }
        require(samples.isNotEmpty() && samples.size.toLong() <= rate.toLong() * MAX_SECONDS) {
            "Supply between one sample and $MAX_SECONDS seconds of mono PCM; split longer audio."
        }
        for (i in samples.indices) {
            if (i % 4096 == 0) control.checkpoint()
            require(samples[i].isFinite()) { "PCM contains a non-finite sample at $i" }
        }
    }
}

/** Reject re-entry from progress callbacks as well as closing a live GPU invocation off-thread. */
internal class AudioThread {
    private val owner = Thread.currentThread()
    private var closed = false
    private var running = false

    private fun checkThread() = check(Thread.currentThread() === owner) {
        "Create, run, and close the audio engine on the same worker thread"
    }

    fun <T> run(block: () -> T): T {
        checkThread()
        check(!closed) { "Audio engine is closed" }
        check(!running) { "Audio engine is already running" }
        running = true
        return try { block() } finally { running = false }
    }

    fun close(block: () -> Unit) {
        checkThread()
        check(!running) { "Close the audio engine after inference has exited" }
        if (closed) return
        closed = true
        block()
    }
}
