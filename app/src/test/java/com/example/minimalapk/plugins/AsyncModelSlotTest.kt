package com.example.minimalapk.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AsyncModelSlotTest {
    private class Model(val key: Int, private val closed: CountDownLatch = CountDownLatch(1)) : AutoCloseable {
        val createdOn = Thread.currentThread()
        var closedOn: Thread? = null
        val closes = AtomicInteger()
        override fun close() {
            closedOn = Thread.currentThread()
            closes.incrementAndGet()
            closed.countDown()
        }
        fun awaitClose() = assertTrue(closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun slowOpenDoesNotBlockPreviewAndStaleLoadClosesBeforeLatestOpens() {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val opened = Collections.synchronizedList(mutableListOf<Model>())
        val keys = Collections.synchronizedList(mutableListOf<Int>())
        val slot = AsyncModelSlot<Int, Model>("slot-test", open = { key ->
            keys += key
            val model = Model(key).also { opened += it }
            if (key == 1) { opening.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            model
        }, ready = { _, _ -> ready.countDown() })
        try {
            slot.set(1)
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            assertNull(slot.runIfReady { _, _ -> "must not wait for load" })
            slot.set(2)
            slot.set(3)
            release.countDown()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), keys)
            opened[0].awaitClose()
            assertEquals(1, opened[0].closes.get())
            assertEquals(3, slot.runIfReady { key, model ->
                assertEquals(model.createdOn, Thread.currentThread())
                key
            })
        } finally {
            release.countDown()
            slot.close()
        }
        opened.last().awaitClose()
        opened.forEach { assertEquals(it.createdOn, it.closedOn) }
    }

    @Test
    fun disablingPreventsNewBorrowsAndDefersCloseUntilInferenceDrains() {
        val ready = CountDownLatch(1)
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val models = Collections.synchronizedList(mutableListOf<Model>())
        val caller = Executors.newSingleThreadExecutor()
        val slot = AsyncModelSlot<Int, Model>("slot-drain", open = { key -> Model(key).also { models += it } }, ready = { _, _ -> ready.countDown() })
        try {
            slot.set(1)
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val task = caller.submit<Int?> { slot.runIfReady { _, model ->
                running.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                assertEquals(0, model.closes.get())
                model.key
            } }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            slot.set(null)
            assertNull(slot.runIfReady { _, _ -> "disabled" })
            assertEquals(0, models.single().closes.get())
            release.countDown()
            assertEquals(1, task.get(5, TimeUnit.SECONDS))
            models.single().awaitClose()
            assertEquals(models.single().createdOn, models.single().closedOn)
        } finally {
            release.countDown()
            slot.close()
            caller.shutdown()
        }
    }

    @Test
    fun closeDuringOpenClosesTheLateResourceExactlyOnceWithoutReadyCallback() {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val readyCount = AtomicInteger()
        val models = Collections.synchronizedList(mutableListOf<Model>())
        val slot = AsyncModelSlot<Int, Model>("slot-close", open = { key ->
            Model(key, closed).also {
                models += it
                opening.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        }, ready = { _, _ -> readyCount.incrementAndGet() })
        slot.set(1)
        try {
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            slot.close()
            assertNull(slot.runIfReady { _, _ -> "closed" })
        } finally {
            release.countDown()
            slot.close()
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(0, readyCount.get())
        assertEquals(1, models.single().closes.get())
        assertEquals(models.single().createdOn, models.single().closedOn)
    }

    @Test
    fun sameKeyStaysReadyAcrossSessionsWithoutReopening() {
        val ready = CountDownLatch(1)
        val attempts = AtomicInteger()
        val slot = AsyncModelSlot<String, Model>("slot-reuse", open = { key ->
            attempts.incrementAndGet()
            Model(key.length)
        }, ready = { _, _ -> ready.countDown() })
        try {
            slot.set("config")
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertTrue(slot.isReady("config"))
            assertFalse(slot.isReady("other"))
            // A new session reusing the same config must not reopen the model.
            slot.set("config")
            assertEquals(1, attempts.get())
            assertNotNull(slot.runIfReady { key, _ -> key })
        } finally {
            slot.close()
        }
        assertFalse(slot.isReady("config"))
    }

    @Test
    fun failureLeavesPreviewAvailableAndOnlyAChangedConfigRetries() {
        val failed = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val attempts = AtomicInteger()
        val failures = AtomicInteger()
        val slot = AsyncModelSlot<Int, Model>("slot-failure", open = { key ->
            attempts.incrementAndGet()
            if (key == 1) throw UnsatisfiedLinkError("native runtime unavailable")
            Model(key)
        }, ready = { _, _ -> ready.countDown() }, failed = { _, _ -> failures.incrementAndGet(); failed.countDown() })
        slot.use {
            slot.set(1)
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            repeat(30) {
                assertNull(slot.runIfReady { _, _ -> "not ready" })
                slot.set(1)
            }
            assertEquals(1, attempts.get())
            slot.set(2)
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertEquals(2, slot.runIfReady { key, _ -> key })
            assertNull(slot.runIfReady<Unit> { _, _ -> error("inference failure") })
            assertEquals(2, failures.get())
            assertNull(slot.runIfReady { _, _ -> "failed model must not run again" })
        }
    }

    @Test
    fun latestFrameWorkerKeepsOnlyOnePendingValueAndRecoversFromConsumerError() {
        val first = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val consumed = Collections.synchronizedList(mutableListOf<Int>())
        val failures = AtomicInteger()
        val worker = BoundedFrameWorker<Int>("frames-test", failed = { _, _ -> failures.incrementAndGet() }) { value ->
            consumed += value
            if (value == 1) {
                first.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                error("plugin error")
            }
            finished.countDown()
        }
        try {
            worker.offer(1)
            assertTrue(first.await(5, TimeUnit.SECONDS))
            (2..100).forEach { worker.offer(it) }
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 100), consumed)
            assertEquals(1, failures.get())
        } finally {
            release.countDown()
            worker.close()
        }
        assertFalse(worker.offer(101))
    }
}
