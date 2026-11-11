package com.example.minimalapk.litert.text

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextDecisionHostTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun optionsPreserveDescriptionsAndRejectAmbiguousKeys() {
        val parsed = TextDecisionRequest.parseOptions(listOf("  alpha: x:y  ", " ", " beta "))
        assertEquals(listOf("alpha" to "x:y", "beta" to ""), parsed)
        val request = TextDecisionRequest("hello", "question?", parsed)
        assertEquals("x:y", request.optionText(0))
        assertEquals("beta", request.optionText(1))
        assertThrows(IllegalArgumentException::class.java) { TextDecisionRequest.parseOptions(listOf(": missing")) }
        assertThrows(IllegalArgumentException::class.java) {
            TextDecisionRequest("text", "question", listOf("a" to "first", "a" to "second"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextDecisionRequest("text", "question", listOf("" to "first", "b" to "second"))
        }
    }

    @Test fun workIsBoundedBeforeTokenizationAndMalformedUtf16IsNotSilentlyReplaced() {
        assertThrows(IllegalArgumentException::class.java) {
            TextDecisionRequest("a".repeat(32_769), "?", listOf("a" to "", "b" to ""))
        }
        assertThrows(IllegalArgumentException::class.java) { validateTextBudget(listOf("broken\ud800")) }
        validateTextBudget(listOf("OK 😀 東京"))
    }

    @Test fun resourceAndBackendErrorsPrecedeNativeModelLoading() {
        assertEquals(5, TextDecisionTasks.ids.size)
        val error = assertThrows(IllegalArgumentException::class.java) {
            TextDecisionTasks.create("text-decision-laya-multilingual", temporary.root, "cpu")
        }
        assertTrue(error.message!!.contains("token_embeddings.json is missing"))
        assertTrue(error.message!!.contains("laya_ml_act_head_fp32.tflite is missing"))
        assertThrows(IllegalArgumentException::class.java) {
            TextDecisionTasks.create("text-decision-julia-1", temporary.root, "npu")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextDecisionTasks.create("text-decision-unknown", temporary.root, "cpu")
        }
    }

    @Test fun jsonKeepsPythonOrderingNumbersAndUnicodeEscapes() {
        val value = HostJson.parse("""{"日本": [false, null, 2, 1e-5], "s": "\ud83d\ude00"}""")
        assertEquals("""{"日本": [false, null, 2, 1e-05], "s": "😀"}""", HostJson.stringify(value))
        assertEquals("""{"\u65e5\u672c": [false, null, 2, 1e-05], "s": "\ud83d\ude00"}""",
            HostJson.stringify(value, true))
        assertEquals("[1e+16, -0.0, 0.0001]", HostJson.stringify(listOf(1e16, -0.0, 0.0001)))
        for (invalid in listOf("[1,]", "{\"a\":1,\"a\":2}", "01", "1.", "\"bad\nline\"", "{} true")) {
            assertThrows(invalid, IllegalArgumentException::class.java) { HostJson.parse(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { HostJson.parse("[".repeat(65) + "0" + "]".repeat(65)) }
    }

    @Test fun mmapLookupUsesRealPadRowsAndRewritesEveryElementAcrossRequests() {
        val file = temporary.newFile()
        val bytes = ByteBuffer.allocate(3 * 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        // PAD is deliberately nonzero. Other rows include negative zero and a subnormal.
        listOf(0x3c00, 0x4000, 0x4200, 0x4400, 0xbc00, 0x8000, 0x0001, 0x7bff,
            0x3800, 0x3400, 0x3000, 0x2c00).forEach { bytes.putShort(it.toShort()) }
        file.writeBytes(bytes.array())
        val table = MappedEmbeddings(file, 3, 4)
        val first = table.gather(intArrayOf(1, 2), 3)
        assertArrayEquals(floatArrayOf(-1f, -0f, Math.scalb(1f, -24), 65504f,
            .5f, .25f, .125f, .0625f, 1f, 2f, 3f, 4f), first, 0f)
        assertEquals(Int.MIN_VALUE, first[1].toRawBits())
        first.fill(Float.NaN)
        val second = table.gather(intArrayOf(2), 3)
        assertSame(first, second)
        assertArrayEquals(floatArrayOf(.5f, .25f, .125f, .0625f,
            1f, 2f, 3f, 4f, 1f, 2f, 3f, 4f), second, 0f)
        assertThrows(IllegalArgumentException::class.java) { table.gather(intArrayOf(3), 3) }
        assertThrows(IllegalArgumentException::class.java) { table.gather(intArrayOf(0, 1), 1) }
        table.close()
        table.close()
        assertThrows(IllegalStateException::class.java) { table.gather(intArrayOf(0)) }
    }

    @Test fun malformedEmbeddingSizeFailsBeforeMapping() {
        val file = temporary.newFile()
        file.writeBytes(ByteArray(7))
        val failure = assertThrows(IllegalArgumentException::class.java) { MappedEmbeddings(file, 2, 2) }
        assertTrue(failure.message!!.contains("8 bytes"))
    }

    @Test fun binary16ExpansionMatchesIndependentArithmeticForEveryFiniteBitPattern() {
        for (bits in 0..65535) {
            val exponent = (bits ushr 10) and 31
            val fraction = bits and 1023
            if (exponent == 31) continue
            var value = if (exponent == 0) Math.scalb(fraction.toDouble(), -24)
            else Math.scalb(1.0 + fraction / 1024.0, exponent - 15)
            if (bits and 0x8000 != 0) value = -value
            assertEquals("half=$bits", value.toFloat().toRawBits(), MappedEmbeddings.halfToFloat(bits).toRawBits())
        }
        assertEquals(Float.POSITIVE_INFINITY, MappedEmbeddings.halfToFloat(0x7c00))
        assertTrue(MappedEmbeddings.halfToFloat(0x7c01).isNaN())
        assertEquals(0x7fc02000, MappedEmbeddings.halfToFloat(0x7c01).toRawBits())
    }

    @Test fun lifecycleDoesNotAcceptCrossThreadUseCloseOrCancelledRequests() {
        val lifetime = DecisionLifetime()
        var foreign: Throwable? = null
        val thread = Thread { try { lifetime.checkOpen() } catch (failure: Throwable) { foreign = failure } }
        thread.start(); thread.join()
        assertTrue(foreign is IllegalStateException)
        Thread.currentThread().interrupt()
        try { assertThrows(CancellationException::class.java) { lifetime.checkOpen() } }
        finally { Thread.interrupted() }
        var releases = 0
        lifetime.closeOnce { releases++ }
        lifetime.closeOnce { releases++ }
        assertEquals(1, releases)
        assertThrows(IllegalStateException::class.java) { lifetime.checkOpen() }
    }

    @Test fun distinctRoutingContractsAndUnusedRowsStayZero() {
        val (q, o) = DebertaInputs.routing(DecisionSpan(3, 5), listOf(DecisionSpan(6, 9), DecisionSpan(10, 11)))
        assertEquals(.5f, q[3], 0f)
        assertEquals(.5f, q[256 + 4], 0f)
        assertEquals(1f / 3, o[6], 0f)
        assertEquals(1f, o[256 + 10], 0f)
        assertEquals(0f, o[5], 0f) // [OPT] marker is NOT averaged.
        assertTrue(q.drop(2 * 256).all { it == 0f })
        assertTrue(o.drop(2 * 256).all { it == 0f })
        val routing = GliclassInputs.routing(intArrayOf(1, 7))
        assertEquals(1f, routing[1], 0f)
        assertEquals(1f, routing[128 + 7], 0f)
        assertEquals(2f, routing.sum(), 0f)
        val shorter = GlinerInputs.routing(intArrayOf(4, 8))
        assertTrue(shorter.drop(256).all { it == 0f })
        assertEquals(2f, shorter.sum(), 0f)
    }

    @Test fun glinerShapeBindingDoesNotAssumeArgsOrder() {
        val shapes = mapOf("args_2" to listOf(1, 128, 1024), "args_0" to listOf(1, 128),
            "args_1" to listOf(1, 32, 128))
        assertEquals(listOf("args_2", "args_0", "args_1"), GlinerInputs.resolveInputShapes(shapes::getValue).keys.toList())
        assertThrows(IllegalArgumentException::class.java) {
            GlinerInputs.resolveInputShapes { listOf(1, 128) }
        }
    }

    @Test fun glinerWordOffsetsUseOriginalCodePointsBeforeLowercaseExpansion() {
        val words = GlinerInputs.splitWords("İ 😀 E-mail user@example.com")
        assertEquals(listOf("i\u0307", "😀", "e-mail", "user@example.com"), words.map { it.text })
        assertEquals(listOf(0, 2, 4, 11), words.map { it.start })
        assertEquals(listOf(1, 3, 10, 27), words.map { it.end })
        assertEquals("Ready. ".plus('.'), GlinerInputs.collateText("Ready. "))
        assertEquals("Ready!", GlinerInputs.collateText("Ready!"))
    }
}
