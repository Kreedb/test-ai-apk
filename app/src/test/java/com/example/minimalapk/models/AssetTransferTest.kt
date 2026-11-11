package com.example.minimalapk.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AssetTransferTest {
    @Test
    fun coroutineCancellationClosesBlockedStreamAndNeverCommits() = runBlocking {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val unwound = CountDownLatch(1)
        val installed = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            try {
                runAssetTransfer { transfer ->
                    transfer.use(object : InputStream() {
                        override fun read(): Int {
                            reading.countDown()
                            check(closed.await(5, TimeUnit.SECONDS))
                            return -1
                        }
                        override fun close() { closed.countDown() }
                    }) { input ->
                        input.read()
                        transfer.commit { installed.set(true) }
                    }
                }
            } finally { unwound.countDown() }
        }
        assertTrue(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertTrue(unwound.await(5, TimeUnit.SECONDS))
        assertFalse(installed.get())
    }

    @Test
    fun cancellationBeforeAcceptedModelCommitDoesNotInstallOrRegister() = runBlocking {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val installed = AtomicBoolean(false)
        val registered = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            started.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            commitModelInstallation(install = { installed.set(true) }) { registered.set(true) }
        }
        assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
        job.cancel()
        proceed.countDown()
        job.join()
        assertFalse(installed.get())
        assertFalse(registered.get())
    }

    @Test
    fun cancellationAfterAcceptedCommitCannotOrphanInstalledPoseBeforeRegistration() = runBlocking {
        val written = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val registered = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            commitModelInstallation<String>(install = {
                written.countDown() // model file was written on IO
                check(finish.await(5, TimeUnit.SECONDS))
                "new-pose-spec"
            }) { spec ->
                assertEquals("new-pose-spec", spec)
                registered.set(true)
            }
        }
        assertTrue(withContext(Dispatchers.IO) { written.await(5, TimeUnit.SECONDS) })
        job.cancel()
        finish.countDown()
        job.join()
        assertTrue(registered.get())
    }

    @Test
    fun failedCommitIsNeverRegisteredAndCancellationIsNotConvertedToIoError() = runBlocking {
        val registered = AtomicBoolean(false)
        val result = async {
            try {
                commitModelInstallation<Unit>(install = { throw IllegalStateException("disk full") }) { registered.set(true) }
                false
            } catch (_: IllegalStateException) { true }
        }
        assertTrue(result.await())
        assertFalse(registered.get())
        val transfer = AssetTransfer()
        assertThrows(CancellationException::class.java) {
            transfer.use(object : InputStream() { override fun read() = -1 }) {
                transfer.cancel()
                throw java.io.IOException("socket closed")
            }
        }
        Unit
    }
}
