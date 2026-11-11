package com.example.minimalapk.plugins

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LiteRtWorkTest {
    private class Model : AutoCloseable {
        val thread = Thread.currentThread()
        val closed = CountDownLatch(1)
        override fun close() { assertSame(thread, Thread.currentThread()); closed.countDown() }
    }
    private fun host(): PluginHost {
        val plugin = object : CameraPlugin {
            override val descriptor = PluginDescriptor("work", "work", setOf(PluginCapability.TEXT))
            override fun onEvent(event: PluginEvent, context: PluginContext) = Unit
        }
        return PluginHost(listOf(plugin)).also {
            it.configure(mapOf("work" to PluginSettings(true)))
            it.lifecycle(CameraLifecycle.ENTER)
            it.lifecycle(CameraLifecycle.FOREGROUND)
        }
    }
    private fun CountDownLatch.awaitResult() = assertTrue(await(5, TimeUnit.SECONDS))

    @Test fun cancelDuringNativeRunRejectsLatePublishAndKeepsEngineUsableOnItsOwner() {
        host().use { host ->
            val ready = CountDownLatch(1)
            val running = CountDownLatch(1)
            val release = CountDownLatch(1)
            val done = CountDownLatch(1)
            val nextDone = CountDownLatch(1)
            val published = AtomicBoolean(true)
            var model: Model? = null
            lateinit var work: LiteRtWork<Model>
            work = LiteRtWork("test-work", open = { Model().also { model = it } },
                status = { _, _, error -> assertFalse(error) }, ready = { ready.countDown() })
            val context = host.contextFor("work")!!
            try {
                work.start(context)
                ready.awaitResult()
                work.submit(context) { engine, valid ->
                    assertSame(engine.thread, Thread.currentThread())
                    running.countDown()
                    release.awaitResult()
                    published.set(work.publishIf(valid, context, PluginOutput(100, listOf(PluginMessage("stale")))))
                    done.countDown()
                }
                running.awaitResult()
                work.cancel()
                release.countDown()
                done.awaitResult()
                assertFalse(published.get())
                assertTrue(host.snapshot.value.reports.isEmpty())
                work.submit(context) { engine, valid ->
                    assertSame(engine.thread, Thread.currentThread())
                    assertTrue(work.publishIf(valid, context, PluginOutput(200, listOf(PluginMessage("new")))))
                    nextDone.countDown()
                }
                nextDone.awaitResult()
                assertEquals("new", host.snapshot.value.reports.single().output.messages.single().text)
            } finally { release.countDown(); work.close() }
            model!!.closed.awaitResult()
        }
    }

    @Test fun pendingDuringCompilationIsConflatedWithoutReadyTextOverwritingProcessing() {
        host().use { host ->
            val opening = CountDownLatch(1)
            val release = CountDownLatch(1)
            val opened = CountDownLatch(1)
            val ran = CountDownLatch(1)
            val values = Collections.synchronizedList(mutableListOf<Int>())
            val work = LiteRtWork("test-load", open = {
                opening.countDown(); release.awaitResult(); Model()
            }, status = { _, _, _ -> }, ready = { opened.countDown() })
            val context = host.contextFor("work")!!
            try {
                work.start(context)
                opening.awaitResult()
                (1..100).forEach { value -> work.submit(context) { _, _ -> values += value; ran.countDown() } }
                release.countDown()
                ran.awaitResult()
                assertEquals(listOf(100), values)
                // A request superseded the load notification; it must not overwrite processing text.
                assertEquals(1L, opened.count)
            } finally { release.countDown(); work.close() }
        }
    }

    @Test fun cancelWhileOpeningClosesOnlyAfterNativeOpenReturnsAndDoesNotRestoreReadyOutput() {
        host().use { host ->
            val opening = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val ready = AtomicBoolean(false)
            val ran = AtomicBoolean(false)
            val opens = AtomicInteger()
            val closes = AtomicInteger()
            val retried = CountDownLatch(1)
            val work = LiteRtWork("test-opening-cancel", open = {
                val owner = Thread.currentThread()
                val number = opens.incrementAndGet()
                if (number == 1) { opening.countDown(); release.awaitResult() }
                AutoCloseable {
                    assertSame(owner, Thread.currentThread())
                    closes.incrementAndGet()
                    closed.countDown()
                }
            }, status = { _, _, _ -> }, ready = { ready.set(true) })
            try {
                val context = host.contextFor("work")!!
                work.start(context); opening.awaitResult()
                work.submit(context) { _, _ -> ran.set(true) }
                work.cancel()
                assertEquals(1L, closed.count)
                release.countDown(); closed.awaitResult()
                assertFalse(ran.get())
                assertFalse(ready.get())
                assertEquals(1, closes.get())
                assertFalse(work.isReady(context))
                assertTrue(work.submit(context) { _, valid -> assertTrue(valid()); retried.countDown() })
                retried.awaitResult()
                assertEquals(2, opens.get())
                assertEquals("A fresh explicit request owns a fresh model", 1, closes.get())
            } finally { release.countDown(); work.close() }
        }
    }

    @Test fun oneRunningPlusLatestPendingAndDisableRejectsTheInFlightContext() {
        host().use { host ->
            val ready = CountDownLatch(1)
            val running = CountDownLatch(1)
            val release = CountDownLatch(1)
            val done = CountDownLatch(1)
            val values = Collections.synchronizedList(mutableListOf<Int>())
            lateinit var work: LiteRtWork<Model>
            work = LiteRtWork("test-queue", open = { Model() }, status = { _, _, _ -> }, ready = { ready.countDown() })
            val context = host.contextFor("work")!!
            try {
                work.start(context); ready.awaitResult()
                work.submit(context, replace = false) { _, valid ->
                    values += 1
                    running.countDown(); release.awaitResult()
                    assertFalse(work.publishIf(valid, context, PluginOutput(100)))
                    done.countDown()
                }
                running.awaitResult()
                (2..100).forEach { value -> work.submit(context, replace = false) { _, _ -> values += value } }
                host.configure(mapOf("work" to PluginSettings(false)))
                work.stop()
                release.countDown(); done.awaitResult()
                assertEquals(listOf(1), values)
                assertTrue(host.snapshot.value.reports.isEmpty())
            } finally { release.countDown(); work.close() }
        }
    }

    @Test fun requestGateRejectsEveryPreCancelGeneration() {
        val gate = LiteRtRequestGate()
        val first = gate.next()
        assertTrue(gate.accepts(first))
        val second = gate.next()
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }

    @Test fun deferredSceneDoesNotOpenAnyEngineUntilAnExplicitRequest() {
        host().use { host ->
            val opened = AtomicBoolean(false)
            val ran = CountDownLatch(1)
            val work = LiteRtWork("test-lazy", open = { opened.set(true); Model() },
                status = { _, _, _ -> }, ready = {})
            try {
                val context = host.contextFor("work")!!
                work.start(context, deferLoad = true)
                assertFalse(opened.get())
                assertFalse(work.isReady(context))
                work.submit(context) { _, valid -> assertTrue(valid()); ran.countDown() }
                ran.awaitResult()
                assertTrue(opened.get())
            } finally { work.close() }
        }
    }

    @Test fun failedDeferredOpenReportsTheRealNeedHaveReasonEvenWhenAnActionWasWaiting() {
        host().use { host ->
            val failure = CountDownLatch(1)
            val errors = Collections.synchronizedList(mutableListOf<String>())
            val work = LiteRtWork<AutoCloseable>("test-resource-gate", open = {
                error("RAM++ requires 3.50 GiB; have 1.00 GiB")
            }, status = { _, message, error -> if (error) { errors += message; failure.countDown() } }, ready = {})
            try {
                val context = host.contextFor("work")!!
                work.start(context, deferLoad = true)
                work.submit(context) { _, _ -> fail("must not run without a model") }
                failure.awaitResult()
                assertEquals("RAM++ requires 3.50 GiB; have 1.00 GiB", errors.last())
                assertFalse(work.submit(context) { _, _ -> fail("failed model must not be used") })
                assertEquals("RAM++ requires 3.50 GiB; have 1.00 GiB", errors.last())
            } finally { work.close() }
        }
    }
}
