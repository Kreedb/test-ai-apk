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

// Ports GliclassInputs/GliclassDecoder/GliclassDecider from pinned typed_decisions/gliclass/.
// Host origin: litert-community/GLiClass-Edge-v3.0-LiteRT@88c90950587eb951974c094eef91afa0fe3552c0
// android/sample/app/src/main/java/com/gliclass/ (Apache-2.0); Knowledgator gliclass 0.1.20.
// Changes: single-label S128 adapter, reject structural marker injection/duplicate labels,
// shared mmap table, named shapes and reset/lifecycle checks. See ATTRIBUTION.md.
package com.example.minimalapk.litert.text

import java.io.File

internal data class GliclassSequence(val ids: IntArray, val labelPositions: IntArray, val labels: List<String>)

internal class GliclassInputs(private val tokenizer: GliclassTokenizer) {
    init {
        require(tokenizer.vocabularySize == 50370 && tokenizer.clsId == 50281 && tokenizer.sepId == 50282 &&
            tokenizer.padId == 50283 && tokenizer.tokenId("<<LABEL>>") == 50368 && tokenizer.tokenId("<<SEP>>") == 50369) {
            "GLiClass tokenizer special ids do not match the [50370,384] table/graph."
        }
    }

    fun encode(request: TextDecisionRequest): GliclassSequence {
        request.validate()
        require(request.options.size <= 25) { "GLiClass-Edge has 25 label slots." }
        val labels = request.options.indices.map(request::optionText)
        require(labels.distinct().size == labels.size) { "GLiClass-Edge needs distinct rendered option descriptions." }
        require((listOf(request.text, request.question) + labels).none { "<<LABEL>>" in it || "<<SEP>>" in it }) {
            "GLiClass text/question/options contain reserved <<LABEL>> or <<SEP>> markers."
        }
        val ids = tokenizer.encode(linearize(request.text, labels, request.question + " "))
        require(ids.size <= 128) {
            "GLiClass-Edge request needs ${ids.size} tokens; this graph holds 128. Nothing was truncated."
        }
        val positions = ids.indices.filter { ids[it] == 50368 }.toIntArray()
        require(positions.size == labels.size) { "GLiClass label-marker count does not match labels." }
        return GliclassSequence(ids, positions, labels)
    }

    companion object {
        /** The official pipeline inserts NO separator between prompt and text. Adapter adds one space. */
        fun linearize(text: String, labels: List<String>, prompt: String): String = buildString {
            labels.forEach { append("<<LABEL>>").append(it) }
            append("<<SEP>>").append(prompt).append(text)
        }

        fun routing(positions: IntArray, window: Int = 128): FloatArray {
            require(window == 128 && positions.size <= 25 && positions.all { it in 0 until window }) {
                "Invalid GLiClass label routing."
            }
            return FloatArray(25 * window).also { out ->
                positions.forEachIndexed { index, position -> out[index * window + position] = 1f }
            }
        }
    }
}

internal class GliclassDecider(directory: File, backend: String) : TextDecisionEngine {
    private val lifetime = DecisionLifetime()
    private val tokenizer = GliclassTokenizer(File(directory, "tokenizer.json"))
    private val inputs = GliclassInputs(tokenizer)
    private val table = MappedEmbeddings(File(directory, "tok_embeddings_fp16.bin"), 50370, 384)
    private val loaded = try {
        compileDecisionBackend(backend) { accelerator ->
            DecisionGraph.create(
                File(directory, "gliclass_edge_v3_s128_fp32.tflite"), accelerator,
                linkedMapOf("inputs_embeds" to listOf(1, 128, 384), "attention_mask" to listOf(1, 128),
                    "label_routing" to listOf(1, 25, 128)),
                mapOf("logits" to listOf(1, 1, 1, 25)),
            )
        }
    } catch (failure: Throwable) { table.close(); throw failure }

    override fun decide(request: TextDecisionRequest): TextDecisionResult {
        lifetime.checkOpen()
        val started = System.nanoTime()
        val sequence = inputs.encode(request)
        val graph = loaded.runner
        graph.write("inputs_embeds", table.gather(sequence.ids, 128, tokenizer.padId))
        graph.write("attention_mask", FloatArray(128) { if (it < sequence.ids.size) 1f else 0f })
        graph.write("label_routing", GliclassInputs.routing(sequence.labelPositions))
        graph.run()
        val p = DecisionMath.softmax32(graph.read("logits").copyOf(sequence.labels.size))
        lifetime.checkOpen()
        return TextDecisionResult(
            request.options.mapIndexed { i, (key, _) -> key to p[i] }, request.options[p.indices.maxBy { p[it] }].first,
            DecisionGraph.milliseconds(started), loaded.backend, 128, loaded.fallbackReason,
            "${sequence.ids.size} of 128 tokens; nothing truncated. English GLiClass-Edge reads option " +
                "descriptions as labels; the question plus one space precedes the text. Single-label float32 softmax.",
        )
    }

    override fun close() = lifetime.closeOnce {
        try { loaded.runner.close() } finally { table.close() }
    }
}
