package com.example.minimalapk.litert.text

import org.junit.Assert.*
import org.junit.Test

/** Captured outputs below are from the pinned models' Apache-2.0 public fixture files. */
class DecisionDecoderTest {
    @Test fun layaCapturedChoiceScoreAndNoulUseOfficialArithmetic() {
        // Laya fixtures/gate_rows_s256.json: ML_A01/department, urgency, churn_risk.
        val choice = LayaQuestion.choice("Which department should handle this request?", linkedMapOf(
            "billing" to "invoices, payments, refunds", "technical" to "bugs, outages, system errors",
            "sales" to "pricing, new contracts", "other" to "everything else",
        ))
        val answer = LayaDecoder.decode(
            floatArrayOf(12.99390697479248f, -3.6657893657684326f, -4.524628162384033f, -3.6038355827331543f),
            floatArrayOf(1191.483642578125f, -1424.5887451171875f), choice, LayaCalibration(),
        )
        assertEquals("billing", answer["choice"])
        assertEquals(linkedMapOf("billing" to 1.0, "technical" to 0.0, "sales" to 0.0, "other" to 0.0), answer["probabilities"])
        val score = LayaQuestion.score("How urgent is this request?", listOf("not urgent", "soon", "critical deadline or blocking issue"))
        val logits = floatArrayOf(-2.2645411491394043f, -.05815485119819641f, 1.105062484741211f)
        val result = LayaDecoder.decode(logits, floatArrayOf(1541.9920654296875f, -1820.5234375f), score, LayaCalibration())
        assertEquals(1.7169, result["score"])
        assertEquals(0.4049, result["confidence"])
        assertEquals(linkedMapOf("0" to .0255, "1" to .232, "2" to .7425), result["probabilities"])
        assertEquals(mapOf("act_probability" to 1.0), result["action"])
        val noul = LayaDecoder.decode(floatArrayOf(4.8075408935546875f, -.6828187108039856f),
            floatArrayOf(1439.5357666015625f, -1708.661865234375f),
            LayaQuestion.yesNo("Does the user threaten to cancel or leave?"), LayaCalibration())
        assertEquals(.0041, noul["noul"])
        assertEquals(.9959, noul["confidence"])
    }

