package com.example.minimalapk.models

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AssetResourceStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val payload = ByteArray(16) { 1 }

    @Test
    fun deleteInvalidatesReadinessThenWaitsForEngineLeaseBeforeRemovingAnyFile() {
        val root = folder.newFolder()
        val set = assetTestSet("model.bin" to payload)
        val store = AssetResourceStore.at(root)
        install(root, set)
        val lease = store.acquire(set)
        val revision = store.revision.value
        val waiting = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val deletion = worker.submit {
                // Checkpoint runs in the lease wait after the starting revision is published.
                store.delete(set.id, AssetTransfer {
                    if (store.revision.value > revision) waiting.countDown()
                })
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            assertFalse(deletion.isDone)
            assertNull(store.installedDirectory(set))
            assertThrows(IllegalStateException::class.java) { store.acquire(set) }
            // A lazy component reader still sees its entire set until it closes the lease.
            assertArrayEquals(payload, File(lease.directory, "model.bin").readBytes())
            lease.close()
            lease.close() // close is idempotent and can run on a different worker
            deletion.get(5, TimeUnit.SECONDS)
            assertNull(store.installedDirectory(set))
            assertFalse(File(root, set.id).exists())
            assertEquals(revision + 2, store.revision.value)
        } finally { lease.close(); worker.shutdownNow() }
    }

    @Test
    fun cancellationWhileWaitingForLeaseRestoresAvailabilityWithoutDeleting() {
        val root = folder.newFolder()
        val set = assetTestSet("model.bin" to payload)
        val store = AssetResourceStore.at(root)
        install(root, set)
        val lease = store.acquire(set)
        val changed = CountDownLatch(1)
        val before = store.revision.value
        val transfer = AssetTransfer { if (store.revision.value > before) changed.countDown() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val deletion = worker.submit<Boolean> {
                try { store.delete(set.id, transfer); false } catch (_: CancellationException) { true }
            }
            assertTrue(changed.await(5, TimeUnit.SECONDS))
            transfer.cancel()
            assertTrue(deletion.get(5, TimeUnit.SECONDS))
            assertNotNull(store.installedDirectory(set))
            assertArrayEquals(payload, File(lease.directory, "model.bin").readBytes())
            assertEquals(before + 2, store.revision.value)
        } finally { lease.close(); worker.shutdownNow() }
    }

    @Test
    fun distinctInstallersShareOneWriterForTheSameCanonicalSet() {
        val root = folder.newFolder()
        val set = assetTestSet("model.bin" to payload)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queued = CountDownLatch(1)
        val opened = AtomicInteger()
        val transport = object : AssetTransport {
            override fun open(request: AssetHttpRequest): AssetHttpResponse {
                opened.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return AssetHttpResponse(200, payload.size.toLong(), null, null, ByteArrayInputStream(payload))
            }
        }
        val worker = Executors.newFixedThreadPool(2)
        try {
            val one = worker.submit { AssetInstaller(root, transport, reserveBytes = 0).install(set) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val two = worker.submit {
                AssetInstaller(File(root, "../${root.name}"), transport, reserveBytes = 0)
                    .install(set, AssetTransfer { queued.countDown() })
            }
            assertTrue(queued.await(5, TimeUnit.SECONDS))
            assertFalse(two.isDone)
            assertEquals(1, opened.get())
            release.countDown()
            one.get(5, TimeUnit.SECONDS)
            two.get(5, TimeUnit.SECONDS)
            assertEquals(1, opened.get()) // second verifies the first complete commit
            assertTrue(set.isReadyIn(root))
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test
    fun waitingWriterCanCancelAndDoesNotInterruptTheActiveWriter() {
        val root = folder.newFolder()
        val set = assetTestSet("model.bin" to payload)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val transfer = AssetTransfer { waiting.countDown() }
        val worker = Executors.newFixedThreadPool(2)
        try {
            val one = worker.submit {
                AssetInstaller(root, object : AssetTransport {
                    override fun open(request: AssetHttpRequest): AssetHttpResponse {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        return AssetHttpResponse(200, payload.size.toLong(), null, null, ByteArrayInputStream(payload))
                    }
                }, reserveBytes = 0).install(set)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val two = worker.submit<Boolean> {
                try { AssetResourceStore.at(root).delete(set.id, transfer); false }
                catch (_: CancellationException) { true }
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            transfer.cancel()
            assertTrue(two.get(5, TimeUnit.SECONDS))
            release.countDown()
            one.get(5, TimeUnit.SECONDS)
            assertTrue(set.isReadyIn(root))
        } finally { release.countDown(); worker.shutdownNow() }
    }

    private fun install(root: File, set: AssetSet) {
        AssetInstaller(root, object : AssetTransport {
            override fun open(request: AssetHttpRequest) =
                AssetHttpResponse(200, payload.size.toLong(), null, null, ByteArrayInputStream(payload))
        }, reserveBytes = 0).install(set)
    }
}
