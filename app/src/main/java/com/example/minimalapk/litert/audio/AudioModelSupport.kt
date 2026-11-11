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

import com.example.minimalapk.litert.CompiledModelRunner
import com.google.ai.edge.litert.Accelerator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** Tracks actual placement per graph; CPU-only host work is explicit, never reported as GPU. */
internal class AudioCompiler(private val preferred: String, private val details: String = "") {
    private val placements = linkedMapOf<String, String>()
    private val failures = linkedMapOf<String, String>()

    init {
        require(preferred in setOf("cpu", "gpu", "mixed")) { "Audio backend must be cpu, gpu or mixed" }
    }

    val snapshot: BackendSnapshot
        get() = BackendSnapshot(
            when {
                placements.isEmpty() -> "Not compiled"
                placements.values.all { it == "CPU" } -> "CPU"
                placements.values.all { it == "GPU" } -> "GPU"
                else -> "GPU + CPU"
            },
            failures.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}: ${it.value}" },
            listOf(details, placements.entries.joinToString { "${it.key}=${it.value}" })
                .filter { it.isNotEmpty() }.joinToString("; "),
        )

    fun <T> create(name: String, cpuOnly: Boolean = false, factory: (Accelerator) -> T): T {
        if (preferred == "cpu" || cpuOnly || name in failures) {
            return factory(Accelerator.CPU).also { placements[name] = "CPU" }
        }
        return try {
            factory(Accelerator.GPU).also { placements[name] = "GPU" }
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is InterruptedException ||
                Thread.currentThread().isInterrupted) throw failure
            failures[name] = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
            factory(Accelerator.CPU).also { placements[name] = "CPU" }
        }
    }

    fun model(file: File, cpuOnly: Boolean = false): CompiledModelRunner =
        create(file.name, cpuOnly) { CompiledModelRunner.fromFile(file.absolutePath, it) }
}

internal fun modelFile(directory: File, name: String): File = File(directory, name).also {
    require(it.isFile && it.length() > 0) { "Missing audio model resource: $name in $directory" }
}

internal fun readAudioBytes(file: File, maxBytes: Int, exactBytes: Int? = null): ByteArray {
    require(file.isFile && file.length() in 1..maxBytes.toLong()) { "Invalid resource size: ${file.name}" }
    if (exactBytes != null) require(file.length() == exactBytes.toLong()) {
        "${file.name}: expected $exactBytes bytes, found ${file.length()}"
    }
    // Bound the actual read too, in case the file changes between stat and read.
    return file.inputStream().use { input ->
        readAudioStream(input, maxBytes).also {
            if (exactBytes != null) require(it.size == exactBytes) { "Resource changed: ${file.name}" }
        }
    }
}

internal fun readAudioStream(input: java.io.InputStream, maxBytes: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream(minOf(maxBytes, 65536))
    val buffer = ByteArray(8192)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        require(result.size().toLong() + n <= maxBytes) { "Audio resource exceeds $maxBytes bytes" }
        result.write(buffer, 0, n)
    }
    return result.toByteArray()
}

internal fun audioFloats(bytes: ByteArray, expected: Int? = null): FloatArray {
    require(bytes.size % 4 == 0 && (expected == null || bytes.size / 4 == expected)) {
        "Invalid float32 resource length"
    }
    val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(view.remaining()).also { view.get(it); requireFinite(it, "float32 resource") }
}

internal fun requireFinite(values: FloatArray, label: String, size: Int? = null): FloatArray {
    require(size == null || values.size == size) { "$label: ${values.size} elements, expected $size" }
    require(values.all { it.isFinite() }) { "$label contains non-finite values" }
    return values
}

/** Close every graph even if an earlier close throws. All callers execute on their owning worker. */
internal fun closeAudioModels(vararg models: AutoCloseable?) {
    var failure: Throwable? = null
    for (model in models) {
        try { model?.close() } catch (e: Throwable) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
    }
    failure?.let { throw it }
}

internal fun elapsedAudioMs(start: Long): Double = (System.nanoTime() - start) / 1_000_000.0
