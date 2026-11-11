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

// Ports DecideInputs/DecideSchema/DecideDecoder/GlinerDecider from pinned typed_decisions/gliner/.
// Host origin: litert-community/GLiNER2.5-Decide-LiteRT@db80197282d11373df084c0ceed67a54544cfa84
// android/app/src/main/java/com/gliner25decide/ (Apache-2.0), Fastino AI gliner2 2.0.0.
// Changes: one single-label task S128 adapter, strict diagnostics, named shape discovery and reset.
// This is GLiNER-Decide classification, NOT GLiNER2.5-Small entity extraction. See ATTRIBUTION.md.
package com.example.minimalapk.litert.text

import java.io.File
import java.util.Locale
import java.util.regex.Pattern

internal data class GlinerWord(val text: String, val start: Int, val end: Int)
internal data class GlinerSequence(val ids: IntArray, val labelPositions: IntArray, val words: List<GlinerWord>)

/** `( [P] answer: prompt ( [L] description ... ) ) [SEP_TEXT] lowercased text words`. */
internal class GlinerInputs(private val tokenizer: UnigramTokenizer) {
    fun encode(request: TextDecisionRequest): GlinerSequence {
        request.validate()
        require(request.options.size <= 32) { "GLiNER-Decide has 32 label slots." }
        val labels = request.options.indices.map(request::optionText)
        require(labels.distinct().size == labels.size) { "GLiNER-Decide needs distinct rendered option descriptions." }
        require(labels.none { it == "[SEP_STRUCT]" || it == "[SEP_TEXT]" }) {
            "GLiNER-Decide label cannot equal [SEP_STRUCT] or [SEP_TEXT]; it would shift schema routing."
        }
        val ids = ArrayList<Int>()
        fun append(word: String) { ids.addAll(tokenizer.encode(word).asList()) }
        // Every item is independently tokenized; concatenating and then encoding changes Metaspace.
        append("(")
        append("[P]")
        append("answer: ${request.question}")
        append("(")
        val positions = IntArray(labels.size)
        labels.forEachIndexed { index, label ->
            positions[index] = ids.size
            append("[L]")
            append(label)
        }
        append(")")
        append(")")
        append("[SEP_TEXT]")
        val words = splitWords(collateText(request.text))
        words.forEach { append(it.text) }
        require(ids.size <= 128) {
            "GLiNER-Decide request needs ${ids.size} encoded tokens; its fixed graph holds 128. Nothing was truncated."
        }
        return GlinerSequence(ids.toIntArray(), positions, words)
    }

    companion object {
        fun collateText(text: String): String = when {
            text.isEmpty() -> "."
            text.endsWith('.') || text.endsWith('!') || text.endsWith('?') -> text
            else -> "$text."
        }

        /** Original code-point offsets survive lowercase expansions, e.g. U+0130 and emoji. */
        fun splitWords(text: String): List<GlinerWord> {
            val result = ArrayList<GlinerWord>()
            val matcher = WORD_PATTERN.matcher(text)
            while (matcher.find()) result.add(GlinerWord(
                matcher.group().lowercase(Locale.ROOT), text.codePointCount(0, matcher.start()),
                text.codePointCount(0, matcher.end()),
            ))
            return result
        }

        fun routing(positions: IntArray): FloatArray {
            require(positions.size in 2..32 && positions.all { it in 0 until 128 }) { "Invalid GLiNER-Decide label routing." }
            return FloatArray(32 * 128).also { out ->
                positions.forEachIndexed { slot, position -> out[slot * 128 + position] = 1f }
            }
        }

        /** Exporters may permute args_N: each semantic input has exactly one matching shape. */
        fun resolveInputShapes(dimensions: (String) -> List<Int>): Map<String, List<Int>> {
            val names = listOf("args_0", "args_1", "args_2")
            val actual = names.associateWith(dimensions)
            val ordered = linkedMapOf<String, List<Int>>()
            for (shape in listOf(listOf(1, 128, 1024), listOf(1, 128), listOf(1, 32, 128))) {
                val matches = actual.filterValues { it == shape }.keys
                require(matches.size == 1) { "GLiNER-Decide expects one FLOAT32 input $shape; got $actual." }
                ordered[matches.single()] = shape
            }
            return ordered
        }

        private const val SPACE = "\\x{09}-\\x{0d}\\x{1c}-\\x{20}\\x{85}\\x{a0}\\x{1680}" +
            "\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}"
        private const val WORD = "\\p{L}\\p{N}_"
        private val WORD_PATTERN = Pattern.compile(
            "(?:https?://[^$SPACE]+|www\\.[^$SPACE]+)" +
                "|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}|@[a-z0-9_]+" +
                "|[$WORD]+(?:[-_][$WORD]+)*|[^$SPACE]",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )
    }
}

internal class GlinerDecider(directory: File, backend: String) : TextDecisionEngine {
    private val lifetime = DecisionLifetime()
    private val tokenizer = UnigramTokenizer(File(directory, "tokenizer.json"), UnigramFamily.GLINER_DECIDE)
    private val inputs = GlinerInputs(tokenizer)
    private val table = MappedEmbeddings(File(directory, "word_embeddings_fp16.bin"), 128011, 1024)
    private val loaded = try {
        compileDecisionBackend(backend) { accelerator ->
            DecisionGraph.create(
                File(directory, "gliner25_decide_s128_wfp16.tflite"), accelerator,
                GlinerInputs::resolveInputShapes, mapOf("output_0" to listOf(1, 1, 1, 32)),
            )
        }
    } catch (failure: Throwable) { table.close(); throw failure }

    override fun decide(request: TextDecisionRequest): TextDecisionResult {
        lifetime.checkOpen()
        val started = System.nanoTime()
        val sequence = inputs.encode(request)
        val graph = loaded.runner
        val (embeds, attention, routing) = graph.inputNames
        graph.write(embeds, table.gather(sequence.ids, 128, tokenizer.padId))
        graph.write(attention, FloatArray(128) { if (it < sequence.ids.size) 1f else 0f })
        graph.write(routing, GlinerInputs.routing(sequence.labelPositions))
        graph.run()
        val p = DecisionMath.softmax32(graph.read("output_0").copyOf(request.options.size))
        lifetime.checkOpen()
        return TextDecisionResult(
            request.options.mapIndexed { i, (key, _) -> key to p[i] }, request.options[p.indices.maxBy { p[it] }].first,
            DecisionGraph.milliseconds(started), loaded.backend, 128, loaded.fallbackReason,
            "${sequence.ids.size} of 128 tokens; nothing truncated. GLiNER-Decide task 'answer' uses the " +
                "question as its prompt, descriptions as labels and independently encoded lowercase words. " +
                "Single-label float32 softmax; this model does not extract entity spans.",
        )
    }

    override fun close() = lifetime.closeOnce {
        try { loaded.runner.close() } finally { table.close() }
    }
}
