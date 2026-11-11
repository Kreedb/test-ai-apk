/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

// Adapted from typed_decisions/TextDecision.kt in google-ai-edge/litert-samples@
// f9f0e000630e9cdd6d263c4cd80495e4231a2dc8. Changes: bounded requests, validated keys/resources,
// diagnostics and thread/lifetime contract. Source and component notices: ATTRIBUTION.md.
package com.example.minimalapk.litert.text

import java.io.File
import java.util.concurrent.CancellationException

/** One question about [text]; options are (key, description), in display order. */
data class TextDecisionRequest(
    val text: String,
    val question: String,
    val options: List<Pair<String, String>>,
) {
    init {
        validate()
    }

    internal fun validate() {
        require(text.isNotBlank()) { "Enter the text to ask about." }
        require(question.isNotBlank()) { "Enter a question." }
        require(options.size in 2..255) { "Write 2–255 options as key: description." }
        require(options.all { it.first.isNotBlank() }) { "Each option needs a nonblank key." }
        require(options.map { it.first }.distinct().size == options.size) {
            "Each option needs its own key."
        }
        validateTextBudget(listOf(text, question) + options.flatMap { listOf(it.first, it.second) })
    }

    /** Label-based models read the description, falling back to the key. Laya reads both. */
    fun optionText(index: Int): String = options[index].second.ifBlank { options[index].first }

    companion object {
        /** Splits at the first colon only; a row without one is a key alone. */
        fun parseOptions(rows: List<String>): List<Pair<String, String>> =
            rows.filter { it.isNotBlank() }.map { row ->
                val colon = row.indexOf(':')
                if (colon < 0) row.trim() to ""
                else row.substring(0, colon).trim() to row.substring(colon + 1).trim()
            }.also { parsed ->
                require(parsed.all { it.first.isNotEmpty() }) {
                    "Each option needs a key before the colon."
                }
            }
    }
}

/** Probabilities in request order. Time includes tokenization, lookup, inference and readback. */
data class TextDecisionResult(
    val probabilities: List<Pair<String, Float>>,
    val answerKey: String,
    val ms: Double,
    val backend: String,
    val window: Int,
    val fallbackReason: String? = null,
    val details: String = "",
)

/**
 * Construct, decide and close on the same worker thread. These fixed encoder graphs have no
 * recurrent/KV state: every call writes all inputs, including padding and unused routing slots.
 * The host must discard results belonging to a cancelled/obsolete UI generation; a native graph
 * invocation cannot be interrupted halfway through. No image or language-model prompt is implied.
 */
interface TextDecisionEngine : AutoCloseable {
    fun decide(request: TextDecisionRequest): TextDecisionResult
    override fun close()
}

object TextDecisionTasks {
    val ids: Set<String> = linkedSetOf(
        "text-decision-laya-multilingual",
        "text-decision-julia-1",
        "text-decision-gliclass-edge",
        "text-decision-open-decision",
        "text-decision-gliner-decide",
    )

    /**
     * [directory] is the installed task directory, with flattened AssetCatalog filenames.
     * CPU uses four threads; GPU uses FP32 and reports any compile-time CPU fallback. `mixed`
     * is accepted as a GPU preference, but the result reports the actual accelerator.
     * Asset hashes/revisions are the installer's responsibility; construction checks file sizes,
     * tokenizer contracts, metadata and graph signature shapes before exposing an engine.
     */
    fun create(taskId: String, directory: File, backend: String): TextDecisionEngine {
        require(taskId in ids) { "Unknown text task: $taskId" }
        require(backend in setOf("cpu", "gpu", "mixed")) {
            "$taskId supports cpu or gpu (explicit FP32), not '$backend'."
        }
        DecisionAssets.check(taskId, directory)
        return when (taskId) {
            "text-decision-laya-multilingual" -> LayaDecider(directory, backend)
            "text-decision-julia-1" -> JuliaDecider(directory, backend)
            "text-decision-gliclass-edge" -> GliclassDecider(directory, backend)
            "text-decision-open-decision" -> DebertaDecider(directory, backend)
            else -> GlinerDecider(directory, backend)
        }
    }
}

/** Bound work before tokenization; the much smaller model-specific token limit is checked later. */
internal fun validateTextBudget(parts: List<String>) {
    val count = parts.sumOf { it.length.toLong() }
    require(count <= 32_768) {
        "Request has $count UTF-16 characters; the host limit is 32768 before tokenization."
    }
    parts.forEach { text ->
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char.isHighSurrogate()) {
                require(index < text.length && text[index++].isLowSurrogate()) {
                    "Request contains an unpaired UTF-16 surrogate."
                }
            } else require(!char.isLowSurrogate()) { "Request contains an unpaired UTF-16 surrogate." }
        }
    }
}

internal class DecisionLifetime {
    private val owner = Thread.currentThread()
    private var closed = false

    fun checkOpen() {
        check(Thread.currentThread() === owner) { "Text engine must stay on its creating worker thread." }
        check(!closed) { "Text engine is closed." }
        if (owner.isInterrupted) throw CancellationException("Text decision was cancelled.")
    }

    fun closeOnce(release: () -> Unit) {
        check(Thread.currentThread() === owner) { "Close the text engine on its creating worker thread." }
        if (closed) return
        closed = true
        release()
    }
}

/** Pinned sizes from litert-models.tsv; this does not replace the catalog's SHA-256 verification. */
internal object DecisionAssets {
    private val files = mapOf(
        "text-decision-laya-multilingual" to linkedMapOf(
            "laya_ml_s256_embeds_wfp16.tflite" to 250_889_040L,
            "laya_ml_act_head_fp32.tflite" to 795_816L,
            "laya_ml_calibration.json" to 9_156L,
            "tokenizer.json" to 34_363_188L,
            "token_embeddings_fp16.bin" to 393_216_000L,
            "token_embeddings.json" to 801L,
        ),
        "text-decision-julia-1" to linkedMapOf(
            "julia1_s512_fp32.tflite" to 185_095_348L,
            "julia1_token_table_fp16.bin" to 196_608_000L,
            "tokenizer.json" to 34_363_188L,
        ),
        "text-decision-gliclass-edge" to linkedMapOf(
            "gliclass_edge_v3_s128_fp32.tflite" to 53_703_480L,
            "tok_embeddings_fp16.bin" to 38_684_160L,
            "tokenizer.json" to 3_583_595L,
        ),
        "text-decision-open-decision" to linkedMapOf(
            "deberta_v3_large_decision_s256_wfp16.tflite" to 712_780_432L,
            "word_embeddings_fp16.bin" to 262_348_800L,
            "tokenizer.json" to 8_657_170L,
        ),
        "text-decision-gliner-decide" to linkedMapOf(
            "gliner25_decide_s128_wfp16.tflite" to 660_383_872L,
            "word_embeddings_fp16.bin" to 262_166_528L,
            "tokenizer.json" to 8_333_952L,
        ),
    )

    fun check(taskId: String, directory: File) {
        val problems = files.getValue(taskId).mapNotNull { (name, bytes) ->
            val file = File(directory, name)
            when {
                !file.isFile -> "$name is missing"
                file.length() != bytes -> "$name has ${file.length()} bytes; expected $bytes"
                else -> null
            }
        }
        require(problems.isEmpty()) { "Incomplete $taskId resource set: ${problems.joinToString("; ")}" }
    }
}
