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

// Ports LayaPromptBuilder, LayaCalibration, LayaDecoder and LayaDecider from the pinned
// google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8 typed_decisions/laya/.
// Original: litert-community/Laya-Multilingual-LiteRT@32f1b84d55f42a323464fad22594cfc2059e7467,
// android/app/src/main/java/com/laya/ (Apache-2.0), laya 0.3.4 by Convai Innovations.
// Changes: expose the graph's real score/noul channels, report all truncation, validate shapes,
// finite temperatures and schemas, and worker-confine/reset/close resources. See ATTRIBUTION.md.
package com.example.minimalapk.litert.text

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.exp
import kotlin.math.ln

/** Laya-only typed schema. `noul` is the exported yes/no channel, always false then true. */
data class LayaQuestion(val type: String, val instructions: String, val criteria: Any?) {
    val qtype: Int get() = when (type) {
        "choice" -> 0
        "score" -> 1
        "noul" -> 2
        else -> throw IllegalArgumentException("Unknown Laya question type '$type'.")
    }

    fun renderedOptions(): List<String> = when (type) {
        "choice" -> HostJson.obj(criteria).map { (key, value) ->
            if (value == null || value == "") key else "$key: ${render(value)}"
        }
        "score" -> HostJson.array(criteria).mapIndexed { index, value -> "level $index: ${render(value)}" }
        "noul" -> {
            val values = if (criteria == null) emptyMap() else HostJson.obj(criteria)
            listOf(
                "false: " + (values["false"]?.takeUnless { it == "" }?.let(::render) ?: "no, the statement does not hold"),
                "true: " + (values["true"]?.takeUnless { it == "" }?.let(::render) ?: "yes, the statement holds"),
            )
        }
        else -> throw IllegalArgumentException("Unknown Laya question type '$type'.")
    }

    internal fun validate() {
        qtype
        require(instructions.isNotBlank()) { "Enter Laya question instructions." }
        val options = renderedOptions()
        require(options.size in 2..20) { "The pinned Laya host supports 2–20 options/levels." }
        if (type == "choice") require(HostJson.obj(criteria).keys.all { it.isNotBlank() }) {
            "Laya choice keys must be nonblank."
        }
        if (type == "noul" && criteria != null) require(HostJson.obj(criteria).keys.all { it == "false" || it == "true" }) {
            "Laya yes/no criteria may only name false and true."
        }
        validateTextBudget(listOf(instructions) + options)
    }

    companion object {
        fun choice(instructions: String, options: Map<String, String?>): LayaQuestion =
            LayaQuestion("choice", instructions, LinkedHashMap(options))
        fun score(instructions: String, levels: List<String>): LayaQuestion =
            LayaQuestion("score", instructions, levels.toList())
        fun yesNo(instructions: String, falseDescription: String? = null, trueDescription: String? = null): LayaQuestion =
            LayaQuestion("noul", instructions, linkedMapOf("false" to falseDescription, "true" to trueDescription))

        internal fun fromMap(value: JsonObject): LayaQuestion {
            val type = value.string("type")
            var criteria = value["criteria"]
            if (type == "choice" && criteria is List<*>) {
                require(criteria.all { it is String }) { "Laya choice labels must be strings." }
                require(criteria.toSet().size == criteria.size) { "Laya choice labels repeat." }
                criteria = criteria.associateTo(LinkedHashMap()) { it as String to null }
            }
            require(value.containsKey("instructions")) { "Missing Laya instructions." }
            val instructions = value["instructions"].let { if (it is String) it else HostJson.stringify(it, true) }
            return LayaQuestion(type, instructions, criteria)
        }

        private fun render(value: Any?): String = if (value is String) value else HostJson.stringify(value)
    }
}

/** Answer is the official Laya schema (including confidence/action), never a fabricated class. */
data class LayaDecisionResult(
    val answer: Map<String, Any?>,
    val ms: Double,
    val backend: String,
    val window: Int,
    val fallbackReason: String? = null,
    val details: String = "",
)

/** Only the Laya task implements this extension; other tasks retain their own choice semantics. */
interface LayaDecisionEngine : TextDecisionEngine {
    fun decide(text: String, question: LayaQuestion): LayaDecisionResult
}

