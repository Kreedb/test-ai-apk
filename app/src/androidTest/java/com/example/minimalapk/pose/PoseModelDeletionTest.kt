package com.example.minimalapk.pose

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minimalapk.models.AssetTransfer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real LiteRT CPU CompiledModel held on the same thread used by camera GPU backends. */
class PoseModelDeletionTest {
    @Test
    fun poseWeightsAreUnlinkedOnlyAfterNativeCloseAndRegistrationCommitsTogether() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "models/delete-test-${System.nanoTime()}.tflite")
        file.parentFile!!.mkdirs()
        val bytes = Base64.decode(RGB_ADD, Base64.DEFAULT)
        file.writeBytes(bytes)
        val graph = TfliteGraph(bytes)
        assertTrue("CompiledModel fixture requires distinct input/output tensors", graph.inputs().toSet().intersect(graph.outputs().toSet()).isEmpty())
        assertEquals(listOf("BUILTIN:0 v1"), graph.describe().operators)
        val spec = ModelSpec("delete-test", "test", "file:${file.name}", 1, 1, enabled = true)
        val worker = Executors.newSingleThreadExecutor { Thread(it, "native-pose-delete") }
        val deleting = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var saved = listOf(spec)
        val reader = worker.submit<PoseBackend> { checkNotNull(PoseModelAccess.open(file, spec.id, worker) {
            LiteRtBackend.open(context, spec, useGpu = false)
        }) }.get(30, TimeUnit.SECONDS)
        try {
            // A/B/A must overwrite every output channel, even after prior tests have populated
            // native allocations. Nonzero ADD constants cannot be optimized into an I/O alias.
            for ((pixel, expected) in listOf(
                0xffff0000.toInt() to floatArrayOf(1.25f, .5f, .75f),
                0xff00ff00.toInt() to floatArrayOf(.25f, 1.5f, .75f),
                0xffff0000.toInt() to floatArrayOf(1.25f, .5f, .75f),
            )) assertArrayEquals(expected, worker.submit<FloatArray> {
                reader.analyze(intArrayOf(pixel))
            }.get(10, TimeUnit.SECONDS), 0f)
            val inference = worker.submit<FloatArray> {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                // The path barrier can stop between queued work and invocation. A cancelled call
                // is expected; deletion must still wait for the already-owned native handles.
                try { reader.analyze(intArrayOf(0xffff0000.toInt())) } catch (_: java.util.concurrent.CancellationException) { floatArrayOf() }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val deletion = deleting.submit {
                PoseModelAccess.deleting(file, spec.id, false, AssetTransfer()) {
                    deletePoseFile(file, false) { saved = emptyList() }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while ((reader as PoseModelReader).isCurrent && System.nanoTime() < deadline) Thread.yield()
            assertFalse(reader.isCurrent)
            assertTrue(file.isFile)
            assertEquals(listOf(spec), saved)
            assertFalse(deletion.isDone)
            release.countDown()
            inference.get(10, TimeUnit.SECONDS)
            deletion.get(10, TimeUnit.SECONDS)
            assertFalse(file.exists())
            assertTrue(saved.isEmpty())
        } finally {
            release.countDown()
            worker.submit { reader.close() }.get(10, TimeUnit.SECONDS)
            worker.shutdown(); deleting.shutdownNow(); file.delete()
        }
    }

    companion object {
        // [1,1,1,3] FLOAT32 rgb + [.25,.5,.75] -> separate sum tensor; one real ADD.
        // 456 bytes, SHA-256 513e31bd9327d7a7aeae088fe77ca8c487cb877b5751cc678fe645f0b5c6cb06.
        // No pose accuracy claim: this isolates native ownership and actual buffer writes.
        private const val RGB_ADD = "GAAAAFRGTDMAAA4AFAAQAAgADAAAAAQADgAAABAAAAA8AAAARAAAAAMAAAACAAAAKAAAAAwAAAAAAAYACAAEAAYAAAAEAAAADAAAAAAAgD4AAAA/AABAP1z///8BAAAABAAAAGj///8BAAAAFAAAAAAADgAYABQAEAAMAAgABAAOAAAAFAAAADQAAAAgAAAAJAAAAHAAAAAMAAAAY29tcHV0ZWRfcmdiAAAAAAEAAAACAAAAAQAAAAAAAAABAAAAFAAAAAAADgAUAAAAEAAMAAsABAAOAAAAKAAAAAAAAAsIAAAADAAAAAEAAAACAAAAAgAAAAAAAAABAAAABAAEAAQAAAADAAAAlAAAAEgAAAAEAAAAjP///wwAAAAIAAAAGAAAAAQAAAABAAAAAQAAAAEAAAADAAAAAwAAAHN1bQAUABQADAAAAAQAEAAAAAAAAAAIABQAAAABAAAADAAAAAgAAAAYAAAABAAAAAEAAAABAAAAAQAAAAMAAAAGAAAAb2Zmc2V0AAAUABAACAAAAAAADAAAAAAAAAAEABQAAAAMAAAACAAAABgAAAAEAAAAAQAAAAEAAAABAAAAAwAAAAMAAAByZ2IA"
    }
}
