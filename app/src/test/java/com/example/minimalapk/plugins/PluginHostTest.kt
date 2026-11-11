package com.example.minimalapk.plugins

import android.graphics.Bitmap
import com.example.minimalapk.pose.PhotoBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PluginHostTest {
    private class Image(override val width: Int = 640, override val height: Int = 640) : PluginImage {
        override val bitmap: Bitmap get() = error("Pure host tests do not access Android pixels")
    }

    private class Plugin(
        id: String,
        capabilities: Set<PluginCapability> = PluginCapability.entries.toSet(),
        priority: Int = 0,
        val fails: Boolean = false,
    ) : CameraPlugin {
        override val descriptor = PluginDescriptor(id, id, capabilities, priority)
        val events = mutableListOf<PluginEvent>()
        override fun onEvent(event: PluginEvent, context: PluginContext) {
            events += event
            if (fails && event is PluginEvent.Frame) error("isolated failure")
        }
    }

    private val space = ImageSpace(640, 640, PhotoBounds(80f, 0f, 560f, 640f))
    private fun frame(host: PluginHost, number: Long = 1) = CameraFrame(
        ImageSource(host.sessionId()!!, number, space, 90), number * 100,
        Image(640, 480), Image(), ViewportMapping(space, 640, 640), emptyList(),
    )

    private fun start(vararg plugins: Plugin): PluginHost = PluginHost(plugins.toList()).also { host ->
        host.configure(plugins.associate { it.descriptor.id to PluginSettings(true) })
        host.lifecycle(CameraLifecycle.ENTER)
        host.lifecycle(CameraLifecycle.FOREGROUND)
    }

    @Test
    fun disableClearsEveryOutputAndOldConfigurationsCannotPublishAfterReenable() {
        val plugin = Plugin("test")
        start(plugin).use { host ->
            val source = frame(host).also(host::acceptFrame).source
            val old = host.contextFor("test")!!
            old.publish(PluginOutput(100, listOf(PluginMessage("visible")), listOf(PluginLayer(source))))
            assertEquals(1, host.snapshot.value.reports.size)
            host.configure(mapOf("test" to PluginSettings(false)))
            assertTrue(host.snapshot.value.reports.isEmpty())
            assertTrue(host.snapshot.value.layers.isEmpty())
            assertFalse(old.isCurrent())
            host.acceptFrame(frame(host, 2))
            assertEquals(1, plugin.events.filterIsInstance<PluginEvent.Frame>().size)
            assertFalse(old.publish(PluginOutput(200, listOf(PluginMessage("late")))))
            host.configure(mapOf("test" to PluginSettings(true, mapOf("option" to "new"), 1)))
            val new = host.contextFor("test")!!
            assertNotEquals(old.token, new.token)
            assertFalse(old.publish(PluginOutput(300)))
            assertTrue(new.publish(PluginOutput(300, listOf(PluginMessage("new")))))
            host.configure(mapOf("test" to PluginSettings(true, mapOf("option" to "newer"), 2)))
            assertFalse(new.isCurrent())
            assertTrue(host.snapshot.value.reports.isEmpty())
        }
    }

    @Test
    fun backgroundAndNewSessionRejectOldFramesAndResults() {
        val plugin = Plugin("test")
        start(plugin).use { host ->
            val oldFrame = frame(host)
            host.acceptFrame(oldFrame)
            val old = host.contextFor("test")!!
            host.press(4, ImagePoint(320f, 320f), 110)
            host.lifecycle(CameraLifecycle.BACKGROUND)
            assertNull(host.snapshot.value.frame)
            assertNull(host.snapshot.value.press)
            assertFalse(old.publish(PluginOutput(120)))
            assertEquals(CancelReason.BACKGROUND, plugin.events.filterIsInstance<PluginEvent.Cancel>().last().reason)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            assertNotEquals(old.token.session, host.contextFor("test")!!.token.session)
            host.acceptFrame(oldFrame)
            assertNull(host.snapshot.value.frame)
            host.acceptFrame(frame(host))
            assertEquals(1L, host.snapshot.value.frame!!.source.frameNumber)
        }
    }

    @Test
    fun slowReplacementUsesItsWholeSourceSnapshotThenReleaseRestoresLatestFrame() {
        start(Plugin("enhance")).use { host ->
            val oldFrame = frame(host).also(host::acceptFrame)
            val context = host.contextFor("enhance")!!
            val press = host.press(7, ImagePoint(320f, 320f), 110)!!
            val latest = frame(host, 5).also(host::acceptFrame)
            val replacement = PreviewReplacement(oldFrame.source, Image(1280, 1280), press)
            assertTrue(context.publish(PluginOutput(120, replacement = replacement)))
            assertEquals(oldFrame.source, host.snapshot.value.displayedSource)
            host.release(7, ImagePoint(320f, 320f), 600)
            assertEquals(latest.source, host.snapshot.value.displayedSource)
            assertFalse(context.publish(PluginOutput(700, replacement = replacement)))
            val nextPress = host.press(7, ImagePoint(320f, 320f), 800)!!
            assertNotEquals(press, nextPress)
            assertFalse(context.publish(PluginOutput(900, replacement = replacement)))
        }
    }

    @Test
    fun everyCancelReasonInvalidatesPressTokensAndWithdrawsReplacement() {
        CancelReason.entries.forEach { reason ->
            val plugin = Plugin("enhance")
            start(plugin).use { host ->
                val source = frame(host).also(host::acceptFrame).source
                val context = host.contextFor("enhance")!!
                val press = host.press(1, ImagePoint(320f, 320f), 110)!!
                val output = PluginOutput(120, replacement = PreviewReplacement(source, Image(), press))
                assertTrue(context.publish(output))
                host.cancel(reason, 130)
                assertNull(host.snapshot.value.replacement)
                assertFalse(context.publish(output.copy(timestampNanos = 140)))
                assertEquals(reason, plugin.events.filterIsInstance<PluginEvent.Cancel>().single().reason)
            }
        }
    }

    @Test
    fun movingOutOfPhotoCancelsAndCannotReenterWithoutANewDown() {
        start(Plugin("enhance")).use { host ->
            host.acceptFrame(frame(host))
            assertNull(host.press(1, ImagePoint(10f, 10f), 100))
            host.press(1, ImagePoint(320f, 320f), 110)
            host.move(1, ImagePoint(40f, 320f), 120)
            assertNull(host.snapshot.value.press)
            host.move(1, ImagePoint(320f, 320f), 130)
            assertNull(host.snapshot.value.press)
        }
    }

    @Test
    fun secondPointerCancelsTheFirstPressAndViewportChangeAlsoEndsIt() {
        val plugin = Plugin("enhance")
        start(plugin).use { host ->
            host.acceptFrame(frame(host))
            host.press(1, ImagePoint(320f, 320f), 110)
            assertNull(host.press(2, ImagePoint(300f, 300f), 120))
            assertNull(host.snapshot.value.press)
            assertEquals(CancelReason.MULTITOUCH, plugin.events.filterIsInstance<PluginEvent.Cancel>().last().reason)
            host.press(1, ImagePoint(320f, 320f), 130)
            host.viewport(320, 600)
            assertNull(host.snapshot.value.press)
            assertEquals(CancelReason.VIEWPORT_CHANGED, plugin.events.filterIsInstance<PluginEvent.Cancel>().last().reason)
        }
    }

    @Test
    fun stopAfterBackgroundIsStillDeliveredAndNextSessionRechecksAudioPermission() {
        val plugin = Plugin("audio")
        start(plugin).use { host ->
            host.permissionChanged(host.sessionId()!!, AudioPermission.GRANTED)
            host.lifecycle(CameraLifecycle.BACKGROUND)
            host.lifecycle(CameraLifecycle.STOP)
            assertTrue(plugin.events.contains(PluginEvent.Lifecycle(CameraLifecycle.STOP)))
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            assertEquals(AudioPermission.NOT_REQUESTED,
                plugin.events.filterIsInstance<PluginEvent.AudioPermissionChanged>().last().permission)
        }
    }

    @Test
    fun pluginEnabledDuringPressCannotClaimIt() {
        start(Plugin("first"), Plugin("second")).use { host ->
            host.configure(mapOf("first" to PluginSettings(true), "second" to PluginSettings(false)))
            val source = frame(host).also(host::acceptFrame).source
            val press = host.press(1, ImagePoint(320f, 320f), 110)!!
            host.configure(mapOf("first" to PluginSettings(true), "second" to PluginSettings(true)))
            val second = host.contextFor("second")!!
            assertNull(second.currentPress())
            assertFalse(second.publish(PluginOutput(120, replacement = PreviewReplacement(source, Image(), press))))
        }
    }

    @Test
    fun replacementPriorityAndStableIdDetermineWinnerWithVisibleConflict() {
        start(Plugin("z", priority = 1), Plugin("a", priority = 1), Plugin("low")).use { host ->
            val source = frame(host).also(host::acceptFrame).source
            val press = host.press(1, ImagePoint(320f, 320f), 110)!!
            listOf("z", "a", "low").forEach { id ->
                host.contextFor(id)!!.publish(PluginOutput(120, replacement = PreviewReplacement(source, Image(), press)))
            }
            assertEquals("a", host.snapshot.value.replacement!!.pluginId)
            assertEquals(setOf("z", "low"), host.snapshot.value.reports.map { it.pluginId }.toSet())
            assertTrue(host.snapshot.value.reports.all { it.output.messages.last().text.contains("a") })
        }
    }

    @Test
    fun layersRequireExactFrameRotationAndRoiAndRespectDeclaredCapabilities() {
        val capabilities = setOf(PluginCapability.LAYER, PluginCapability.TEXT)
        start(Plugin("layer", capabilities), Plugin("no_outputs", emptySet())).use { host ->
            val source = frame(host).also(host::acceptFrame).source
            host.contextFor("layer")!!.publish(PluginOutput(120, layers = listOf(
                PluginLayer(source), PluginLayer(source.copy(frameNumber = 2)),
                PluginLayer(source.copy(rotationDegrees = 0)),
                PluginLayer(source.copy(space = space.copy(photoBounds = PhotoBounds(90f, 0f, 550f, 640f)))),
            )))
            assertEquals(listOf(PluginLayer(source)), host.snapshot.value.layers.single().output.layers)
            host.contextFor("no_outputs")!!.publish(PluginOutput(120, listOf(PluginMessage("not allowed")), listOf(PluginLayer(source))))
            assertTrue(host.snapshot.value.reports.isEmpty())
            host.acceptFrame(frame(host, 3))
            assertTrue(host.snapshot.value.layers.isEmpty())
        }
    }

    @Test
    fun errorsStayIsolatedAndMessagesAreBoundedAndTimeOrdered() {
        val broken = Plugin("broken", fails = true)
        val healthy = Plugin("healthy", setOf(PluginCapability.CAMERA_FRAMES, PluginCapability.TEXT))
        start(broken, healthy).use { host ->
            host.acceptFrame(frame(host))
            assertEquals(1, healthy.events.filterIsInstance<PluginEvent.Frame>().size)
            assertEquals(MessageKind.ERROR, host.snapshot.value.reports.single().output.messages.single().kind)
            val context = host.contextFor("healthy")!!
            context.publish(PluginOutput(500, List(100) { PluginMessage("x".repeat(1000)) }))
            val messages = host.snapshot.value.reports.first { it.pluginId == "healthy" }.output.messages
            assertEquals(PluginHost.MAX_MESSAGES, messages.size)
            assertTrue(messages.all { it.text.length == PluginHost.MAX_MESSAGE_CHARS })
            assertFalse(context.publish(PluginOutput(499, listOf(PluginMessage("older audio transcript")))))
        }
    }

    @Test
    fun audioOnlyReachesEnabledSubscribersWithPermissionAndNeverAfterStop() {
        val audio = Plugin("audio", setOf(PluginCapability.AUDIO_PERMISSION, PluginCapability.AUDIO_PCM))
        val video = Plugin("video", setOf(PluginCapability.CAMERA_FRAMES))
        start(audio, video).use { host ->
            val block = PcmBlock(ByteBuffer.allocate(8).asReadOnlyBuffer(), 16000, 1, PcmFormat.PCM_16_LE, 100)
            val session = host.sessionId()!!
            host.audio(session, block)
            assertTrue(audio.events.filterIsInstance<PluginEvent.Audio>().isEmpty())
            host.permissionChanged(session, AudioPermission.GRANTED)
            host.audio(session, block)
            assertEquals(1, audio.events.filterIsInstance<PluginEvent.Audio>().size)
            assertTrue(video.events.filterIsInstance<PluginEvent.AudioPermissionChanged>().isEmpty())
            host.lifecycle(CameraLifecycle.STOP)
            host.audio(session, block)
            assertEquals(1, audio.events.filterIsInstance<PluginEvent.Audio>().size)
        }
    }

    @Test
    fun lateAudioPermissionsAndBlocksCannotCrossSessionsAndRevocationInvalidatesText() {
        val plugin = Plugin("audio")
        start(plugin).use { host ->
            val session = host.sessionId()!!
            host.permissionChanged(session, AudioPermission.GRANTED)
            val context = host.contextFor("audio")!!
            assertTrue(context.publish(PluginOutput(100, listOf(PluginMessage("transcript")))))
            host.permissionChanged(session, AudioPermission.REVOKED)
            assertFalse(context.publish(PluginOutput(200, listOf(PluginMessage("late transcript")))))
            assertTrue(host.snapshot.value.reports.isEmpty())
            host.lifecycle(CameraLifecycle.BACKGROUND)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            host.permissionChanged(session, AudioPermission.GRANTED)
            val block = PcmBlock(ByteBuffer.allocate(8).asReadOnlyBuffer(), 16000, 1, PcmFormat.PCM_16_LE, 300)
            host.audio(session, block)
            assertTrue(plugin.events.filterIsInstance<PluginEvent.Audio>().isEmpty())
            assertEquals(AudioPermission.NOT_REQUESTED,
                plugin.events.filterIsInstance<PluginEvent.AudioPermissionChanged>().last().permission)
        }
    }

    @Test
    fun pcmReadersHaveIndependentPositionsAndLittleEndianFormat() {
        val buffer = ByteBuffer.allocate(4).put(byteArrayOf(1, 2, 3, 4)).apply { flip() }.asReadOnlyBuffer()
        val block = PcmBlock(buffer, 16000, 1, PcmFormat.PCM_16_LE, 100)
        val first = block.samples
        assertEquals(513.toShort(), first.short)
        assertEquals(2, first.remaining())
        assertEquals(4, block.samples.remaining())
        assertTrue(block.samples.isReadOnly)
    }

    @Test
    fun slowFrameCallbackCannotBlockDisableOrDeliverOldFrameIntoNewSession() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val slow = object : CameraPlugin {
            override val descriptor = PluginDescriptor("slow", "slow", setOf(PluginCapability.CAMERA_FRAMES, PluginCapability.TEXT))
            override fun onEvent(event: PluginEvent, context: PluginContext) {
                if (event is PluginEvent.Frame) {
                    running.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    result.set(context.publish(PluginOutput(500, listOf(PluginMessage("late")))))
                }
            }
        }
        val next = Plugin("next", setOf(PluginCapability.CAMERA_FRAMES))
        val executor = Executors.newSingleThreadExecutor()
        PluginHost(listOf(slow, next)).use { host ->
            host.configure(mapOf("slow" to PluginSettings(true), "next" to PluginSettings(true)))
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            val frame = frame(host)
            val task = executor.submit { host.acceptFrame(frame) }
            try {
                assertTrue(running.await(5, TimeUnit.SECONDS))
                host.configure(mapOf("slow" to PluginSettings(false), "next" to PluginSettings(true)))
                host.lifecycle(CameraLifecycle.BACKGROUND)
                host.lifecycle(CameraLifecycle.FOREGROUND)
                release.countDown()
                task.get(5, TimeUnit.SECONDS)
                assertFalse(result.get())
                assertTrue(next.events.filterIsInstance<PluginEvent.Frame>().isEmpty())
                assertTrue(host.snapshot.value.reports.isEmpty())
            } finally {
                release.countDown()
                executor.shutdown()
            }
        }
    }
}