    @Test fun layaCalibrationBucketsDoNotCalibrateTheActionHead() {
        val q = LayaQuestion.choice("?", linkedMapOf("first" to null, "second" to null))
        val raw = floatArrayOf(0f, .001f)
        val calibration = LayaCalibration(doubleArrayOf(2.0, 3.0, 4.0), mapOf("choice:2" to 0.0))
        assertEquals(.7310586f, LayaDecoder.probabilities(raw, q, calibration)[1], 1e-7f)
        assertEquals("choice:6-10", LayaCalibration.bucket("choice", 6))
        val a = LayaDecoder.decode(raw, floatArrayOf(1f, 0f), q, calibration)
        val b = LayaDecoder.decode(raw, floatArrayOf(1f, 0f), q, LayaCalibration())
        assertEquals(a["action"], b["action"])
        assertNotEquals(a["probabilities"], b["probabilities"])
        assertArrayEquals(floatArrayOf(.5f, 0f, 1f, 2f / 255), LayaDecoder.actFeatures(floatArrayOf(0f, 0f)), 1e-7f)
        assertEquals("first", LayaDecoder.decode(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), q, LayaCalibration())["choice"])
        assertEquals(.0624, LayaDecoder.round4(.06245), 0.0)
        assertEquals(.0625, LayaDecoder.round4(Math.nextUp(.06245)), 0.0)
        assertEquals((-0.0).toRawBits(), LayaDecoder.round4(-.000001).toRawBits())
    }

    @Test fun calibrationRejectsMalformedConfigurationRatherThanReturningNan() {
        assertThrows(IllegalArgumentException::class.java) { LayaCalibration(doubleArrayOf(1.0, Double.NaN, 1.0)) }
        assertThrows(IllegalArgumentException::class.java) { LayaCalibration(doubleArrayOf(1.0, -1.0, 1.0)) }
        assertThrows(IllegalArgumentException::class.java) { LayaCalibration(byOptions = mapOf("choice:6" to 1.0)) }
        assertThrows(IllegalArgumentException::class.java) { LayaCalibration.fromMap(emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { LayaDecoder.softmax(floatArrayOf(Float.POSITIVE_INFINITY, 0f)) }
    }

    @Test fun glinerCapturedReadmeLogitsAndGliclassDifferentLogitsAreNotLayaCalibrated() {
        // GLiNER-Decide android/app/src/debug/assets/gate_fixtures.json, readme_01_customer_support_intent.
        val gliner = DecisionMath.softmax32(floatArrayOf(-4.8493146896362305f, 3.5138485431671143f,
            -3.871110439300537f, -4.253898620605469f, -5.772402763366699f, -5.490521430969238f,
            -5.102658271789551f, -3.5973589420318604f, -4.188627243041992f))
        assertEquals(.997067391872406f, gliner[1], 2e-7f)
        // GLiClass fixtures/oracle_public.json, semif_000 (arithmetic only; no dataset text copied).
        val gliclass = DecisionMath.softmax32(floatArrayOf(4.308628559112549f, 3.846890449523926f, .7567108869552612f))
        assertArrayEquals(floatArrayOf(.6028247475624084f, .3798925578594208f, .017282750457525253f), gliclass, 1e-7f)
        // Open-Decision divides by 1.05 in float64; Julia uses 1.0 in float64.
        val julia = DecisionMath.softmax64(floatArrayOf(0f, 1.05f))
        val deberta = DecisionMath.softmax64(floatArrayOf(0f, 1.05f), 1.05)
        // Decimal 1.05 is first stored as binary32 by the graph, then widened to float64.
        assertEquals(.7310585697012547, deberta[1], 1e-12)
        assertTrue(julia[1] > deberta[1])
        assertThrows(IllegalArgumentException::class.java) { DecisionMath.softmax64(floatArrayOf(0f), 0.0) }
    }

    @Test fun capturedRealCpuOutputsFromAllFiveLiteralRequestsRemainRegressionFixtures() {
        // Captured 2026-11-11 with CPU CompiledModel 2.3.0 by verify_text_decisions.py, using
        // hash-verified catalog files and bit-exact parity of ALL Kotlin inputs. These are stored
        // observations, not generated test expectations. Full requests/sources: exporter + ATTRIBUTION.md.
        val calibration = LayaCalibration(doubleArrayOf(2.042901414492613, 3.684653192472766, 3.629100511692587),
            mapOf("choice:2" to 1.397546128996061))
        val q = LayaQuestion.choice("What does the customer need?", linkedMapOf("refund" to "money returned", "help" to "technical help"))
        val laya = LayaDecoder.decode(floatArrayOf(3.8331592082977295f, -3.693040370941162f),
            floatArrayOf(1370.7567138671875f, -1635.1903076171875f), q, calibration)
        assertEquals("refund", laya["choice"])
        assertEquals(linkedMapOf("refund" to .9954, "help" to .0046), laya["probabilities"])
        assertArrayEquals(doubleArrayOf(.7925332115040093, .20730663922751197, .00016014926847869574),
            DecisionMath.softmax64(floatArrayOf(-2.0680582523345947f, -3.4090936183929443f, -10.574941635131836f)), 1e-12)
        assertArrayEquals(floatArrayOf(.8847068548202515f, .06336815655231476f, .033833395689725876f,
            .015832342207431793f, .002259274944663048f), DecisionMath.softmax32(floatArrayOf(5.6794891357421875f,
            3.04319429397583f, 2.4156811237335205f, 1.656287670135498f, -.29072338342666626f)), 1e-7f)
        assertArrayEquals(doubleArrayOf(.03803211820197495, .10569905513662696, .7541915397352217,
            .041141007802654325, .06093627912352213), DecisionMath.softmax64(floatArrayOf(-2.465506076812744f,
            -1.392232894897461f, .6710700392723083f, -2.383002996444702f, -1.9705384969711304f), 1.05), 1e-12)
        // Actual GLiNER source oracle says YES; passage entails NO. Assert reproducibility only.
        assertArrayEquals(floatArrayOf(.9962641596794128f, .003735815640538931f),
            DecisionMath.softmax32(floatArrayOf(2.9081079959869385f, -2.677938461303711f)), 1e-7f)
    }
}
