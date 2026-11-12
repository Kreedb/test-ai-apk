package com.example.minimalapk.pose

import com.example.minimalapk.models.AssetTransfer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class PoseModelAccessTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun deleteBarsNewOpenAndWaitsForSameWorkerCloseBeforeRemovingWeights() {
        val file = folder.newFile("pose.tflite").apply { writeText("old weights") }
        val worker = Executors.newSingleThreadExecutor { Thread(it, "pose-owner") }
        val deletionWorker = Executors.newSingleThreadExecutor()
        val invoked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val reader = worker.submit<PoseBackend> { PoseModelAccess.open(file, "one", worker) {
            object : PoseBackend {
                override val name = "fake GPU"
                override fun analyze(pixels: IntArray): FloatArray {
                    invoked.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return floatArrayOf(1f)
                }
                override fun close() { assertEquals("pose-owner", Thread.currentThread().name); closed.countDown() }
            }
        }!! }.get()
        try {
            val inference = worker.submit { reader.analyze(intArrayOf()) }
            assertTrue(invoked.await(5, TimeUnit.SECONDS))
            val deletion = deletionWorker.submit {
                PoseModelAccess.deleting(file, "one", false, AssetTransfer()) {
                    assertEquals(0L, closed.count)
                    deletePoseFile(file, false) {}
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((reader as PoseModelReader).isCurrent && System.nanoTime() < deadline) Thread.yield()
            assertFalse(reader.isCurrent)
            assertTrue(file.isFile)
            assertThrows(IllegalStateException::class.java) { PoseModelAccess.open(file, "new", worker) { error("must not open") } }
            release.countDown(); inference.get(5, TimeUnit.SECONDS); deletion.get(5, TimeUnit.SECONDS)
            assertFalse(file.exists())
        } finally { release.countDown(); worker.shutdownNow(); deletionWorker.shutdownNow() }
    }

    @Test
    fun sharedAliasKeepsItsOwnConsumerAndWeightsAndFailedRegistrationRestoresFile() {
        val file = folder.newFile("shared.tflite").apply { writeText("keep") }
        val worker = Executors.newSingleThreadExecutor()
        val closed = AtomicInteger()
        val readers = worker.submit<List<PoseBackend>> { listOf("a", "b").map { id ->
            PoseModelAccess.open(file, id, worker) { object : PoseBackend {
                override val name = id
                override fun analyze(pixels: IntArray) = floatArrayOf(42f)
                override fun close() { closed.incrementAndGet() }
            } }!!
        } }.get()
        try {
            PoseModelAccess.deleting(file, "a", true, AssetTransfer()) { deletePoseFile(file, true) {} }
            assertTrue(file.isFile)
            assertEquals(1, closed.get())
            assertEquals(42f, worker.submit<Float> { readers[1].analyze(intArrayOf()).single() }.get())
            assertThrows(IllegalStateException::class.java) { deletePoseFile(file, false) { error("preferences full") } }
            assertEquals("keep", file.readText())
        } finally { worker.submit { readers.forEach { it.close() } }.get(); worker.shutdown() }
    }

    @Test
    fun cancellingWaitRetainsFileAndRegistrationUntilTheWorkerEventuallyDrains() {
        val file = folder.newFile("cancel.tflite")
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transfer = AssetTransfer()
        val reader = worker.submit<PoseBackend> { PoseModelAccess.open(file, "a", worker) { object : PoseBackend {
            override val name = "fake"
            override fun analyze(pixels: IntArray): FloatArray { entered.countDown(); release.await(); return floatArrayOf() }
            override fun close() {}
        } }!! }.get()
        val cancelWorker = Executors.newSingleThreadExecutor()
        try {
            worker.submit { reader.analyze(intArrayOf()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cancelWorker.submit { Thread.sleep(100); transfer.cancel() }
            assertThrows(CancellationException::class.java) {
                PoseModelAccess.deleting(file, "a", false, transfer) { error("must not commit") }
            }
            assertTrue(file.isFile)
        } finally { release.countDown(); worker.shutdown(); cancelWorker.shutdownNow() }
    }

    @Test
    fun nativeCloseFailureRejectsDeletionAndRetainsWeight() {
        val file = folder.newFile("failed-close.tflite")
        val worker = Executors.newSingleThreadExecutor()
        worker.submit { PoseModelAccess.open(file, "bad", worker) { object : PoseBackend {
            override val name = "broken-driver"
            override fun analyze(pixels: IntArray) = floatArrayOf()
            override fun close() { error("native close failed") }
        } } }.get()
        try {
            assertThrows(IllegalStateException::class.java) {
                PoseModelAccess.deleting(file, "bad", false, AssetTransfer()) { error("must not unregister") }
            }
            assertTrue(file.isFile)
        } finally { worker.shutdown() }
    }
}
