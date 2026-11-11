package com.example.minimalapk.plugins

import android.graphics.Bitmap
import android.content.ContextWrapper
import com.example.minimalapk.examples.SR_OUTPUT_SIZE
import com.example.minimalapk.examples.SuperResolutionTiles
import com.example.minimalapk.pose.PhotoBounds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Real Android Canvas/Bitmap and real host/worker/slot, with deterministic fixed-size tile output.
 * This verifies coverage, input ownership and interaction races, not ESRGAN's visual accuracy. */
class SuperResolutionRenderingTest {
    private class Tiles(private val beforeTile: () -> Unit = {}) : SuperResolutionTiles {
        val calls = AtomicInteger()
        val closed = CountDownLatch(1)
        override fun runTile(pixels: IntArray, width: Int, height: Int, left: Int, top: Int): Bitmap {
            calls.incrementAndGet()
            beforeTile()
            // Independent nearest-neighbor 4x reference; complement every channel to prove that
            // every photo pixel was actually replaced, including the edges and tile boundaries.
            val output = IntArray(SR_OUTPUT_SIZE * SR_OUTPUT_SIZE) { index ->
                val x = (left + index % SR_OUTPUT_SIZE / 4).coerceIn(0, width - 1)
                val y = (top + index / SR_OUTPUT_SIZE / 4).coerceIn(0, height - 1)
                pixels[y * width + x] xor 0x00ffffff
            }
            return Bitmap.createBitmap(output, SR_OUTPUT_SIZE, SR_OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        }
        override fun close() { closed.countDown() }
    }

    @Test
    fun allRotationsCoverWholePhotoAndKeepSameSourceLetterboxAndBorrowedPixels() {
        val width = 83
        val height = 47
        val colors = intArrayOf(0xffcc2200.toInt(), 0xff00cc22.toInt(), 0xff2200cc.toInt(), 0xff88aa11.toInt())
        val before = IntArray(width * height) { index -> colors[(if (index % width >= width / 2) 1 else 0) + (if (index / width >= height / 2) 2 else 0)] }
        val raw = Bitmap.createBitmap(before, width, height, Bitmap.Config.ARGB_8888)
        try {
            for (rotation in listOf(0, 90, 180, 270)) {
                val swapped = rotation % 180 != 0
                val w = if (swapped) height else width
                val h = if (swapped) width else height
                val base = Bitmap.createBitmap(w + 12, h + 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff315274.toInt()) }
                val space = ImageSpace(base.width, base.height, PhotoBounds(6f, 4f, w + 6f, h + 4f))
                val frame = CameraFrame(ImageSource(1, 1, space, rotation), 1,
                    BitmapImage(raw), BitmapImage(base), ViewportMapping(space, space.width, space.height), emptyList())
                try {
                    Tiles().use { runner ->
                        val plan = superResolutionPlan(w, h)
                        val result = checkNotNull(superResolutionComposite(frame, plan, runner, { true }))
                        try {
                            assertEquals(plan.tiles.size, runner.calls.get())
                            assertEquals(base.width * 4, result.width)
                            assertEquals(base.height * 4, result.height)
                            assertEquals(0xff315274.toInt(), result.getPixel(1, 1))
                            for (y in 0 until h) for (x in 0 until w) {
                                val (sourceX, sourceY) = when (rotation) {
                                    90 -> y to height - 1 - x
                                    180 -> width - 1 - x to height - 1 - y
                                    270 -> width - 1 - y to x
                                    else -> x to y
                                }
                                assertEquals("rotation=$rotation photo=$x,$y", before[sourceY * width + sourceX] xor 0x00ffffff,
                                    result.getPixel((x + 6) * 4 + 2, (y + 4) * 4 + 2))
                            }
                            assertArrayEquals(before, pixels(raw))
                            assertFalse(raw.isRecycled)
                            assertEquals(0xff315274.toInt(), base.getPixel(6, 4))
                        } finally { result.recycle() }
                    }
                } finally { base.recycle() }
            }
        } finally { raw.recycle() }
    }