internal data class LayaSequence(
    val ids: IntArray,
    val markers: IntArray,
    val question: LayaQuestion,
    val originalStateTokens: Int,
    val retainedStateTokens: Int,
    val headWasTruncated: Boolean,
    val optionsWereTruncated: Boolean,
) {
    val details: String get() =
        "${ids.size} of 256 tokens; text retained $retainedStateTokens/$originalStateTokens tokens. " +
            "Instruction truncated=$headWasTruncated; options capped/squeezed=$optionsWereTruncated."
}

/** Exact laya 0.3.4 option cap, squeeze rule and right truncation, including the final slice. */
internal class LayaPromptBuilder(private val encode: (String) -> IntArray) {
    fun build(state: String, question: LayaQuestion): LayaSequence {
        question.validate()
        val options = question.renderedOptions()
        val head = encode("${question.type} question: ${question.instructions.replace("<mask>", " ")}")
        val fullOptions = options.map { encode(" " + it.replace("<mask>", " ")) }
        var optionIds = fullOptions.map { intArrayOf(4) + it.copyOf(minOf(it.size, 48)) }
        var budget = 256 - optionIds.sumOf { it.size }
        if (budget < 16) {
            val per = maxOf(4, (256 - 16) / optionIds.size)
            optionIds = optionIds.map { it.copyOf(minOf(it.size, per)) }
            budget = 256 - optionIds.sumOf { it.size }
        }
        val ids = arrayListOf(2)
        val headLimit = maxOf(8, budget)
        ids.addAll(head.take(headLimit))
        ids.add(1)
        val markers = ArrayList<Int>()
        for (option in optionIds) { markers.add(ids.size); ids.addAll(option.asList()) }
        ids.add(1)
        val room = maxOf(0, 256 - ids.size - 1)
        val stateIds = encode(state.replace("<mask>", " "))
        ids.addAll(stateIds.take(room))
        ids.add(1)
        require(markers.all { it < 256 }) { "Laya options exceed the 256-token head; an option marker would be lost." }
        return LayaSequence(
            ids.take(256).toIntArray(), markers.toIntArray(), question, stateIds.size, minOf(room, stateIds.size),
            head.size > headLimit,
            fullOptions.indices.any { fullOptions[it].size + 1 > optionIds[it].size } || ids.size > 256,
        )
    }
}

internal class LayaCalibration(
    private val temperatures: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),
    private val byOptions: Map<String, Double> = emptyMap(),
) {
    init {
        require(temperatures.size == 3) { "Laya needs choice/score/noul temperatures." }
        require((temperatures.asList() + byOptions.values).all { it.isFinite() && it >= 0.0 && it <= Float.MAX_VALUE }) {
            "Laya temperatures must be finite nonnegative float32 values; zero uses the upstream 1e-3 floor."
        }
        val buckets = listOf("choice", "score", "noul").flatMap { type ->
            listOf("2", "3-5", "6-10", "11+").map { "$type:$it" }
        }
        require(byOptions.keys.all { it in buckets }) { "Unknown Laya temperature bucket." }
    }

    fun temperature(type: String, count: Int): Double = byOptions[bucket(type, count)] ?: temperatures[when (type) {
        "choice" -> 0
        "score" -> 1
        "noul" -> 2
        else -> throw IllegalArgumentException("Unknown Laya type '$type'.")
    }]

    companion object {
        fun load(file: File): LayaCalibration = fromMap(HostJson.obj(HostJson.parse(file)))
        fun fromMap(config: JsonObject): LayaCalibration {
            require(config.containsKey("temperature")) { "Laya calibration is missing temperature." }
            val temperatures = config.array("temperature").map {
                (it as? Number)?.toDouble() ?: throw IllegalArgumentException("Invalid Laya temperature.")
            }.toDoubleArray()
            val buckets = config["temperature_by_options"]?.let { value ->
                HostJson.obj(value).mapValues { (_, v) ->
                    (v as? Number)?.toDouble() ?: throw IllegalArgumentException("Invalid Laya bucket temperature.")
                }
            } ?: emptyMap()
            return LayaCalibration(temperatures, buckets)
        }
        fun bucket(type: String, count: Int): String = "$type:" + when {
            count <= 2 -> "2"
            count <= 5 -> "3-5"
            count <= 10 -> "6-10"
            else -> "11+"
        }
    }
}

/** Preserves NumPy float32 reduction/weak-scalar arithmetic and Python four-decimal rounding. */
internal object LayaDecoder {
    fun softmax(values: FloatArray): FloatArray {
        require(values.isNotEmpty() && values.all { it.isFinite() }) { "Laya logits must be finite and nonempty." }
        val max = values.max()
        val out = FloatArray(values.size) { exp((values[it] - max).toDouble()).toFloat() }
        val sum = numpySum(out)
        return FloatArray(values.size) { out[it] / sum }
    }

