package com.example.minimalapk.litert.text

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exports ACTUAL Kotlin tokenizer/prompt/routing/mmap-embedding inputs for independent CPU checks.
 * Compile with the main text sources and run main(installedTaskRoot, outputDirectory); then run
 * verify_text_decisions.py in this directory with ai-edge-litert, numpy and tokenizers available.
 * No weights bundled, no network, no Android/Gradle dependency. See ATTRIBUTION.md for provenance.
 */
object TextDecisionFixtureExporter {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected installed task root and scratch output directory." }
        val assets = File(args[0])
        val output = File(args[1]).apply { mkdirs() }
        val cases = ArrayList<Map<String, Any?>>()
        fun input(case: String, name: String, shape: List<Int>, values: FloatArray): Map<String, Any?> {
            val file = File(output, "$case-$name.f32")
            val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(values)
            file.writeBytes(bytes.array())
            return mapOf("name" to name, "shape" to shape, "file" to file.name)
        }

        val layaTask = "text-decision-laya-multilingual"
        val layaDir = File(assets, layaTask)
        val gemma = GemmaTokenizer(File(layaDir, "tokenizer.json"))
        val layaExamples = listOf(
            Triple("laya-card-choice", "同じ支払いが二重に請求されました。返金をお願いします。",
                LayaQuestion.choice("What does the customer need?", linkedMapOf("refund" to "money returned", "help" to "technical help"))),
            Triple("laya-gate-score", "{\"from\": \"Draft desk\", \"subject\": \"Extra charge\", \"body\": \"Please return the duplicate payment.\"}",
                LayaQuestion.score("How urgent is this request?", listOf("not urgent", "soon", "critical deadline or blocking issue"))),
            Triple("laya-gate-noul", "{\"from\": \"Draft desk\", \"subject\": \"Extra charge\", \"body\": \"Please return the duplicate payment.\"}",
                LayaQuestion.yesNo("Does the user threaten to cancel or leave?")),
        )
        MappedEmbeddings(File(layaDir, "token_embeddings_fp16.bin"), 256000, 768).use { table ->
            for ((id, text, question) in layaExamples) {
                val sequence = LayaPromptBuilder(gemma::encode).build(text, question)
                cases.add(linkedMapOf("id" to id, "task" to layaTask, "graph" to "laya_ml_s256_embeds_wfp16.tflite",
                    "text" to text, "question" to linkedMapOf("type" to question.type, "instructions" to question.instructions,
                        "criteria" to question.criteria), "ids" to sequence.ids, "markers" to sequence.markers,
                    "expected_key" to if (id == "laya-card-choice") "refund" else null,
                    "inputs" to listOf(
                        input(id, "inputs_embeds", listOf(1, 256, 768), table.gather(sequence.ids, 256)),
                        input(id, "attention_mask", listOf(1, 256), FloatArray(256) { if (it < sequence.ids.size) 1f else 0f }),
                        input(id, "qtype_onehot", listOf(1, 3), FloatArray(3).also { it[question.qtype] = 1f }))))
            }
        }

        val juliaTask = "text-decision-julia-1"
        val julia = TextDecisionRequest(
            "Order #4417 from Larkspur Home Goods was charged twice. The customer, Mira Okafor, wants the extra charge refunded before Friday.",
            "Which team should handle this request?", listOf("billing" to "Billing and payment disputes",
                "shipping" to "Shipping and delivery", "access" to "Account access and login"))
        val juliaSequence = JuliaSequenceBuilder(gemma::encode).build(julia)
        MappedEmbeddings(File(File(assets, juliaTask), "julia1_token_table_fp16.bin"), 256000, 384).use { table ->
            val id = "julia-card-team"
            cases.add(linkedMapOf("id" to id, "task" to juliaTask, "graph" to "julia1_s512_fp32.tflite",
                "text" to julia.text, "question" to julia.question, "options" to julia.options.map { listOf(it.first, it.second) },
                "ids" to juliaSequence.ids, "markers" to juliaSequence.markers, "expected_key" to "billing", "inputs" to listOf(
                    input(id, "inputs_embeds", listOf(1, 512, 384), table.gather(juliaSequence.ids, 512)),
                    input(id, "attention_mask", listOf(1, 512), FloatArray(512) { if (it < juliaSequence.ids.size) 1f else 0f }),
                    input(id, "qtype_onehot", listOf(1, 3), floatArrayOf(1f, 0f, 0f)))))
        }

