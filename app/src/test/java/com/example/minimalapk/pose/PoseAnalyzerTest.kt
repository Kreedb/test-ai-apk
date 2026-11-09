package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PoseAnalyzerTest {
    private fun worker(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PoseBackend-test")
        }

    private class FakeBackend : PoseBackend {
        override val name: String = "fake"
        val closeThread = AtomicReference<Thread>()
        val closeCount = AtomicInteger()
        val closed = CountDownLatch(1)

        override fun analyze(pixels: IntArray): FloatArray = FloatArray(0)

        override fun close() {
            closeThread.set(Thread.currentThread())
            closeCount.incrementAndGet()
            closed.countDown()
        }
    }

    @Test
    fun fastLoadRunsOnWorkerAndReturnsBackend() {
        val executor = worker()
        try {
            val backend = FakeBackend()
            val loadThread = AtomicReference<Thread>()

            val loaded = PoseAnalyzer.loadWithTimeout("fake", 1_000L, executor) {
                loadThread.set(Thread.currentThread())
                backend
            }

            assertSame(backend, loaded)
            assertEquals("PoseBackend-test", loadThread.get().name)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun slowLoadTimesOutAndClosesTheLateBackendOnWorkerExactlyOnce() {
        val executor = worker()
        try {
            val backend = FakeBackend()

            assertThrows(TimeoutException::class.java) {
                PoseAnalyzer.loadWithTimeout("fake", 50L, executor) {
                    Thread.sleep(200L)
                    backend
                }
            }

            // The latch is counted down inside close(), so awaiting it orders the assertions after
            // the worker performs the cleanup.
            assertTrue(backend.closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, backend.closeCount.get())
            assertEquals("PoseBackend-test", backend.closeThread.get().name)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun closeRunsBackendCleanupOnWorker() {
        val executor = worker()
        val backend = FakeBackend()

        PoseAnalyzer(backend, executor).close()

        assertEquals("PoseBackend-test", backend.closeThread.get().name)
        assertNotSame(Thread.currentThread(), backend.closeThread.get())
    }
}
