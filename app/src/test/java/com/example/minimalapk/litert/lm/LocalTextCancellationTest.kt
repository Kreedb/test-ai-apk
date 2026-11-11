package com.example.minimalapk.litert.lm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalTextCancellationTest {
    @Test
    fun cancelledChunksCannotPublishAndFreshRequestStartsEmpty() {
        val delivered = mutableListOf<String>()
        val first = LocalTextCancellation()
        val stream = LocalTextStream(first) { delivered += it }
        assertTrue(stream.append("first"))
        first.cancel()
        assertFalse(stream.append(" stale result"))
        assertThrows(CancellationException::class.java) { stream.finish() }
        val fresh = LocalTextStream(LocalTextCancellation()) { delivered += it }
        fresh.append("42")
        assertEquals("42", fresh.finish())
        assertEquals(listOf("first", "42"), delivered)
    }

    @Test
    fun cancellationWaitsForPublicationAlreadyInFlightButRejectsFutureText() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelStarted = CountDownLatch(1)
        val cancellation = LocalTextCancellation()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publishing = executor.submit {
                cancellation.deliver {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val cancelling = executor.submit { cancelStarted.countDown(); cancellation.cancel() }
            assertTrue(cancelStarted.await(5, TimeUnit.SECONDS))
            assertFalse(cancelling.isDone)
            release.countDown()
            publishing.get(5, TimeUnit.SECONDS)
            cancelling.get(5, TimeUnit.SECONDS)
            assertFalse(cancellation.deliver { throw AssertionError("old request published after cancel returned") })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun splitUnicodeIsPublishedAsWholeCodePointsAndOutputIsBounded() {
        val text = mutableListOf<String>()
        val stream = LocalTextStream(LocalTextCancellation(), maxChars = 4) { text += it }
        stream.append("中\uD83D")
        assertEquals(listOf("中"), text)
        stream.append("\uDE00!")
        assertEquals("中\uD83D\uDE00!", stream.finish())
        assertThrows(IllegalArgumentException::class.java) { stream.append("x") }
        val broken = LocalTextStream(LocalTextCancellation()) { }
        broken.append("\uD83D")
        assertThrows(IllegalArgumentException::class.java) { broken.finish() }
    }

    @Test
    fun preflightRejectsLowMemoryOrWrongAbiWithoutLoadingJni() {
        val device = LocalTextDevice(24, listOf("x86_64"), true, LocalTextModels.MIN_AVAILABLE_BYTES, false)
        assertTrue(LocalTextReadiness.reasons(device).isEmpty())
        assertTrue(LocalTextReadiness.reasons(device.copy(availableBytes = LocalTextModels.MIN_AVAILABLE_BYTES - 1)).single().contains("2560"))
        assertTrue(LocalTextReadiness.reasons(device.copy(lowMemory = true)).single().contains("低内存"))
        assertTrue(LocalTextReadiness.reasons(device.copy(abis = listOf("x86"), process64Bit = false)).single().contains("ABI"))
        assertTrue(LocalTextReadiness.reasons(device.copy(api = 23)).single().contains("API 24"))
    }
}
