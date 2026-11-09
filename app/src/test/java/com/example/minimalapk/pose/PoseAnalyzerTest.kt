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

        override fun analyze(pixels: IntArray): FloatArray = FloatArray(0)

        override fun close() {
            closeThread.set(Thread.currentThread())
            closeCount.incrementAndGet()
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
    fun slowLoadTimesOutAndClosesTheLateBackendExactlyOnce() {
        val executor = worker()
        try {
            val backend = FakeBackend()
            val finished = CountDownLatch(1)

            assertThrows(TimeoutException::class.java) {
                PoseAnalyzer.loadWithTimeout("fake", 50L, executor) {
                    try {
                        Thread.sleep(200L)
                        backend
                    } finally {
                        finished.countDown()
                    }
                }
            }

            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, backend.closeCount.get())
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