        val gliclassTask = "text-decision-gliclass-edge"
        val gliclassDir = File(assets, gliclassTask)
        val gliclassTokenizer = GliclassTokenizer(File(gliclassDir, "tokenizer.json"))
        val router = TextDecisionRequest(
            "The Zorvik X2 router from Tallowmere Networks keeps dropping the connection every evening around 9 pm. " +
                "I have restarted it and reset it to factory settings, but the status light still blinks orange.",
            "What is the problem?", listOf("connection" to "connectivity problem", "hardware" to "hardware defect",
                "billing" to "billing issue", "feature" to "feature request", "installation" to "installation help"))
        val gliclassSequence = GliclassInputs(gliclassTokenizer).encode(router)
        MappedEmbeddings(File(gliclassDir, "tok_embeddings_fp16.bin"), 50370, 384).use { table ->
            val id = "gliclass-router-with-question"
            cases.add(linkedMapOf("id" to id, "task" to gliclassTask, "graph" to "gliclass_edge_v3_s128_fp32.tflite",
                "text" to router.text, "question" to router.question, "options" to router.options.map { listOf(it.first, it.second) },
                "ids" to gliclassSequence.ids, "markers" to gliclassSequence.labelPositions, "expected_key" to "connection",
                "inputs" to listOf(
                    input(id, "inputs_embeds", listOf(1, 128, 384), table.gather(gliclassSequence.ids, 128, gliclassTokenizer.padId)),
                    input(id, "attention_mask", listOf(1, 128), FloatArray(128) { if (it < gliclassSequence.ids.size) 1f else 0f }),
                    input(id, "label_routing", listOf(1, 25, 128), GliclassInputs.routing(gliclassSequence.labelPositions)))))
            val oracleId = "gliclass-card-promptless"
            val ids = gliclassTokenizer.encode(GliclassInputs.linearize(router.text, router.options.map { it.second }, ""))
            val positions = ids.indices.filter { ids[it] == 50368 }.toIntArray()
            cases.add(linkedMapOf("id" to oracleId, "task" to gliclassTask, "graph" to "gliclass_edge_v3_s128_fp32.tflite",
                "text" to router.text, "question" to "", "options" to router.options.map { listOf(it.first, it.second) },
                "ids" to ids, "markers" to positions, "expected_key" to "connection", "inputs" to listOf(
                    input(oracleId, "inputs_embeds", listOf(1, 128, 384), table.gather(ids, 128, gliclassTokenizer.padId)),
                    input(oracleId, "attention_mask", listOf(1, 128), FloatArray(128) { if (it < ids.size) 1f else 0f }),
                    input(oracleId, "label_routing", listOf(1, 25, 128), GliclassInputs.routing(positions)))))
        }

        val debertaTask = "text-decision-open-decision"
        val debertaDir = File(assets, debertaTask)
        val debertaTokenizer = UnigramTokenizer(File(debertaDir, "tokenizer.json"), UnigramFamily.OPEN_DECISION)
        val delivery = TextDecisionRequest(
            "Order 8841 was supposed to arrive on Monday and the tracking page still says 'label created'. " +
                "I need it before Friday for a gift.", "Which team should handle this message?",
            listOf("billing", "technical support", "shipping", "account access", "sales").map { it to "" })
        val debertaSequence = DebertaInputs(debertaTokenizer).encode(delivery)
        val (q, o) = DebertaInputs.routing(debertaSequence.question, debertaSequence.options)
        MappedEmbeddings(File(debertaDir, "word_embeddings_fp16.bin"), 128100, 1024).use { table ->
            val id = "deberta-card-one-question"
            cases.add(linkedMapOf("id" to id, "task" to debertaTask, "graph" to "deberta_v3_large_decision_s256_wfp16.tflite",
                "text" to delivery.text, "question" to delivery.question, "options" to delivery.options.map { listOf(it.first, it.second) },
                "ids" to debertaSequence.ids, "question_span" to listOf(debertaSequence.question.start, debertaSequence.question.end),
                "option_spans" to debertaSequence.options.map { listOf(it.start, it.end) }, "expected_key" to "shipping",
                "inputs" to listOf(
                    input(id, "inputs_embeds", listOf(1, 256, 1024), table.gather(debertaSequence.ids, 256)),
                    input(id, "attention_mask", listOf(1, 256), FloatArray(256) { if (it < debertaSequence.ids.size) 1f else 0f }),
                    input(id, "q_routing", listOf(1, 128, 256), q), input(id, "o_routing", listOf(1, 128, 256), o))))
        }

        val glinerTask = "text-decision-gliner-decide"
        val glinerDir = File(assets, glinerTask)
        val glinerTokenizer = UnigramTokenizer(File(glinerDir, "tokenizer.json"), UnigramFamily.GLINER_DECIDE)
        val treaty = TextDecisionRequest(
            "The treaty was signed in Paris in 1992. It entered into force the following year, after the last signatory ratified it.",
            "Did the treaty enter into force in 1992?", listOf("yes" to "", "no" to ""))
        val glinerSequence = GlinerInputs(glinerTokenizer).encode(treaty)
        MappedEmbeddings(File(glinerDir, "word_embeddings_fp16.bin"), 128011, 1024).use { table ->
            val id = "gliner-readme18"
            cases.add(linkedMapOf("id" to id, "task" to glinerTask, "graph" to "gliner25_decide_s128_wfp16.tflite",
                "text" to treaty.text, "question" to treaty.question, "options" to treaty.options.map { listOf(it.first, it.second) },
                // The published GLiNER oracle incorrectly answers YES to this passage. Preserve
                // conversion parity and record the semantic failure rather than inventing accuracy.
                "ids" to glinerSequence.ids, "markers" to glinerSequence.labelPositions, "expected_key" to "yes",
                "semantic_expected_key" to "no", "known_model_error" to true,
                "inputs" to listOf(
                    input(id, "args_0", listOf(1, 128, 1024), table.gather(glinerSequence.ids, 128)),
                    input(id, "args_1", listOf(1, 128), FloatArray(128) { if (it < glinerSequence.ids.size) 1f else 0f }),
                    input(id, "args_2", listOf(1, 32, 128), GlinerInputs.routing(glinerSequence.labelPositions)))))
        }
        File(output, "cases.json").writeText(HostJson.stringify(cases))
        println("Exported ${cases.size} literal fixtures and mmap-gathered float32 inputs to $output")
    }
}