    @Test
    fun pressWhileLoadingRetainsSourceAndRunsExactlyOnceThenReleaseUsesLatestFrame() {
        val loading = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val runner = Tiles()
        val plugin = SuperResolutionPlugin(ContextWrapper(null)) { _, _ ->
            loading.countDown()
            check(ready.await(5, TimeUnit.SECONDS))
            runner
        }
        PluginHost(listOf(plugin)).use { host ->
            try {
                start(host)
                assertTrue(loading.await(5, TimeUnit.SECONDS))
                val source = frame(host, 1).also(host::acceptFrame)
                val token = host.press(1, ImagePoint(40f, 24f), System.nanoTime())
                assertNotNull(token)
                assertEquals(0, runner.calls.get())
                val latest = frame(host, 2).also(host::acceptFrame)
                ready.countDown()
                await { host.snapshot.value.replacement != null }
                assertEquals(source.source, host.snapshot.value.displayedSource)
                val count = runner.calls.get()
                host.acceptFrame(frame(host, 3))
                assertEquals(count, runner.calls.get()) // One snapshot, not one native run per live frame.
                host.release(1, ImagePoint(40f, 24f), System.nanoTime())
                assertNull(host.snapshot.value.replacement)
                assertTrue(host.snapshot.value.displayedSource!!.frameNumber > latest.source.frameNumber)
                assertFalse(source.raw.bitmap.isRecycled)
            } finally { ready.countDown() }
        }
        assertTrue(runner.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun cancelledLoadingPressCannotReturnOnReadinessAndFreshPressStillWorks() {
        for (reason in CancelReason.entries) {
            val loading = CountDownLatch(1)
            val ready = CountDownLatch(1)
            val runner = Tiles()
            val plugin = SuperResolutionPlugin(ContextWrapper(null)) { _, _ ->
                loading.countDown(); check(ready.await(5, TimeUnit.SECONDS)); runner
            }
            PluginHost(listOf(plugin)).use { host ->
                try {
                    start(host)
                    assertTrue(loading.await(5, TimeUnit.SECONDS))
                    host.acceptFrame(frame(host, 1))
                    host.press(1, ImagePoint(40f, 24f), System.nanoTime())
                    host.cancel(reason)
                    ready.countDown()
                    await { messages(host).any { it.contains("已就绪") } }
                    assertEquals("reason=$reason", 0, runner.calls.get())
                    assertNull(host.snapshot.value.replacement)
                    // This is the same explicit start/end path used by accessibility actions.
                    assertNotNull(host.press(-1, ImagePoint(40f, 24f), System.nanoTime()))
                    await { host.snapshot.value.replacement != null }
                    host.cancel(CancelReason.SYSTEM_CANCEL)
                    assertNull(host.snapshot.value.replacement)
                } finally { ready.countDown() }
            }
            assertTrue(runner.closed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun nativeTileCompletingAfterReleaseOrBackgroundNeverPublishesAndClosesOnDisable() {
        for (background in listOf(false, true)) {
            val running = CountDownLatch(1)
            val release = CountDownLatch(1)
            val runner = Tiles { running.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val plugin = SuperResolutionPlugin(ContextWrapper(null)) { _, _ -> runner }
            PluginHost(listOf(plugin)).use { host ->
                try {
                    start(host)
                    await { messages(host).any { it.contains("已就绪") } }
                    host.acceptFrame(frame(host, 1))
                    host.press(1, ImagePoint(40f, 24f), System.nanoTime())
                    assertTrue(running.await(5, TimeUnit.SECONDS))
                    if (background) host.lifecycle(CameraLifecycle.BACKGROUND)
                    else {
                        host.release(1, ImagePoint(40f, 24f), System.nanoTime())
                        host.configure(mapOf(PluginIds.SUPER_RESOLUTION to PluginSettings(false)))
                    }
                    assertNull(host.snapshot.value.replacement)
                    release.countDown()
                    assertTrue(runner.closed.await(5, TimeUnit.SECONDS))
                    assertEquals(1, runner.calls.get())
                    assertNull(host.snapshot.value.replacement)
                    assertTrue(host.snapshot.value.reports.isEmpty())
                } finally { release.countDown() }
            }
        }
    }

    @Test
    fun sourceSurvivesTileFailureAndLateFailureCannotReplaceRestoredText() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val failedOnce = AtomicInteger()
        val runner = Tiles {
            if (failedOnce.incrementAndGet() == 1) {
                running.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                exited.countDown()
                error("delayed native failure")
            }
        }
        val plugin = SuperResolutionPlugin(ContextWrapper(null)) { _, _ -> runner }
        PluginHost(listOf(plugin)).use { host ->
            try {
                start(host)
                await { messages(host).any { it.contains("已就绪") } }
                val source = frame(host, 1).also(host::acceptFrame)
                host.press(1, ImagePoint(40f, 24f), System.nanoTime())
                assertTrue(running.await(5, TimeUnit.SECONDS))
                host.release(1, ImagePoint(40f, 24f), System.nanoTime())
                assertTrue(messages(host).any { it.contains("已恢复实时原图") })
                release.countDown()
                assertTrue(exited.await(5, TimeUnit.SECONDS))
                // A fresh press queues behind the failing call and proves it unwound safely.
                host.press(1, ImagePoint(40f, 24f), System.nanoTime())
                await { host.snapshot.value.replacement != null }
                assertFalse(messages(host).any { it.contains("delayed native failure") })
                assertFalse(source.raw.bitmap.isRecycled)
                host.release(1, ImagePoint(40f, 24f), System.nanoTime())
            } finally { release.countDown() }
        }
    }

    @Test
    fun configChangedWhileLoadingCannotGiveOldPressToNewModelOrDisabledReplacement() {
        val loading = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val runner = Tiles()
        val plugin = SuperResolutionPlugin(ContextWrapper(null)) { _, _ ->
            loading.countDown(); check(ready.await(5, TimeUnit.SECONDS)); runner
        }
        PluginHost(listOf(plugin)).use { host ->
            try {
                start(host)
                assertTrue(loading.await(5, TimeUnit.SECONDS))
                host.acceptFrame(frame(host, 1))
                host.press(1, ImagePoint(40f, 24f), System.nanoTime())
                host.configure(mapOf(PluginIds.SUPER_RESOLUTION to PluginSettings(true, SuperResolutionSettings(false).toConfig())))
                ready.countDown()
                assertTrue(runner.closed.await(5, TimeUnit.SECONDS))
                assertEquals(0, runner.calls.get())
                assertNull(host.snapshot.value.replacement)
                assertTrue(messages(host).any { it.contains("替换画面已关闭") })
                host.release(1, ImagePoint(40f, 24f), System.nanoTime())
                host.press(1, ImagePoint(40f, 24f), System.nanoTime())
                assertEquals(0, runner.calls.get())
                assertNull(host.snapshot.value.replacement)
            } finally { ready.countDown() }
        }
    }

    private fun start(host: PluginHost) {
        host.configure(mapOf(PluginIds.SUPER_RESOLUTION to PluginSettings(true)))
        host.lifecycle(CameraLifecycle.ENTER)
        host.lifecycle(CameraLifecycle.FOREGROUND)
    }

    private fun frame(host: PluginHost, number: Long): CameraFrame {
        val bitmap = Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffaabbcc.toInt()) }
        val space = ImageSpace(80, 48, PhotoBounds(0f, 0f, 80f, 48f))
        return CameraFrame(ImageSource(host.sessionId()!!, number, space, 0), System.nanoTime(),
            BitmapImage(bitmap), BitmapImage(bitmap), ViewportMapping(space, 80, 48), emptyList())
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun messages(host: PluginHost) = host.snapshot.value.reports.flatMap { it.output.messages }.map { it.text }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Timed out waiting for actual plugin publication", condition())
    }
}
