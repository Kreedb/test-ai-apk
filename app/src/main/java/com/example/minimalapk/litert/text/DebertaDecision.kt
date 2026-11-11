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

// Ports DecisionInputs/Question/DecisionDecoder/DebertaDecider from pinned typed_decisions/deberta/.
// Host origin: litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT@
// 7a276235b795e8ad3ae7ac6a9f237daa2098863a/android/sample/app/src/main/java/com/opendecision/
// (Apache-2.0), Kotoba Labs Inc. (Mithril) decision host. See ATTRIBUTION.md.
// Changes: one-choice-question S256 adapter, explicit clipping diagnostics, reset/lifecycle checks.
package com.example.minimalapk.litert.text

import java.io.File

internal data class DecisionSpan(val start: Int, val end: Int)
internal data class DebertaSequence(
    val ids: IntArray,
    val question: DecisionSpan,
    val options: List<DecisionSpan>,
    val originalStateTokens: Int,
    val retainedStateTokens: Int,
)

/**
 * [CLS] [STATE] state[:256] [Q] instructions ([OPT] description)+ [SEP].
 * Unlike the marker models, routing averages the question/option TEXT spans, excluding markers.
 */
internal class DebertaInputs(private val tokenizer: UnigramTokenizer) {
    fun encode(request: TextDecisionRequest): DebertaSequence {
        request.validate()
        require(request.options.size <= 128) { "Open-Decision has 128 option slots." }
        val descriptions = request.options.indices.map(request::optionText)
        require(descriptions.distinct().size == descriptions.size) { "Open-Decision option descriptions repeat." }
        val ids = arrayListOf(tokenizer.tokenId("[CLS]"), tokenizer.tokenId("[STATE]"))
        val text = tokenizer.encode(request.text)
        ids.addAll(text.take(256))
        val instructions = tokenizer.encode(request.question)
        val question = DecisionSpan(ids.size + 1, ids.size + 1 + instructions.size)
        ids.add(tokenizer.tokenId("[Q]"))
        ids.addAll(instructions.asList())
        val options = descriptions.map { description ->
            val tokens = tokenizer.encode(description)
            val span = DecisionSpan(ids.size + 1, ids.size + 1 + tokens.size)
            ids.add(tokenizer.tokenId("[OPT]"))
            ids.addAll(tokens.asList())
            span
        }
        ids.add(tokenizer.tokenId("[SEP]"))
        require(ids.size <= 256) {
            "Open-Decision needs ${ids.size} tokens after the author's 256-token state cap; this graph holds " +
                "256 TOTAL tokens including question/options. Shorten text, question or options."
        }
        return DebertaSequence(ids.toIntArray(), question, options, text.size, minOf(text.size, 256))
    }

    companion object {
        /** Unused rows and padding are zeroed anew on each call. */
        fun routing(question: DecisionSpan, options: List<DecisionSpan>): Pair<FloatArray, FloatArray> {
            require(options.size in 2..128 && (listOf(question) + options).all { it.start in 0..it.end && it.end <= 256 }) {
                "Invalid Open-Decision question/option token span."
            }
            val q = FloatArray(128 * 256)
            val o = FloatArray(128 * 256)
            for ((slot, option) in options.withIndex()) {
                if (question.end > question.start) {
                    val weight = 1f / (question.end - question.start)
                    for (token in question.start until question.end) q[slot * 256 + token] = weight
                }
                if (option.end > option.start) {
                    val weight = 1f / (option.end - option.start)
                    for (token in option.start until option.end) o[slot * 256 + token] = weight
                }
            }
            return q to o
        }
    }
}

internal class DebertaDecider(directory: File, backend: String) : TextDecisionEngine {
    private val lifetime = DecisionLifetime()
    private val tokenizer = UnigramTokenizer(File(directory, "tokenizer.json"), UnigramFamily.OPEN_DECISION)
    private val inputs = DebertaInputs(tokenizer)
    private val table = MappedEmbeddings(File(directory, "word_embeddings_fp16.bin"), 128100, 1024)
    private val loaded = try {
        compileDecisionBackend(backend) { accelerator ->
            DecisionGraph.create(
                File(directory, "deberta_v3_large_decision_s256_wfp16.tflite"), accelerator,
                linkedMapOf("inputs_embeds" to listOf(1, 256, 1024), "attention_mask" to listOf(1, 256),
                    "q_routing" to listOf(1, 128, 256), "o_routing" to listOf(1, 128, 256)),
                mapOf("logits" to listOf(1, 1, 1, 128)),
            )
        }
    } catch (failure: Throwable) { table.close(); throw failure }

    override fun decide(request: TextDecisionRequest): TextDecisionResult {
        lifetime.checkOpen()
        val started = System.nanoTime()
        val sequence = inputs.encode(request)
        val (q, o) = DebertaInputs.routing(sequence.question, sequence.options)
        val graph = loaded.runner
        graph.write("inputs_embeds", table.gather(sequence.ids, 256, tokenizer.padId))
        graph.write("attention_mask", FloatArray(256) { if (it < sequence.ids.size) 1f else 0f })
        graph.write("q_routing", q)
        graph.write("o_routing", o)
        graph.run()
        val p = DecisionMath.softmax64(graph.read("logits").copyOf(request.options.size), 1.05)
        lifetime.checkOpen()
        return TextDecisionResult(
            request.options.mapIndexed { i, (key, _) -> key to p[i].toFloat() },
            request.options[p.indices.maxBy { p[it] }].first,
            DecisionGraph.milliseconds(started), loaded.backend, 256, loaded.fallbackReason,
            "${sequence.ids.size} of 256 total tokens; text retained ${sequence.retainedStateTokens}/" +
                "${sequence.originalStateTokens} tokens. Open-Decision averages question/option text spans " +
                "and reads descriptions (or keys when blank); float64 softmax at the author's temperature 1.05.",
        )
    }

    override fun close() = lifetime.closeOnce {
        try { loaded.runner.close() } finally { table.close() }
    }
}
