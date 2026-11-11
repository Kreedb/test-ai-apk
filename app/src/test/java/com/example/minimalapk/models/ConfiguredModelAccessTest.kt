package com.example.minimalapk.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConfiguredModelAccessTest {
    private val model = ConfiguredModel("test", "test", File("isolated-configured-access.tflite"))

    @Test
    fun latestSingleShotCancelsOldOwnerAndWaitsForItsNativeDrain() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nextEntered = CountDownLatch(1)
        val old = AssetTransfer().apply { onCancel { cancelled.countDown() } }
        try {
            val first = pool.submit<Boolean> {
                try {
                    ConfiguredModelAccess.run(model, old) {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        old.checkpoint()
                    }
                    false
                } catch (_: CancellationException) { true }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Int> {
                ConfiguredModelAccess.run(model, AssetTransfer()) { nextEntered.countDown(); 42 }
            }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertEquals(1L, nextEntered.count)
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertEquals(42, second.get(5, TimeUnit.SECONDS))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test
    fun deletionBarPreventsNewRequestsWithoutCancellingAnUnrelatedModel() {
        val other = model.copy(file = File("other-configured-access.tflite"))
        val transfer = AssetTransfer()
        ConfiguredModelAccess.run(other, transfer) {
            ConfiguredModelAccess.delete(model) { drain ->
                assertThrows(IllegalStateException::class.java) {
                    ConfiguredModelAccess.run(model, AssetTransfer()) { error("deleted model must never run") }
                }
                drain()
                transfer.checkpoint()
            }
        }
        assertFalse(model.file.exists())
    }

    @Test
    fun cancelledOwnerNeverInvokesAndReleasesExactlyOnce() {
        var closed = 0
        // A no-op owner tests the same barrier; Android coverage opens a real native interpreter.
        val owner = ConfiguredInterpreter { closed++ }
        owner.cancel()
        assertThrows(CancellationException::class.java) { owner.run { error("cancelled owner must not invoke") } }
        owner.close()
        owner.close()
        assertEquals(1, closed)
    }

    @Test
    fun lateCancelledRequestCannotReplaceOrCancelTheCurrentRun() {
        val current = AssetTransfer()
        val stale = AssetTransfer().apply { cancel() }
        ConfiguredModelAccess.run(model, current) {
            assertThrows(CancellationException::class.java) {
                ConfiguredModelAccess.run(model, stale) { error("stale request ran") }
            }
            current.checkpoint()
        }
    }

    @Test
    fun deletionCancelsInspectionAndWaitsForItsFinallyBeforeCommit() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            val check = pool.submit<Boolean> {
                try {
                    ConfiguredModelAccess.inspect(model) { lease ->
                        lease.cancellation.onCancel { cancelled.countDown() }
                        entered.countDown()
                        try { release.await(); lease.checkpoint() } finally { finished.countDown() }
                    }
                    false
                } catch (_: CancellationException) { true }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val deletion = pool.submit {
                ConfiguredModelAccess.delete(model) { drain ->
                    drain()
                    assertEquals(0L, finished.count)
                }
            }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertFalse(deletion.isDone)
            assertThrows(IllegalStateException::class.java) { ConfiguredModelAccess.inspect(model) { error("barred") } }
            release.countDown()
            assertTrue(check.get(5, TimeUnit.SECONDS))
            deletion.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
