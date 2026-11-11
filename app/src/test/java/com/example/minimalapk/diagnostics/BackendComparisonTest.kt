package com.example.minimalapk.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BackendComparisonTest {
    private val input = comparisonInput(2)
    private val resource = BackendComparisonResource("YOLO test", "asset:test", 123, "a".repeat(64),
        listOf(1, 2, 2, 3), listOf(1, 2))

    private class Clock {
        var nanos = 0L
        fun read() = nanos
        fun advance(ms: Long) { nanos += ms * 1_000_000L }
    }

    private fun run(
        clock: Clock = Clock(),
        plan: BackendComparisonPlan = BackendComparisonPlan(),
        cancellation: BackendComparisonCancellation = BackendComparisonCancellation(clock = clock::read),
        backends: List<ComparisonBackend> = ComparisonBackend.entries,
        unavailable: (ComparisonBackend) -> String? = { null },
        create: (ComparisonBackend) -> BackendComparisonSession,
    ) = BackendComparisonRunner(clock::read).run(resource, input, comparisonInputHash(input), "test device",
        plan, cancellation, unavailable, create, backends)

    private open class Session : BackendComparisonSession {
        var closed = 0
        override val details = "fake runtime"
        override fun load() {}
        override fun write(values: FloatArray) {}
        override fun infer() {}
        override fun readback() = floatArrayOf(.25f, .75f)
        override fun close() { closed++ }
    }

    @Test
    fun allBackendsUseSameInputSeriallyAndReadbackCountsInCompletionLatency() {
        val clock = Clock()
        val events = mutableListOf<String>()
        val threads = mutableSetOf<Long>()
        var open = 0
        val report = run(clock = clock) { backend ->
            assertEquals(0, open)
            events += "create:$backend"
            object : Session() {
                var iteration = 0
                private fun stage(name: String, ms: Long) {
                    threads += Thread.currentThread().id
                    events += "$name:$backend"
                    clock.advance(ms)
                }
                override fun load() { open++; stage("load", 10) }
                override fun write(values: FloatArray) {
                    assertSame(input, values)
                    assertArrayEquals(input, values, 0f)
                    stage("write", 1)
                }
                override fun infer() { stage("infer", listOf(100L, 5L, 7L, 9L)[iteration++]) }
                override fun readback(): FloatArray { stage("readback", 2); return super.readback() }
                override fun close() { open--; stage("close", 3); super.close() }
            }
        }
        assertEquals(1, threads.size)
        assertEquals(0, open)
        assertEquals(ComparisonBackend.entries, report.results.map { it.backend })
        report.results.forEach {
            assertEquals(ComparisonStatus.COMPLETE, it.status)
            assertEquals(1, it.warmed)
            assertEquals(3, it.measured.size)
            assertEquals(10.0, it.loadMs!!, 0.0)
            assertEquals(3.0, it.closeMs!!, 0.0)
            assertEquals(7.0, it.median("infer")!!, 0.0)
            assertEquals(9.0, it.completionMedianMs!!, 0.0)
            assertTrue(it.iterations.all { iteration -> iteration.output?.elements == 2 })
        }
        assertTrue(events.indexOf("close:CPU") < events.indexOf("create:GPU"))
        assertTrue(events.indexOf("close:GPU") < events.indexOf("create:NPU"))
        assertFalse(report.logLines().joinToString().contains("FPS="))
        assertTrue(report.compactLines().any { "GPU+CPU" in it && "实际未知" in it })
        assertTrue(report.compactLines().any { "NPU" in it && "回退 CPU" in it })
    }

    @Test
    fun failedPartialLoadAndInferenceAreTimedClosedAndDoNotHideLaterBackend() {
        val clock = Clock()
        val sessions = mutableMapOf<ComparisonBackend, Session>()
        val report = run(clock = clock, plan = BackendComparisonPlan(0, 1)) { backend ->
            object : Session() {
                override fun load() {
                    clock.advance(11)
                    if (backend == ComparisonBackend.CPU) error("failed after environment allocation")
                }
                override fun infer() {
                    clock.advance(17)
                    if (backend == ComparisonBackend.GPU) error("invoke failed")
                }
                override fun close() { clock.advance(5); super.close() }
            }.also { sessions[backend] = it }
        }
        assertEquals(listOf(ComparisonStatus.FAILED, ComparisonStatus.FAILED, ComparisonStatus.COMPLETE),
            report.results.map { it.status })
        assertEquals(listOf("load", "infer", "complete"), report.results.map { it.stage })
        assertEquals(11.0, report.results.first().loadMs!!, 0.0)
        assertEquals(17.0, report.results[1].iterations.single().timingsMs.getValue("infer"), 0.0)
        assertTrue(report.results.all { it.closeMs == 5.0 })
        assertTrue(sessions.values.all { it.closed == 1 })
        assertTrue(report.results[1].measured.isEmpty())
    }

    @Test
    fun cancellationDuringInvokeDrainsReadbackClosesAndSkipsAllLaterBackends() {
        val clock = Clock()
        val cancellation = BackendComparisonCancellation(clock = clock::read)
        val events = mutableListOf<String>()
        val report = run(clock = clock, cancellation = cancellation) { backend ->
            events += "create:$backend"
            object : Session() {
                override fun infer() { events += "infer"; cancellation.cancel("leave") }
                override fun readback(): FloatArray { events += "readback"; return super.readback() }
                override fun close() { events += "close"; super.close() }
            }
        }
        assertEquals(listOf("create:CPU", "infer", "readback", "close"), events)
        assertTrue(report.cancelled)
        assertTrue(report.results.all { it.status == ComparisonStatus.CANCELLED })
        assertTrue(report.results.all { it.measured.isEmpty() })
        assertNotNull(report.results.first().closeMs)
        assertNull(report.results[1].loadMs)
    }

    @Test
    fun cancellationDuringLoadStillClosesWithoutWritingOrInvoking() {
        val cancellation = BackendComparisonCancellation()
        val events = mutableListOf<String>()
        val report = run(cancellation = cancellation) {
            object : Session() {
                override fun load() { events += "load"; cancellation.cancel("background") }
                override fun write(values: FloatArray) { error("must not write") }
                override fun infer() { error("must not invoke") }
                override fun close() { events += "close" }
            }
        }
        assertEquals(listOf("load", "close"), events)
        assertTrue(report.results.first().iterations.isEmpty())
        assertTrue(report.results.all { it.status == ComparisonStatus.CANCELLED })
    }

    @Test
    fun budgetAndCloseFailureCannotReportCompleteOrStartOverlappingBackend() {
        val clock = Clock()
        val cancellation = BackendComparisonCancellation(clock = clock::read, budgetMs = 5)
        val session = object : Session() { override fun load() { clock.advance(6) } }
        val report = run(clock = clock, cancellation = cancellation) { session }
        assertEquals(1, session.closed)
        assertTrue(report.cancelled)
        assertTrue(report.results.all { it.status == ComparisonStatus.CANCELLED })

        var created = 0
        val closeFailed = run(plan = BackendComparisonPlan(0, 1)) {
            created++
            object : Session() { override fun close() { error("native close failed") } }
        }
        assertEquals(1, created)
        assertEquals(ComparisonStatus.FAILED, closeFailed.results.first().status)
        assertEquals("close", closeFailed.results.first().stage)
        assertTrue(closeFailed.results.first().closeError!!.contains("native close failed"))
        assertTrue(closeFailed.results.drop(1).all { it.status == ComparisonStatus.CANCELLED })
    }

    @Test
    fun nonFiniteOrWrongSizeOutputsFailInsteadOfPublishingValidTimings() {
        listOf(floatArrayOf(Float.NaN, 1f), floatArrayOf(1f, Float.POSITIVE_INFINITY),
            floatArrayOf(Float.NEGATIVE_INFINITY, 1f), floatArrayOf(), floatArrayOf(1f)).forEach { invalid ->
            val session = object : Session() { override fun readback() = invalid }
            val report = run(plan = BackendComparisonPlan(0, 1), backends = listOf(ComparisonBackend.CPU)) { session }
            val result = report.results.single()
            assertEquals(ComparisonStatus.FAILED, result.status)
            assertEquals("validate", result.stage)
            assertTrue(result.measured.isEmpty())
            assertNotNull(result.iterations.single().timingsMs["readback"])
            assertEquals(1, session.closed)
        }
    }

    @Test
    fun failedReadbackIsTimedAndClosedAndNeverCountedAsACompletedMeasurement() {
        val clock = Clock()
        val session = object : Session() {
            override fun infer() { clock.advance(3) }
            override fun readback(): FloatArray { clock.advance(9); error("GPU readback failed") }
            override fun close() { clock.advance(4); super.close() }
        }
        val report = run(clock = clock, plan = BackendComparisonPlan(0, 1),
            backends = listOf(ComparisonBackend.GPU)) { session }
        val result = report.results.single()
        assertEquals(ComparisonStatus.FAILED, result.status)
        assertEquals("readback", result.stage)
        assertEquals(9.0, result.iterations.single().timingsMs.getValue("readback"), 0.0)
        assertEquals(12.0, result.iterations.single().timingsMs.getValue("infer+readback"), 0.0)
        assertEquals(4.0, result.closeMs!!, 0.0)
        assertEquals(1, session.closed)
        assertNull(result.completionMedianMs)
        assertTrue(result.measured.isEmpty())
    }

    @Test
    fun filtersApiAbiSocBeforeAllocatingAndRuntimeUnavailableStillCloses() {
        val x86 = BackendComparisonDevice(35, listOf("x86_64"), "unknown", "emulator")
        val old = BackendComparisonDevice(30, listOf("arm64-v8a"), "Qualcomm", "old")
        val tensor = BackendComparisonDevice(35, listOf("arm64-v8a"), "Google", "pixel")
        val qti = BackendComparisonDevice(31, listOf("arm64-v8a"), " QTI ", "supported")
        assertNull(x86.unavailable(ComparisonBackend.CPU))
        assertNull(x86.unavailable(ComparisonBackend.GPU))
        assertNotNull(x86.unavailable(ComparisonBackend.NPU))
        assertNotNull(old.unavailable(ComparisonBackend.NPU))
        assertNotNull(tensor.unavailable(ComparisonBackend.NPU))
        assertNull(qti.unavailable(ComparisonBackend.NPU))
        assertNotNull(qti.copy(abis = listOf("armeabi-v7a")).unavailable(ComparisonBackend.CPU))

        val session = object : Session() {
            override fun load() { throw BackendComparisonUnavailable("runtime GPU absent") }
        }
        val report = run(unavailable = x86::unavailable) { session }
        assertEquals(listOf(ComparisonStatus.SKIPPED, ComparisonStatus.SKIPPED, ComparisonStatus.SKIPPED),
            report.results.map { it.status })
        assertEquals(2, session.closed)
        assertNull(report.results.last().loadMs)
    }

    @Test
    fun fixedInputHashIsDeterministicAndIterationsAreBounded() {
        val values = comparisonInput(640)
        assertEquals(640 * 640 * 3, values.size)
        assertTrue(values.all { it.isFinite() && it in 0f..1f })
        assertEquals(comparisonInputHash(values), comparisonInputHash(comparisonInput(640)))
        val changed = values.copyOf().apply { this[0] = 1f }
        assertNotEquals(comparisonInputHash(values), comparisonInputHash(changed))
        assertEquals("df3f619804a92fdb4057192dc43dd748ea778adc52bc498ce80524c014b81119",
            comparisonInputHash(floatArrayOf(0f)))
        assertTrue(runCatching { BackendComparisonPlan(repetitions = 100) }.isFailure)
        assertTrue(runCatching { BackendComparisonPlan(warmups = 20) }.isFailure)
        assertTrue(runCatching { BackendComparisonPlan(budgetMs = 0) }.isFailure)
    }

    @Test
    fun workerRejectsRepeatStartUntilCancelledNativeCallAndCleanupDrainOnOwnerThread() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val done = CountDownLatch(1)
        val worker = BackendComparisonWorker()
        val operations = mutableListOf<Pair<String, Long>>()
        val runId = worker.start(update = { if (!it.running) done.countDown() }) { cancellation, _ ->
            run(cancellation = cancellation) {
                object : Session() {
                    override fun load() { operations += "load" to Thread.currentThread().id }
                    override fun infer() {
                        operations += "infer" to Thread.currentThread().id
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    override fun readback(): FloatArray {
                        operations += "readback" to Thread.currentThread().id
                        return super.readback()
                    }
                    override fun close() {
                        operations += "close" to Thread.currentThread().id
                        closing.countDown()
                        check(allowClose.await(5, TimeUnit.SECONDS))
                    }
                }
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(worker.cancel(runId!! + 1))
            assertTrue(worker.cancel(runId, "leave"))
            assertTrue(worker.state.running)
            assertNull(worker.start { _, _ -> error("second native worker must not be started") })
        } finally { release.countDown() }
        try {
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertTrue(worker.state.running)
            assertNull(worker.start { _, _ -> error("must drain native close before another run") })
        } finally { allowClose.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(worker.state.running)
        assertEquals(listOf("load", "infer", "readback", "close"), operations.map { it.first })
        assertEquals(1, operations.map { it.second }.distinct().size)
        assertNotEquals(Thread.currentThread().id, operations.first().second)
    }

    @Test
    fun lifecycleInvalidationAndCallbackFailureDoNotStrandWorkerGuard() {
        val current = AtomicBoolean(false)
        val worker = BackendComparisonWorker(log = { error("logging unavailable") })
        assertNull(worker.start(stillNeeded = current::get) { _, _ -> error("inactive") })
        current.set(true)
        val finished = CountDownLatch(1)
        worker.start(stillNeeded = current::get, update = {
            if (!it.running) finished.countDown()
            error("UI detached")
        }) { cancellation, _ ->
            current.set(false)
            run(cancellation = cancellation) { error("invalidated before allocation") }
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(worker.state.running)
        assertTrue(worker.state.report!!.cancelled)
    }
}
