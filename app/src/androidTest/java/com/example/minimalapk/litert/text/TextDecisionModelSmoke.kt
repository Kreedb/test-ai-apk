package com.example.minimalapk.litert.text

import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Opt-in REAL S128 GLiClass-Edge smoke. Does not download or bundle weights.
 * Callable from instrumentation, or as a dex entrypoint through Android app_process so a
 * coordinator can validate this isolated slice without changing/building the shared app.
 *
 * Three catalog files (95,971,235 bytes total) must be installed in [directory]. Provenance:
 * litert-community/GLiClass-Edge-v3.0-LiteRT@88c90950587eb951974c094eef91afa0fe3552c0,
 * fixtures/requests_public.json + fixtures/oracle_public.json, invented row inv_02 (Apache-2.0).
 * Official promptless CPU FP32 P(connectivity problem)=0.8560521; shipped FP16 table permitted
 * error 0.003. The engine's mandatory question is a distinct additional smoke, not that oracle.
 */
object TextDecisionModelSmoke {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Usage: TextDecisionModelSmoke /path/to/installed/task [cpu|gpu]" }
        println(run(File(args[0]), args.getOrElse(1) { "cpu" }))
    }

    fun run(directory: File, backend: String = "cpu"): String {
        check(backend == "cpu" || backend == "gpu")
        verify(directory, "gliclass_edge_v3_s128_fp32.tflite", 53_703_480L,
            "79036f538289fd406e6f33b6a1dda51758c77d5383c7704369248c491dd6dd59")
        verify(directory, "tok_embeddings_fp16.bin", 38_684_160L,
            "b22f9bd2aedb1768089da8126dc6cd69572b61f21e39d6254119773b5e147a27")
        verify(directory, "tokenizer.json", 3_583_595L,
            "7c1979be5ac04a6681dbfbb98a2b01b176883a298f2a7240c93048b244118a01")
        val tokenizer = GliclassTokenizer(File(directory, "tokenizer.json"))
        val labels = listOf("connectivity problem", "hardware defect", "billing issue", "feature request", "installation help")
        val text = "The Zorvik X2 router from Tallowmere Networks keeps dropping the connection every evening " +
            "around 9 pm. I have restarted it and reset it to factory settings, but the status light still blinks orange."
        val ids = tokenizer.encode(GliclassInputs.linearize(text, labels, ""))
        check(ids.size == 61) { "Published router fixture should encode to 61 tokens; got ${ids.size}." }
        val positions = ids.indices.filter { ids[it] == 50368 }.toIntArray()
        check(positions.contentEquals(intArrayOf(1, 4, 7, 10, 13)))
        val reference = floatArrayOf(.8560521006584167f, .08718694001436234f, .03564048931002617f,
            .019636455923318863f, .0014838966308161616f)
        val loaded = compileDecisionBackend(backend) { accelerator ->
            DecisionGraph.create(File(directory, "gliclass_edge_v3_s128_fp32.tflite"), accelerator,
                linkedMapOf("inputs_embeds" to listOf(1, 128, 384), "attention_mask" to listOf(1, 128),
                    "label_routing" to listOf(1, 25, 128)), mapOf("logits" to listOf(1, 1, 1, 25)))
        }
        val oracle: FloatArray
        loaded.runner.use { graph ->
            // A GPU smoke must really exercise GPU; compile fallback is useful in the app, not evidence.
            check(loaded.backend == if (backend == "cpu") "CPU" else "GPU") {
                "Requested $backend but got ${loaded.backend}: ${loaded.fallbackReason}"
            }
            MappedEmbeddings(File(directory, "tok_embeddings_fp16.bin"), 50370, 384).use { table ->
                graph.write("inputs_embeds", table.gather(ids, 128, tokenizer.padId))
                graph.write("attention_mask", FloatArray(128) { if (it < ids.size) 1f else 0f })
                graph.write("label_routing", GliclassInputs.routing(positions))
                graph.run()
                oracle = DecisionMath.softmax32(graph.read("logits").copyOf(labels.size))
                check(oracle.indices.maxBy { oracle[it] } == 0)
                check(oracle.indices.all { abs(oracle[it] - reference[it]) <= .003f }) {
                    "Real GLiClass output differs from published oracle: ${oracle.toList()}."
                }
                // Native graph reuse cannot silently accept stale inputs from the prior request.
                var rejected = false
                try { graph.run() } catch (_: IllegalStateException) { rejected = true }
                check(rejected) { "Graph accepted an invocation without rewriting its inputs." }
            }
        }

        val request = TextDecisionRequest(text, "What is the problem?", labels.mapIndexed { i, label -> "$i" to label })
        val short = TextDecisionRequest("I was charged twice. Please refund the duplicate payment.",
            "What is the problem?", listOf("billing" to "billing issue", "hardware" to "hardware defect"))
        val engine = TextDecisionTasks.create("text-decision-gliclass-edge", directory, backend)
        val first: TextDecisionResult
        val second: TextDecisionResult
        try {
            first = engine.decide(request)
            check(first.backend == if (backend == "cpu") "CPU" else "GPU") { first.fallbackReason.orEmpty() }
            check(first.answerKey == "0") { "Questioned router smoke chose ${first.answerKey}: ${first.probabilities}" }
            second = engine.decide(short)
            check(second.answerKey == "billing") { "Short billing smoke chose ${second.answerKey}: ${second.probabilities}" }
            var rejected = false
            try { engine.decide(short.copy(text = "hello ".repeat(300))) }
            catch (_: IllegalArgumentException) { rejected = true }
            check(rejected) { "S128 graph accepted an oversized request." }
            val repeated = engine.decide(request)
            same(first, repeated)
        } finally { engine.close() }
        engine.close()
        var closedRejected = false
        try { engine.decide(request) } catch (_: IllegalStateException) { closedRejected = true }
        check(closedRejected) { "Closed engine accepted another request." }
        TextDecisionTasks.create("text-decision-gliclass-edge", directory, backend).use { fresh ->
            same(first, fresh.decide(request))
        }
        return "PASS GLiClass-Edge pinned S128 $backend: oracle=${oracle.toList()}; " +
            "questioned-router=${first.probabilities} (${first.ms} ms); billing=${second.probabilities} " +
            "(${second.ms} ms); A/B/invalid/A and close/fresh A agree."
    }

    private fun same(a: TextDecisionResult, b: TextDecisionResult) {
        check(a.answerKey == b.answerKey && a.backend == b.backend)
        check(a.probabilities.map { it.first } == b.probabilities.map { it.first })
        check(a.probabilities.indices.all { abs(a.probabilities[it].second - b.probabilities[it].second) <= 1e-5f }) {
            "Request state leaked: ${a.probabilities} vs ${b.probabilities}"
        }
    }

    private fun verify(directory: File, name: String, bytes: Long, expected: String) {
        val file = File(directory, name)
        check(file.isFile && file.length() == bytes) { "$name must be the pinned $bytes-byte file." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actual == expected) { "$name hash differs: $actual." }
    }
}