    private fun numpySum(values: FloatArray, from: Int = 0, size: Int = values.size): Float {
        if (size < 8) {
            var sum = -0.0f
            for (i in from until from + size) sum += values[i]
            return sum
        }
        if (size <= 128) {
            val sums = FloatArray(8) { values[from + it] }
            var i = 8
            while (i < size - size % 8) {
                for (j in 0..7) sums[j] += values[from + i + j]
                i += 8
            }
            var sum = ((sums[0] + sums[1]) + (sums[2] + sums[3])) +
                ((sums[4] + sums[5]) + (sums[6] + sums[7]))
            while (i < size) sum += values[from + i++]
            return sum
        }
        val left = (size / 2) - (size / 2) % 8
        return numpySum(values, from, left) + numpySum(values, from + left, size - left)
    }

    fun actFeatures(logits: FloatArray): FloatArray {
        val p = softmax(logits)
        val k = maxOf(p.size, 2)
        val sorted = p.sortedDescending()
        val entropy = -numpySum(FloatArray(p.size) { p[it] * ln(maxOf(p[it], 1e-9f).toDouble()).toFloat() }) /
            ln(k.toDouble()).toFloat()
        return floatArrayOf(sorted[0], sorted[0] - sorted.getOrElse(1) { 0f }, entropy, k / 255f)
    }

    fun probabilities(logits: FloatArray, question: LayaQuestion, calibration: LayaCalibration): FloatArray {
        val temperature = maxOf(1e-3, calibration.temperature(question.type, logits.size)).toFloat()
        return softmax(FloatArray(logits.size) { logits[it] / temperature })
    }

    fun confidence(p: FloatArray): Double {
        if (p.size < 2) return 1.0
        val entropy = -numpySum(FloatArray(p.size) { p[it] * ln(p[it].coerceIn(1e-12f, 1f).toDouble()).toFloat() })
        return (1f - entropy / ln(p.size.toDouble()).toFloat()).coerceIn(0f, 1f).toDouble()
    }

    fun round4(value: Double): Double {
        require(value.isFinite()) { "Non-finite Laya decoded value." }
        val rounded = BigDecimal(value).setScale(4, RoundingMode.HALF_EVEN).toDouble()
        return if (rounded == 0.0 && value.toRawBits() < 0) -0.0 else rounded
    }

    fun decode(logits: FloatArray, act: FloatArray, q: LayaQuestion, calibration: LayaCalibration): Map<String, Any?> {
        require(logits.size == q.renderedOptions().size && act.size == 2) { "Laya output/schema size mismatch." }
        val p = probabilities(logits, q, calibration)
        val action = linkedMapOf("act_probability" to round4(softmax(act)[0].toDouble()))
        return when (q.type) {
            "choice" -> {
                val labels = HostJson.obj(q.criteria).keys.toList()
                linkedMapOf(
                    "type" to "choice", "choice" to labels[p.indices.maxBy { p[it] }],
                    "probabilities" to labels.indices.associateTo(LinkedHashMap()) { labels[it] to round4(p[it].toDouble()) },
                    "confidence" to round4(confidence(p)), "action" to action,
                )
            }
            "score" -> {
                val criteria = HostJson.array(q.criteria)
                linkedMapOf(
                    "type" to "score", "score" to round4(p.indices.sumOf { it.toDouble() * p[it].toDouble() }),
                    "legend" to criteria.indices.associateTo(LinkedHashMap()) { it.toString() to criteria[it] },
                    "probabilities" to p.indices.associateTo(LinkedHashMap()) { it.toString() to round4(p[it].toDouble()) },
                    "confidence" to round4(confidence(p)), "action" to action,
                )
            }
            "noul" -> linkedMapOf(
                "type" to "noul", "noul" to round4(p[1].toDouble()),
                "confidence" to round4(maxOf(p[1].toDouble(), 1.0 - p[1].toDouble())), "action" to action,
            )
            else -> throw IllegalArgumentException("Unknown Laya type '${q.type}'.")
        }
    }
}

