package com.example.minimalapk.plugins

import android.graphics.Bitmap
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredModelVersion
import com.example.minimalapk.models.ConfiguredPcmContract
import com.example.minimalapk.models.ConfiguredPublishedResult
import com.example.minimalapk.models.ConfiguredResultPreview
import com.example.minimalapk.models.ConfiguredResultStore
import com.example.minimalapk.models.ModelRunResult
import com.example.minimalapk.pose.PhotoBounds
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConfiguredModelPluginTest {
    private val selected = ConfiguredModel("waveform", "waveform", File("waveform.tflite"), enabled = true)
    private val enabled = mapOf(PluginIds.CONFIGURED_MODEL to PluginSettings(true))

    private fun pcm(values: ShortArray, time: Long): PcmBlock {
        val bytes = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { bytes.putShort(it) }
        bytes.flip()
        return PcmBlock(bytes.asReadOnlyBuffer(), 16000, 1, PcmFormat.PCM_16_LE, time)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Timed out waiting for the serial model owner", condition())
    }

    private fun messages(host: PluginHost) = host.snapshot.value.reports.flatMap { it.output.messages }.joinToString { it.text }
    private fun start(host: PluginHost) {
        host.configure(enabled)
        host.lifecycle(CameraLifecycle.ENTER)
        host.lifecycle(CameraLifecycle.FOREGROUND)
    }

    @Test
    fun revokingPermissionWhileInferenceIsInFlightRejectsItsResultAndDropsPartialWindow() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val calls = AtomicInteger()
        val plugin = ConfiguredModelPlugin({ listOf(selected) }, { _, permission ->
            object : ConfiguredFrameRunner {
                override val acceptsFrames = false
                override val pcm = ConfiguredPcmContract(4, 16000)
                override val status = permission.name
                override fun run(image: PluginImage): ModelRunResult = error("PCM never receives frames")
                override fun runPcm(samples: FloatArray): ModelRunResult {
                    assertEquals(AudioPermission.GRANTED, permission)
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        return ModelRunResult("late revoked transcript", emptyList())
                    }
                    assertArrayEquals(floatArrayOf(.25f, .25f, .25f, .25f), samples, 0f)
                    return ModelRunResult("fresh window", emptyList())
                }
                override fun close() { if (permission == AudioPermission.GRANTED) drained.countDown() }
            }
        }, MutableStateFlow<ConfiguredPublishedResult?>(null))
        PluginHost(listOf(plugin)).use { host ->
            try {
                start(host)
                val session = host.sessionId()!!
                host.permissionChanged(session, AudioPermission.GRANTED)
                waitFor { "GRANTED" in messages(host) }
                host.audio(session, pcm(ShortArray(4) { 16384 }, 1_000_000L))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                host.audio(session, pcm(ShortArray(2) { 30000 }, 2_000_000L)) // Must not survive revoke.
                host.permissionChanged(session, AudioPermission.REVOKED)
                assertFalse(messages(host).contains("late revoked"))
                release.countDown()
                assertTrue(drained.await(5, TimeUnit.SECONDS))
                waitFor { "REVOKED" in messages(host) }
                assertFalse(messages(host).contains("late revoked"))
                host.permissionChanged(session, AudioPermission.GRANTED)
                waitFor { "GRANTED" in messages(host) }
                host.audio(session, pcm(ShortArray(2) { 8192 }, 3_000_000L))
                assertEquals(1, calls.get())
                host.audio(session, pcm(ShortArray(2) { 8192 }, 4_000_000L))
                waitFor { "fresh window" in messages(host) }
                assertEquals(2, calls.get())
            } finally { release.countDown() }
        }
    }

    @Test
    fun playbackResetsIncompleteAudioAndDisableInvalidatesACompletedButLateCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val plugin = ConfiguredModelPlugin({ listOf(selected) }, { _, permission ->
            object : ConfiguredFrameRunner {
                override val acceptsFrames = false
                override val pcm = ConfiguredPcmContract(4, 16000)
                override val status = permission.name
                override fun run(image: PluginImage): ModelRunResult = error("not image")
                override fun runPcm(samples: FloatArray): ModelRunResult {
                    assertArrayEquals(FloatArray(4) { .25f }, samples, 0f)
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return ModelRunResult("disabled late result", emptyList())
                }
                override fun close() { if (permission == AudioPermission.GRANTED) closed.countDown() }
            }
        }, MutableStateFlow<ConfiguredPublishedResult?>(null))
        PluginHost(listOf(plugin)).use { host ->
            try {
                start(host)
                val session = host.sessionId()!!
                host.permissionChanged(session, AudioPermission.GRANTED)
                waitFor { "GRANTED" in messages(host) }
                host.audio(session, pcm(ShortArray(2) { 16384 }, 1_000_000L))
                host.playbackChanged(true)
                host.audio(session, pcm(ShortArray(4) { 30000 }, 2_000_000L))
                host.playbackChanged(false)
                host.audio(session, pcm(ShortArray(4) { 8192 }, 3_000_000L))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                host.configure(mapOf(PluginIds.CONFIGURED_MODEL to PluginSettings(false)))
                release.countDown()
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertTrue(host.snapshot.value.reports.isEmpty())
                assertFalse(host.snapshot.value.audioCaptureRequested)
            } finally { release.countDown() }
        }
    }

    @Test
    fun temporaryMicrophoneUnavailabilityKeepsCaptureIntentThroughReloadAndRegrant() {
        val loadingBusy = CountDownLatch(1)
        val allowReload = CountDownLatch(1)
        val calls = AtomicInteger()
        val plugin = ConfiguredModelPlugin({ listOf(selected) }, { _, permission ->
            if (permission == AudioPermission.MICROPHONE_BUSY) {
                loadingBusy.countDown()
                assertTrue(allowReload.await(5, TimeUnit.SECONDS))
            }
            object : ConfiguredFrameRunner {
                override val acceptsFrames = false
                override val pcm = ConfiguredPcmContract(4, 16000)
                override val status = permission.name + configuredAudioPermissionMessage(permission)
                override fun run(image: PluginImage): ModelRunResult = error("not image")
                override fun runPcm(samples: FloatArray): ModelRunResult {
                    assertEquals(AudioPermission.GRANTED, permission)
                    assertArrayEquals(FloatArray(4) { .25f }, samples, 0f)
                    calls.incrementAndGet()
                    return ModelRunResult("after unmute", emptyList())
                }
                override fun close() {}
            }
        }, MutableStateFlow<ConfiguredPublishedResult?>(null))
        PluginHost(listOf(plugin)).use { host ->
            try {
                start(host)
                val session = host.sessionId()!!
                host.permissionChanged(session, AudioPermission.GRANTED)
                waitFor { "GRANTED" in messages(host) }
                host.audio(session, pcm(ShortArray(2) { 16384 }, 1_000_000L))
                host.permissionChanged(session, AudioPermission.MICROPHONE_BUSY)
                assertTrue(loadingBusy.await(5, TimeUnit.SECONDS))
                assertTrue(host.hasSubscribers(PluginCapability.AUDIO_PCM))
                assertTrue(host.snapshot.value.audioCaptureRequested)
                assertTrue(messages(host).contains("系统静音"))
                assertFalse(messages(host).contains("请授予"))
                host.audio(session, pcm(ShortArray(4) { 30000 }, 2_000_000L))
                assertEquals(0, calls.get())
                allowReload.countDown()
                waitFor { "MICROPHONE_BUSY" in messages(host) }
                assertTrue(host.snapshot.value.audioCaptureRequested)
                host.permissionChanged(session, AudioPermission.GRANTED)
                waitFor { "GRANTED" in messages(host) }
                host.audio(session, pcm(ShortArray(2) { 8192 }, 3_000_000L))
                assertEquals(0, calls.get())
                host.audio(session, pcm(ShortArray(2) { 8192 }, 4_000_000L))
                waitFor { "after unmute" in messages(host) }
                assertEquals(1, calls.get())
                host.playbackChanged(true)
                assertFalse(host.snapshot.value.audioCaptureRequested)
                host.playbackChanged(false)
                assertTrue(host.snapshot.value.audioCaptureRequested)
                host.configure(mapOf(PluginIds.CONFIGURED_MODEL to PluginSettings(false)))
                assertFalse(host.snapshot.value.audioCaptureRequested)
            } finally { allowReload.countDown() }
        }
    }

    @Test
    fun manualResultAppearsOnStartPersistsAcrossFramesAndClearsWithItsStore() {
        val results = ConfiguredResultStore().apply { configure(enabled.getValue(PluginIds.CONFIGURED_MODEL)) }
        val version = ConfiguredModelVersion(selected.id, selected.file.absolutePath, "config", true, 1)
        val ticket = results.begin(selected, version, version.fingerprint)!!
        assertTrue(results.publish(ticket, version, ConfiguredResultPreview("manual answer")))
        val plugin = ConfiguredModelPlugin({ listOf(selected) }, { _, _ ->
            object : ConfiguredFrameRunner {
                override val acceptsFrames = false
                override val status = "按需运行"
                override fun run(image: PluginImage): ModelRunResult = error("manual runner must not execute on frames")
                override fun close() {}
            }
        }, results.result)
        PluginHost(listOf(plugin)).use { host ->
            start(host)
            waitFor { "manual answer" in messages(host) }
            val source = ImageSource(host.sessionId()!!, 1, ImageSpace(8, 8, PhotoBounds(0f, 0f, 8f, 8f)), 0)
            val image = object : PluginImage {
                override val width = 8
                override val height = 8
                override val bitmap: Bitmap get() = error("Manual mode must never read camera pixels")
            }
            host.acceptFrame(CameraFrame(source, System.nanoTime(), image, image, ViewportMapping(source.space, 8, 8), emptyList()))
            assertTrue("manual answer" in messages(host))
            assertFalse(host.snapshot.value.audioCaptureRequested)
            results.invalidate(version.path)
            waitFor { "manual answer" !in messages(host) }
            host.configure(mapOf(PluginIds.CONFIGURED_MODEL to PluginSettings(false)))
            assertTrue(host.snapshot.value.reports.isEmpty())
        }
    }
}
