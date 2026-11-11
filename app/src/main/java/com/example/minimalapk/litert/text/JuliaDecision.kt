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

// Ports JuliaRequest/JuliaDecoder/JuliaDecider from pinned upstream typed_decisions/julia/.
// Host origin: litert-community/Julia-1-LiteRT@8f36857c56e891c023060586759c6cdc8baf6b3e
// android/app/src/main/java/com/julia1/ (Apache-2.0), Supersonic Labs julia/data.py and typed.py.
// Changes: choice-only adapter, strict diagnostics, named shape checks and lifecycle/reset checks.
package com.example.minimalapk.litert.text

import java.io.File
import kotlin.math.exp

internal data class JuliaSequence(val ids: IntArray, val markers: IntArray)

/** Julia strict encoding: no Laya squeeze/truncation, no key prefixes, no temperature calibration. */
internal class JuliaSequenceBuilder(private val encode: (String) -> IntArray) {
    fun build(request: TextDecisionRequest): JuliaSequence {
        request.validate()
        require(request.options.size in 2..20) { "Julia-1 supports 2–20 options." }
        val options = request.options.indices.map(request::optionText)
        require((listOf(request.text, request.question) + options).none { "<mask>" in it }) {
            "Julia-1 request contains the reserved <mask> marker."
        }
        val head = encode("choice question: ${request.question}")
        val optionIds = options.map { encode(" $it") }
        require(optionIds.all { it.size <= 48 }) { "Julia-1 option exceeds its 48-token contract; shorten the option." }
        // min(512, S512-5) = 507, as the author's strict encoder.
        val budget = 507 - optionIds.sumOf { it.size + 1 }
        if (budget < 16) {
            val perOption = maxOf(4, (507 - 16) / optionIds.size)
            require(optionIds.none { it.size + 1 > perOption }) { "Julia-1 options exceed the head budget." }
        }
        require(head.size <= budget) { "Julia-1 question and options exceed the 507-token head budget." }
        val ids = arrayListOf(2)
        ids.addAll(head.asList())
        ids.add(1)
        val markers = IntArray(optionIds.size)
        optionIds.forEachIndexed { index, tokens ->
            markers[index] = ids.size
            ids.add(4)
            ids.addAll(tokens.asList())
        }
        ids.add(1)
        val text = encode(request.text)
        val needed = ids.size + text.size + 1
        require(needed <= 512) { "Julia-1 request needs $needed tokens; its fixed window is 512. Nothing was truncated." }
        ids.addAll(text.asList())
        ids.add(1)
        return JuliaSequence(ids.toIntArray(), markers)
    }
}

/** Julia and Open-Decision each specify float64 host softmax; GLiClass/GLiNER specify float32. */
internal object DecisionMath {
    fun softmax64(logits: FloatArray, temperature: Double = 1.0): DoubleArray {
        require(logits.isNotEmpty() && logits.all { it.isFinite() }) { "Decision logits must be finite and nonempty." }
        require(temperature.isFinite() && temperature > 0) { "Decision temperature must be positive and finite." }
        val scaled = DoubleArray(logits.size) { logits[it].toDouble() / temperature }
        val max = scaled.max()
        val exponents = DoubleArray(logits.size) { exp(scaled[it] - max) }
        var sum = 0.0
        for (value in exponents) sum += value
        return DoubleArray(logits.size) { exponents[it] / sum }
    }

    fun softmax32(logits: FloatArray): FloatArray {
        require(logits.isNotEmpty() && logits.all { it.isFinite() }) { "Decision logits must be finite and nonempty." }
        val max = logits.max()
        val out = FloatArray(logits.size) { exp((logits[it] - max).toDouble()).toFloat() }
        var sum = 0f
        for (value in out) sum += value
        val inverse = 1f / sum
        for (i in out.indices) out[i] *= inverse
        return out
    }
}

internal class JuliaDecider(directory: File, backend: String) : TextDecisionEngine {
    private val lifetime = DecisionLifetime()
    private val tokenizer = GemmaTokenizer(File(directory, "tokenizer.json"))
    private val table = MappedEmbeddings(File(directory, "julia1_token_table_fp16.bin"), 256000, 384)
    private val loaded = try {
        compileDecisionBackend(backend) { accelerator ->
            DecisionGraph.create(
                File(directory, "julia1_s512_fp32.tflite"), accelerator,
                linkedMapOf("attention_mask" to listOf(1, 512), "inputs_embeds" to listOf(1, 512, 384),
                    "qtype_onehot" to listOf(1, 3)),
                mapOf("token_logits" to listOf(1, 512)),
            )
        }
    } catch (failure: Throwable) { table.close(); throw failure }

    override fun decide(request: TextDecisionRequest): TextDecisionResult {
        lifetime.checkOpen()
        val started = System.nanoTime()
        val sequence = JuliaSequenceBuilder(tokenizer::encode).build(request)
        val graph = loaded.runner
        graph.write("inputs_embeds", table.gather(sequence.ids, 512))
        graph.write("attention_mask", FloatArray(512) { if (it < sequence.ids.size) 1f else 0f })
        graph.write("qtype_onehot", floatArrayOf(1f, 0f, 0f))
        graph.run()
        val tokens = graph.read("token_logits")
        val p = DecisionMath.softmax64(FloatArray(sequence.markers.size) { tokens[sequence.markers[it]] })
        lifetime.checkOpen()
        return TextDecisionResult(
            request.options.mapIndexed { i, (key, _) -> key to p[i].toFloat() },
            request.options[p.indices.maxBy { p[it] }].first,
            DecisionGraph.milliseconds(started), loaded.backend, 512, loaded.fallbackReason,
            "${sequence.ids.size} of 512 tokens; strict encoding, nothing truncated. " +
                "Julia-1 reads option descriptions (or keys when blank), without prefixes. " +
                "Float64 softmax at temperature 1; Julia-1 ships no calibration.",
        )
    }

    override fun close() = lifetime.closeOnce {
        try { loaded.runner.close() } finally { table.close() }
    }
}
