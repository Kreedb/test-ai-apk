package com.example.minimalapk.litert.text

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in REAL tokenizer fixtures, not a reduced vocabulary or a mock model.
 * Set TEXT_DECISION_FIXTURES to a directory with taskId/tokenizer.json (catalog files).
 * No network in the test. Sources, pins and hashes are in text/ATTRIBUTION.md.
 */
class PinnedTokenizerParityTest {
    private fun tokenizer(task: String, expectedSha: String): File {
        val path = System.getProperty("text.decision.fixtures") ?: System.getenv("TEXT_DECISION_FIXTURES")
        assumeTrue("Set TEXT_DECISION_FIXTURES for pinned-tokenizer parity", !path.isNullOrBlank())
        val file = File(path, "$task/tokenizer.json")
        assertTrue("Missing $file", file.isFile)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                hash.update(chunk, 0, count)
            }
        }
        assertEquals(expectedSha, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        return file
    }

    @Test fun layaActualGemmaTokenizerMatchesOfficialPromptIdsAndMarkers() {
        val tokenizer = GemmaTokenizer(tokenizer("text-decision-laya-multilingual", GEMMA_SHA))
        // Laya fixtures/gate_rows_s256.json ML_A01/department: invented state, Apache-2.0.
        val state = HostJson.stringify(linkedMapOf("from" to "Draft desk", "subject" to "Extra charge",
            "body" to "Please return the duplicate payment."))
        val question = LayaQuestion.choice("Which department should handle this request?", linkedMapOf(
            "billing" to "invoices, payments, refunds", "technical" to "bugs, outages, system errors",
            "sales" to "pricing, new contracts", "other" to "everything else"))
        val sequence = LayaPromptBuilder(tokenizer::encode).build(state, question)
        assertArrayEquals(intArrayOf(2, 6241, 2872, 235292, 12236, 9888, 1412, 6589, 736, 3853, 235336, 1,
            4, 54972, 235292, 88220, 235269, 15598, 235269, 85869, 4, 9838, 235292, 30608, 235269,
            142788, 235269, 1812, 10266, 4, 7108, 235292, 25063, 235269, 888, 20078, 4, 1156, 235292,
            4553, 1354, 1, 19946, 2273, 1192, 664, 56041, 11309, 824, 664, 15029, 1192, 664, 12993,
            6256, 824, 664, 2168, 1192, 664, 5958, 2203, 573, 38294, 7984, 1464, 235270, 1), sequence.ids)
        assertArrayEquals(intArrayOf(12, 20, 29, 36), sequence.markers)
        assertFalse(sequence.headWasTruncated)
        assertEquals(sequence.originalStateTokens, sequence.retainedStateTokens)
    }

    @Test fun juliaActualTokenizerUsesDescriptionsOnlyAndStrictLimits() {
        val tokenizer = GemmaTokenizer(tokenizer("text-decision-julia-1", GEMMA_SHA))
        // Same SHA as Laya, but Julia reads bare descriptions, never key prefixes. IDs below
        // independently generated with Hugging Face tokenizers 0.23.2 add_special_tokens=false
        // and the pinned Julia strict sequence contract (see ATTRIBUTION.md).
        val request = TextDecisionRequest("Please return the duplicate payment.",
            "Which department should handle this request?", listOf("billing" to "invoices, payments, refunds",
                "technical" to "bugs, outages, system errors"))
        val sequence = JuliaSequenceBuilder(tokenizer::encode).build(request)
        assertArrayEquals(intArrayOf(2, 6241, 2872, 235292, 12236, 9888, 1412, 6589, 736, 3853, 235336,
            1, 4, 88220, 235269, 15598, 235269, 85869, 4, 30608, 235269, 142788, 235269, 1812, 10266,
            1, 5651, 2203, 573, 38294, 7984, 235265, 1), sequence.ids)
        assertArrayEquals(intArrayOf(12, 18), sequence.markers)
        assertThrows(IllegalArgumentException::class.java) {
            JuliaSequenceBuilder(tokenizer::encode).build(request.copy(text = "hello ".repeat(600)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            JuliaSequenceBuilder(tokenizer::encode).build(request.copy(options = listOf("a" to "hello ".repeat(60), "b" to "short")))
        }
    }

    @Test fun gliclassActualByteLevelTokenizerMatchesPublishedInventedRouterFixture() {
        val tokenizer = GliclassTokenizer(tokenizer("text-decision-gliclass-edge",
            "7c1979be5ac04a6681dbfbb98a2b01b176883a298f2a7240c93048b244118a01"))
        // GLiClass fixtures/oracle_public.json inv_02: model-card invented example, Apache-2.0.
        val labels = listOf("connectivity problem", "hardware defect", "billing issue", "feature request", "installation help")
        val text = "The Zorvik X2 router from Tallowmere Networks keeps dropping the connection every evening " +
            "around 9 pm. I have restarted it and reset it to factory settings, but the status light still blinks orange."
        val ids = tokenizer.encode(GliclassInputs.linearize(text, labels, ""))
        assertArrayEquals(intArrayOf(50281, 50368, 17769, 1895, 50368, 10309, 7071, 50368, 33484, 2523,
            50368, 4735, 2748, 50368, 12692, 1361, 50369, 380, 1503, 263, 30127, 1594, 19, 23093,
            432, 308, 8912, 37166, 32492, 11359, 18752, 253, 4602, 1046, 7237, 1475, 898, 12920, 15,
            309, 452, 19855, 264, 352, 285, 14932, 352, 281, 13250, 7533, 13, 533, 253, 3708, 1708,
            1335, 787, 3106, 13735, 15, 50282), ids)
        val inputs = GliclassInputs(tokenizer)
        val request = TextDecisionRequest(text, "What is the problem?", labels.map { it to "" })
        val prepared = inputs.encode(request)
        assertEquals(labels, prepared.labels)
        assertArrayEquals(intArrayOf(1, 4, 7, 10, 13), prepared.labelPositions)
        assertThrows(IllegalArgumentException::class.java) { inputs.encode(request.copy(text = "<<SEP>> data")) }
        assertThrows(IllegalArgumentException::class.java) { inputs.encode(request.copy(text = "hello ".repeat(200))) }
    }

    @Test fun openDecisionActualPrecompiledNormalizationMatchesPublishedProbesAndAveragesTextSpans() {
        val tokenizer = UnigramTokenizer(tokenizer("text-decision-open-decision",
            "cd119378b0160677b7a1e561ba29ada83918c1d420b326516a585382e83d9d39"), UnigramFamily.OPEN_DECISION)
        // Open-Decision android/sample/app/src/test/resources/tokenizer_probes.json.
        val cases = listOf(
            "ﬁancé ①②③ ㎞ ＡＢＣ" to intArrayOf(33771, 13661, 3964, 6783),
            "a [UNK] b" to intArrayOf(266, 507, 3, 2165),
            "zero\u200bwidth" to intArrayOf(3911, 6459),
            "[STATE] marker inside text [Q] [OPT]" to intArrayOf(128001, 10315, 1013, 1529, 128002, 128003),
            "日本語の文です。" to intArrayOf(507, 100868, 89387, 9456, 42728, 104364, 10548),
        )
        for ((text, expected) in cases) assertArrayEquals(text, expected, tokenizer.encode(text))
        val inputs = DebertaInputs(tokenizer)
        val request = TextDecisionRequest("the", "the", listOf("a" to "very negative", "b" to "fees & charges"))
        val sequence = inputs.encode(request)
        assertArrayEquals(intArrayOf(1, 128001, 262, 128002, 262, 128003, 379, 2330, 128003, 2516, 429, 2521, 2), sequence.ids)
        assertEquals(DecisionSpan(4, 5), sequence.question)
        assertEquals(listOf(DecisionSpan(6, 8), DecisionSpan(9, 12)), sequence.options)
        val failure = assertThrows(IllegalArgumentException::class.java) { inputs.encode(request.copy(text = "the ".repeat(400))) }
        assertTrue(failure.message!!.contains("256 TOTAL"))
    }

    @Test fun glinerActualTokenizerKeepsDifferentNormalizationAndReadme18Schema() {
        val tokenizer = UnigramTokenizer(tokenizer("text-decision-gliner-decide",
            "3ad87d9ffe669147063e70850927dd2da90249e2acc5c8527f1eb65df467bcc8"), UnigramFamily.GLINER_DECIDE)
        // GLiNER-Decide android/app/src/test/resources/tokenizer_probes.json.
        for ((text, ids) in listOf("a [UNK] b" to intArrayOf(266, 507, 3, 2165),
            "a\u00a0b" to intArrayOf(266, 3, 1285), "e\u0301" to intArrayOf(14287),
            "ﬁle" to intArrayOf(507, 3, 2624), "ＡＢＣ" to intArrayOf(507, 3))) {
            assertArrayEquals(text, ids, tokenizer.encode(text))
        }
        // android/app/src/debug/assets/gate_fixtures.json readme_18_question_over_a_passage.
        val request = TextDecisionRequest(
            "The treaty was signed in Paris in 1992. It entered into force the following year, after the last signatory ratified it.",
            "Did the treaty enter into force in 1992?", listOf("Y" to "yes", "N" to "no"))
        val inputs = GlinerInputs(tokenizer)
        val sequence = inputs.encode(request)
        assertArrayEquals(intArrayOf(287, 128003, 1330, 294, 2709, 262, 13281, 1916, 352, 1711, 267,
            5762, 302, 287, 128007, 2489, 128007, 363, 1263, 1263, 128002, 262, 13281, 284, 2346,
            267, 47136, 267, 5762, 323, 278, 3040, 352, 1711, 262, 776, 395, 366, 385, 262, 437,
            59810, 31719, 278, 323), sequence.ids)
        assertArrayEquals(intArrayOf(14, 16), sequence.labelPositions)
        assertThrows(IllegalArgumentException::class.java) { inputs.encode(request.copy(text = "the ".repeat(200))) }
        assertThrows(IllegalArgumentException::class.java) {
            inputs.encode(request.copy(options = listOf("a" to "[SEP_TEXT]", "b" to "no")))
        }
    }

    companion object {
        private const val GEMMA_SHA = "609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f"
    }
}
