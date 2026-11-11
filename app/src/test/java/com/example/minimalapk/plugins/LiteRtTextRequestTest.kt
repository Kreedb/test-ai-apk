package com.example.minimalapk.plugins

import com.example.minimalapk.litert.text.LayaDecisionEngine
import com.example.minimalapk.litert.text.LayaDecisionResult
import com.example.minimalapk.litert.text.LayaQuestion
import com.example.minimalapk.litert.text.TextDecisionEngine
import com.example.minimalapk.litert.text.TextDecisionRequest
import com.example.minimalapk.litert.text.TextDecisionResult
import com.example.minimalapk.litert.text.TextDecisionTasks
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiteRtTextRequestTest {
    private fun settings(
        mode: String? = null,
        task: String = LITERT_LAYA_TASK,
        extra: Map<String, String> = emptyMap(),
    ): PluginSettings = PluginSettings(config = mapOf(
        "task_id" to task,
        "input" to "Please return the duplicate payment.",
        "question" to "What does the customer need?",
        "choices" to "refund: money returned\nhelp: technical help",
        "laya_levels" to "not urgent\nsoon\ncritical deadline or blocking issue",
    ) + (mode?.let { mapOf("laya_mode" to it) } ?: emptyMap()) + extra)

    private class LayaEngine(
        private val answer: (String, LayaQuestion) -> LayaDecisionResult,
    ) : LayaDecisionEngine {
        var typedCalls = 0
        var choiceCalls = 0
        var closes = 0
        var lastQuestion: LayaQuestion? = null
        override fun decide(text: String, question: LayaQuestion): LayaDecisionResult {
            typedCalls++
            lastQuestion = question
            return answer(text, question)
        }
        override fun decide(request: TextDecisionRequest): TextDecisionResult {
            choiceCalls++
            error("Laya must use the typed API, including choice so its action head is reported")
        }
        override fun close() { closes++ }
    }

    private fun result(type: String, values: Map<String, Any?> = emptyMap(), details: String = "37 of 256 tokens.",
        fallback: String? = null): LayaDecisionResult = LayaDecisionResult(linkedMapOf<String, Any?>(
        "type" to type, "confidence" to .958, "action" to mapOf("act_probability" to .73),
    ) + values, 12.5, "CPU", 256, fallback, details)

    @Test fun existingChoiceConfigKeepsKeysAndUsesLayaTypedEngineWithCalibrationAndAction() {
        val request = liteRtTextRunRequest(settings()) as LiteRtTextRunRequest.Laya
        assertEquals("choice", request.question.type)
        assertEquals(listOf("refund: money returned", "help: technical help"), request.question.renderedOptions())
        val engine = LayaEngine { text, _ ->
            assertEquals("Please return the duplicate payment.", text)
            result("choice", mapOf("choice" to "refund", "probabilities" to linkedMapOf("refund" to .9954, "help" to .0046)))
        }
        val report = liteRtTextRun(request, engine)
        assertEquals(1, engine.typedCalls)
        assertEquals(0, engine.choiceCalls)
        assertTrue(report.messages.first().text.contains("refund"))
        val output = report.messages.joinToString("\n") { it.text }
        assertTrue(output.contains("99.54%"))
        assertTrue(output.contains("类型/选项数温度校准"))
        assertTrue(output.contains("act_probability 73.00%"))
        assertTrue(output.contains("置信度 95.80%"))
        assertEquals(PluginExecution(12.5, "CPU"), report.execution)
    }

    @Test fun scoreKeepsOrderedDescriptionsIncludingColonsAndDisplaysExpectationNotArgmax() {
        val request = liteRtTextRunRequest(settings("score", extra = mapOf(
            "laya_levels" to " low: no deadline \n\n middle \n high ", "choices" to "invalid unused choices",
        ))) as LiteRtTextRunRequest.Laya
        assertEquals("score", request.question.type)
        assertEquals(listOf("low: no deadline", "middle", "high"), request.question.criteria)
        assertEquals(listOf("level 0: low: no deadline", "level 1: middle", "level 2: high"), request.question.renderedOptions())
        val report = liteRtTextRun(request, LayaEngine { _, _ -> result("score", mapOf(
            "score" to 1.2814, "probabilities" to linkedMapOf("2" to .4695, "0" to .1881, "1" to .3424),
        )) })
        val output = report.messages.joinToString("\n") { it.text }
        assertTrue(report.messages.first().text.contains("1.2814"))
        assertTrue(report.messages.first().text.contains("0–2"))
        assertFalse(output.contains("建议选项"))
        assertTrue(output.indexOf("0：low: no deadline") < output.indexOf("1：middle"))
        assertTrue(output.indexOf("1：middle") < output.indexOf("2：high"))
        assertTrue(output.contains("18.81%"))
    }

    @Test fun yesNoNeedsNoChoicesAndReturnsContinuousTrueProbabilityWithFixedFalseTrueOrder() {
        val request = liteRtTextRunRequest(settings("noul", extra = mapOf(
            "choices" to "", "laya_levels" to "", "question" to "Does the user threaten to cancel or leave?",
        ))) as LiteRtTextRunRequest.Laya
        assertEquals(2, request.question.qtype)
        assertEquals(listOf("false: no, the statement does not hold", "true: yes, the statement holds"),
            request.question.renderedOptions())
        val report = liteRtTextRun(request, LayaEngine { _, _ -> result("noul", mapOf("noul" to .1805)) })
        val output = report.messages.joinToString("\n") { it.text }
        assertTrue(report.messages.first().text.contains("true 概率：18.05%"))
        assertTrue(output.contains("false：81.95%"))
        assertFalse(output.contains("建议选项"))
        assertTrue(output.contains("连续概率"))
    }

    @Test fun otherFourTasksRetainTheirOwnChoiceApiAndNeverUseLayaCalibration() {
        for (task in TextDecisionTasks.ids - LITERT_LAYA_TASK) {
            val parsed = liteRtTextRunRequest(settings(task = task)) as LiteRtTextRunRequest.Choice
            assertEquals(listOf("refund" to "money returned", "help" to "technical help"), parsed.value.options)
            var calls = 0
            val engine = object : TextDecisionEngine {
                override fun decide(request: TextDecisionRequest): TextDecisionResult {
                    calls++
                    assertEquals(parsed.value, request)
                    return TextDecisionResult(listOf("refund" to .7f, "help" to .3f), "refund", 1.0, "CPU", 128)
                }
                override fun close() = Unit
            }
            val report = liteRtTextRun(parsed, engine)
            assertEquals(1, calls)
            assertTrue(report.messages.first().text.contains("refund"))
            assertFalse(report.messages.any { it.text.contains("Laya") || it.text.contains("act_probability") })
        }
    }

    @Test fun unknownModesTasksAndNonLayaScoreOrYesNoAreRejectedBeforeEngineUse() {
        for (mode in listOf("", "yes/no", "unexpected")) {
            assertThrows(IllegalArgumentException::class.java) { liteRtTextRunRequest(settings(mode)) }
        }
        for (task in TextDecisionTasks.ids - LITERT_LAYA_TASK) {
            for (mode in listOf("score", "noul")) {
                assertThrows(IllegalArgumentException::class.java) { liteRtTextRunRequest(settings(mode, task)) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { liteRtTextRunRequest(settings(task = "not-a-task")) }
        val choiceOnly = object : TextDecisionEngine {
            override fun decide(request: TextDecisionRequest): TextDecisionResult = error("must not fall back to choice")
            override fun close() = Unit
        }
        assertThrows(IllegalStateException::class.java) { liteRtTextRun(liteRtTextRunRequest(settings("score")), choiceOnly) }
    }

    @Test fun inputAndModeSpecificOptionsAreValidatedWithoutReinterpretingHiddenFields() {
        for (mode in listOf("choice", "score", "noul")) {
            for (extra in listOf(mapOf("input" to " "), mapOf("question" to ""),
                mapOf("input" to "x".repeat(4097)), mapOf("question" to "x".repeat(1025)),
                mapOf("input" to "unpaired\ud800"))) {
                assertThrows(IllegalArgumentException::class.java) { liteRtTextRunRequest(settings(mode, extra = extra)) }
            }
        }
        for (levels in listOf("one", "one\n one ", (0..12).joinToString("\n"), "x".repeat(257) + "\ny")) {
            assertThrows(IllegalArgumentException::class.java) { liteRtTextRunRequest(settings("score", extra = mapOf("laya_levels" to levels))) }
        }
        for (choices in listOf("a: first\na: second", ": no key\nb: other", "one")) {
            assertThrows(IllegalArgumentException::class.java) { liteRtTextRunRequest(settings("choice", extra = mapOf("choices" to choices))) }
        }
        // Stale text from a different mode is preserved in settings but never becomes its schema.
        assertTrue(liteRtTextRunRequest(settings("noul", extra = mapOf("choices" to ":", "laya_levels" to "one")))
            is LiteRtTextRunRequest.Laya)
        val existing = liteRtTextRequest(settings())
        assertEquals(listOf("refund", "help"), existing.options.map { it.first })
    }

    @Test fun boundedReportsPreserveCalibrationFallbackAndProbabilitySemantics() {
        val levels = (0..11).joinToString("\n") { "$it " + "😀".repeat(100) }
        val request = liteRtTextRunRequest(settings("score", extra = mapOf(
            "laya_levels" to levels, "question" to "Q".repeat(1024), "input" to "😀".repeat(2048),
        )))
        val report = liteRtTextRun(request, LayaEngine { _, _ -> result("score", mapOf("score" to 5.5,
            "probabilities" to (0..11).associate { it.toString() to .0833 }),
            details = "details ".repeat(100), fallback = "native reason ".repeat(100)) })
        assertTrue(report.messages.size <= PluginHost.MAX_MESSAGES)
        assertTrue(report.messages.all { it.text.length <= PluginHost.MAX_MESSAGE_CHARS })
        assertTrue(report.messages.any { "act_probability" in it.text })
        assertTrue(report.messages.any { "CPU 回退" in it.text })
        assertTrue(report.messages.any { "已截断" in it.text })
        assertTrue(report.messages.any { "8.33%" in it.text }) // .9996 total is NOT renormalized.
        for (message in report.messages) {
            val chars = message.text
            chars.forEachIndexed { index, c ->
                if (c.isHighSurrogate()) assertTrue(index + 1 < chars.length && chars[index + 1].isLowSurrogate())
                if (c.isLowSurrogate()) assertTrue(index > 0 && chars[index - 1].isHighSurrogate())
            }
        }
    }

    @Test fun requestErrorsDoNotCloseEngineAndNativeFailuresOrCancellationAreNotMisreported() {
        val request = liteRtTextRunRequest(settings("noul"))
        var failRequest = true
        val engine = LayaEngine { _, _ ->
            if (failRequest) throw IllegalArgumentException("Request does not fit the token window")
            result("noul", mapOf("noul" to .4))
        }
        val error = liteRtTextRun(request, engine)
        assertEquals(MessageKind.ERROR, error.messages.single().kind)
        assertNull(error.execution)
        failRequest = false
        assertNotNull(liteRtTextRun(request, engine).execution)
        assertEquals(2, engine.typedCalls)
        assertEquals(0, engine.closes)
        assertThrows(CancellationException::class.java) {
            liteRtTextRun(request, LayaEngine { _, _ -> throw CancellationException("cancelled") })
        }
        assertThrows(IllegalStateException::class.java) {
            liteRtTextRun(request, LayaEngine { _, _ -> error("native graph failed") })
        }
        assertThrows(IllegalStateException::class.java) {
            liteRtTextRun(request, LayaEngine { _, _ -> result("noul", mapOf("noul" to Double.NaN)) })
        }
        assertThrows(IllegalStateException::class.java) {
            liteRtTextRun(request, LayaEngine { _, _ -> result("choice", mapOf("choice" to "yes")) })
        }
    }

    @Test fun invalidTokenRequestInsideRealWorkKeepsLoadedOwnerAndSuppressesCancelledErrorPublication() {
        val plugin = object : CameraPlugin {
            override val descriptor = PluginDescriptor("text_test", "text test", setOf(PluginCapability.TEXT))
            override fun onEvent(event: PluginEvent, context: PluginContext) = Unit
        }
        PluginHost(listOf(plugin)).use { host ->
            host.configure(mapOf("text_test" to PluginSettings(true)))
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            val context = host.contextFor("text_test")!!
            val opens = AtomicInteger()
            val errors = AtomicInteger()
            val first = CountDownLatch(1)
            val second = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val engine = LayaEngine { text, _ ->
                when (text) {
                    "overflow" -> throw IllegalArgumentException("Input exceeds encoded token limit")
                    "cancelled-overflow" -> {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        throw IllegalArgumentException("Obsolete invalid input")
                    }
                    else -> result("noul", mapOf("noul" to .4))
                }
            }
            val work = LiteRtWork("text-mode-test", open = { opens.incrementAndGet(); engine },
                status = { _, _, error -> if (error) errors.incrementAndGet() }, ready = {})
            fun request(text: String) = liteRtTextRunRequest(settings("noul", extra = mapOf("input" to text)))
            try {
                work.start(context, deferLoad = true)
                work.submit(context) { loaded, valid ->
                    val report = liteRtTextRun(request("overflow"), loaded)
                    assertTrue(work.publishIf(valid, context, PluginOutput(100, report.messages, execution = report.execution)))
                    first.countDown()
                }
                assertTrue(first.await(5, TimeUnit.SECONDS))
                assertTrue(work.isReady(context))
                assertEquals(MessageKind.ERROR, host.snapshot.value.reports.single().output.messages.single().kind)
                work.submit(context) { loaded, valid ->
                    val report = liteRtTextRun(request("valid"), loaded)
                    assertTrue(work.publishIf(valid, context, PluginOutput(200, report.messages, execution = report.execution)))
                    second.countDown()
                }
                assertTrue(second.await(5, TimeUnit.SECONDS))
                work.submit(context) { loaded, valid ->
                    val report = liteRtTextRun(request("cancelled-overflow"), loaded)
                    assertFalse(work.publishIf(valid, context, PluginOutput(300, report.messages)))
                    cancelled.countDown()
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                work.cancel()
                release.countDown()
                assertTrue(cancelled.await(5, TimeUnit.SECONDS))
                assertEquals(1, opens.get())
                assertEquals(0, errors.get())
                assertEquals(0, engine.closes)
                assertTrue(work.isReady(context))
                assertEquals(200L, host.snapshot.value.reports.single().output.timestampNanos)
            } finally { release.countDown(); work.close() }
        }
    }
}
