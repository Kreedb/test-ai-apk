package com.example.minimalapk.litert.text

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses actual outputs from verify_text_decisions.py, never synthetic successful graph outputs. */
class RealGraphDecoderParityTest {
    @Test fun allFiveHostDecodersMatchIndependentCpuReferenceOnLiteralRequests() {
        val directory = System.getProperty("text.decision.exported") ?: System.getenv("TEXT_DECISION_EXPORTED")
        assumeTrue("Set TEXT_DECISION_EXPORTED after running the real CompiledModel verifier.", !directory.isNullOrBlank())
        val files = File(checkNotNull(directory))
        val cases = HostJson.array(HostJson.parse(File(files, "cases.json"))).map(HostJson::obj).associateBy { it.string("id") }
        val results = HostJson.array(HostJson.parse(File(files, "cpu-results.json"))).map(HostJson::obj)
        assertEquals("Every exported literal fixture must have a successful real-model result", cases.size, results.size)
        val assets = System.getProperty("text.decision.fixtures") ?: System.getenv("TEXT_DECISION_FIXTURES")
        require(!assets.isNullOrBlank()) { "Set TEXT_DECISION_FIXTURES to the pinned asset directory." }
        val calibration = LayaCalibration.load(File(assets, "text-decision-laya-multilingual/laya_ml_calibration.json"))
        val families = HashSet<String>()
        for (result in results) {
            val case = cases.getValue(result.string("id"))
            val family = case.string("task")
            families.add(family)
            assertEquals(true, result["input_parity"])
            val expected = result.array("probabilities").map { (it as Number).toDouble() }.toDoubleArray()
            val outputs = result.obj("raw_outputs")
            if (family.endsWith("laya-multilingual")) {
                val q = LayaQuestion.fromMap(case.obj("question"))
                val all = floats(outputs["token_logits"])
                val logits = case.array("markers").map { all[it.integer("Marker")] }.toFloatArray()
                val features = LayaDecoder.actFeatures(logits)
                assertArrayEquals(result.string("id"), floats(result["act_features"]), features, 1e-6f)
                val p = LayaDecoder.probabilities(logits, q, calibration)
                assertArrayEquals(result.string("id"), expected.map(Double::toFloat).toFloatArray(), p, 1e-6f)
                val answer = LayaDecoder.decode(logits, floats(result["act_logits"]), q, calibration)
                when (q.type) {
                    "choice" -> assertEquals(result["answer_key"], answer["choice"])
                    "score" -> assertEquals(LayaDecoder.round4(expected.indices.sumOf { it * expected[it] }), answer["score"])
                    "noul" -> assertEquals(LayaDecoder.round4(expected[1]), answer["noul"])
                }
            } else {
                val logits = when {
                    family.endsWith("julia-1") -> {
                        val all = floats(outputs["token_logits"])
                        case.array("markers").map { all[it.integer("Marker")] }.toFloatArray()
                    }
                    family.endsWith("gliner-decide") -> floats(outputs["output_0"]).copyOf(expected.size)
                    else -> floats(outputs["logits"]).copyOf(expected.size)
                }
                val actual = when {
                    family.endsWith("julia-1") -> DecisionMath.softmax64(logits)
                    family.endsWith("open-decision") -> DecisionMath.softmax64(logits, 1.05)
                    else -> DecisionMath.softmax32(logits).map(Float::toDouble).toDoubleArray()
                }
                assertArrayEquals(result.string("id"), expected, actual, 1e-6)
                val keys = case.array("options").map { HostJson.array(it)[0] as String }
                assertEquals(result["answer_key"], keys[actual.indices.maxBy { actual[it] }])
            }
        }
        assertEquals(TextDecisionTasks.ids, families)
        val gliner = results.single { it["id"] == "gliner-readme18" }
        assertEquals("yes", gliner["answer_key"])
        assertEquals("no", gliner["semantic_expected_key"])
        assertEquals(true, gliner["known_model_error"])
    }

    private fun floats(value: Any?): FloatArray = HostJson.array(value).map { (it as Number).toFloat() }.toFloatArray()
}