internal class LayaDecider(directory: File, backend: String) : LayaDecisionEngine {
    private val lifetime = DecisionLifetime()
    private val tokenizer = GemmaTokenizer(File(directory, "tokenizer.json"))
    private val calibration = LayaCalibration.load(File(directory, "laya_ml_calibration.json"))
    private val table: MappedEmbeddings
    private data class Graphs(val main: DecisionGraph, val act: DecisionGraph)
    private val loaded: DecisionBackend<Graphs>

    init {
        val metadata = HostJson.obj(HostJson.parse(File(directory, "token_embeddings.json")))
        require(metadata.array("shape").map { it.integer("Embedding shape") } == listOf(256000, 768) &&
            metadata["dtype"] == "float16" && metadata["byte_order"] == "little" &&
            metadata["layout"] == "row-major" && metadata["filename"] == "token_embeddings_fp16.bin" &&
            (metadata["pad_id"] as? Number)?.toInt() == 0 &&
            (metadata["size_bytes"] as? Number)?.toLong() == 393_216_000L &&
            metadata["sha256"] == "58608f7bbd72e03adb3e2db8c4942407058eaeaac38e3abec1d6cb01c1512265") {
            "Laya token_embeddings.json does not describe the pinned [256000,768] FP16 table."
        }
        table = MappedEmbeddings(File(directory, "token_embeddings_fp16.bin"), 256000, 768)
        loaded = try {
            compileDecisionBackend(backend) { accelerator ->
                val main = DecisionGraph.create(
                    File(directory, "laya_ml_s256_embeds_wfp16.tflite"), accelerator,
                    linkedMapOf("attention_mask" to listOf(1, 256), "inputs_embeds" to listOf(1, 256, 768),
                        "qtype_onehot" to listOf(1, 3)),
                    linkedMapOf("pooled_cls" to listOf(1, 768), "token_logits" to listOf(1, 256)),
                )
                try {
                    Graphs(main, DecisionGraph.create(
                        File(directory, "laya_ml_act_head_fp32.tflite"), accelerator,
                        linkedMapOf("feats" to listOf(1, 4), "pooled_cls" to listOf(1, 768)),
                        mapOf("act_logits" to listOf(1, 2)),
                    ))
                } catch (failure: Throwable) { main.close(); throw failure }
            }
        } catch (failure: Throwable) { table.close(); throw failure }
    }

    override fun decide(request: TextDecisionRequest): TextDecisionResult {
        request.validate()
        val result = decide(request.text, LayaQuestion.choice(request.question, request.options.toMap(LinkedHashMap())))
        val probabilities = HostJson.obj(result.answer["probabilities"])
        return TextDecisionResult(
            request.options.map { (key, _) -> key to (probabilities.getValue(key) as Number).toFloat() },
            result.answer["choice"] as String, result.ms, result.backend, result.window,
            result.fallbackReason, result.details,
        )
    }

    override fun decide(text: String, question: LayaQuestion): LayaDecisionResult {
        lifetime.checkOpen()
        require(text.isNotBlank()) { "Enter the text to ask about." }
        question.validate()
        validateTextBudget(listOf(text, question.instructions) + question.renderedOptions())
        val started = System.nanoTime()
        val sequence = LayaPromptBuilder(tokenizer::encode).build(text, question)
        val main = loaded.runner.main
        main.write("inputs_embeds", table.gather(sequence.ids, 256))
        main.write("attention_mask", FloatArray(256) { if (it < sequence.ids.size) 1f else 0f })
        main.write("qtype_onehot", FloatArray(3).also { it[question.qtype] = 1f })
        main.run()
        val tokens = main.read("token_logits")
        val pooled = main.read("pooled_cls")
        val logits = FloatArray(sequence.markers.size) { tokens[sequence.markers[it]] }
        val act = loaded.runner.act
        act.write("pooled_cls", pooled)
        act.write("feats", LayaDecoder.actFeatures(logits))
        act.run()
        val answer = LayaDecoder.decode(logits, act.read("act_logits"), question, calibration)
        lifetime.checkOpen()
        return LayaDecisionResult(
            answer, DecisionGraph.milliseconds(started), loaded.backend, 256, loaded.fallbackReason,
            sequence.details + " Laya reads " + question.renderedOptions().joinToString("; ") { "\"$it\"" } +
                ". Calibrated by type/option count; four-decimal probabilities may not sum to exactly one. " +
                "Action probability is the separate raw-probability head, not an escalation policy.",
        )
    }

    override fun close() = lifetime.closeOnce {
        try { loaded.runner.main.close() } finally {
            try { loaded.runner.act.close() } finally { table.close() }
        }
    }
}
