package com.example.minimalapk.plugins

import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredPcmContract
import com.example.minimalapk.models.ConfiguredPublishedResult
import com.example.minimalapk.models.ModelRunResult
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host integration tests use the frozen custom plugin's runner seam, not a second demand model. */
class PluginAudioPermissionDemandTest {
    private fun start(host: PluginHost) {
        host.configure(mapOf(PluginIds.CONFIGURED_MODEL to PluginSettings(true)))
        host.lifecycle(CameraLifecycle.ENTER)
        host.lifecycle(CameraLifecycle.FOREGROUND)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Waiting for custom model contract discovery", condition())
    }

    @Test fun enabledImageAndTextContractsNeverRequestMicrophonePermission() {
        val models = listOf("image", "text").map { ConfiguredModel(it, it, File("$it.tflite"), enabled = true) }
        val plugin = ConfiguredModelPlugin({ models }, { model, _ ->
            object : ConfiguredFrameRunner {
                override val acceptsFrames = model.id == "image"
                override val status = "${model.id} contract ready"
                override fun run(image: PluginImage): ModelRunResult = error("This test only discovers contracts")
                override fun close() {}
            }
        }, MutableStateFlow<ConfiguredPublishedResult?>(null))
        PluginHost(listOf(plugin)).use { host ->
            start(host)
            assertFalse(host.requiresAudioPermission())
            waitFor { host.snapshot.value.reports.flatMap { it.output.messages }.count { "contract ready" in it.text } == 2 }
            assertFalse(host.requiresAudioPermission())
            assertFalse(host.snapshot.value.audioPermissionRequested)
            assertFalse(host.snapshot.value.audioCaptureRequested)
            host.permissionChanged(host.sessionId()!!, AudioPermission.REVOKED)
            waitFor { host.snapshot.value.reports.flatMap { it.output.messages }.count { "contract ready" in it.text } == 2 }
            assertFalse(host.requiresAudioPermission())
        }
    }

    @Test fun discoveredExplicitPcmPublishesPermissionIntentWithoutWaitingForGrant() {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val selected = ConfiguredModel("pcm", "pcm", File("pcm.tflite"), enabled = true)
        val plugin = ConfiguredModelPlugin({ listOf(selected) }, { _, _ ->
            opening.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            object : ConfiguredFrameRunner {
                override val acceptsFrames = false
                override val pcm = ConfiguredPcmContract(4, 16000)
                override val status = "explicit PCM ready"
                override fun run(image: PluginImage): ModelRunResult = error("Not an image model")
                override fun close() {}
            }
        }, MutableStateFlow<ConfiguredPublishedResult?>(null))
        PluginHost(listOf(plugin)).use { host ->
            try {
                start(host)
                assertTrue(opening.await(5, TimeUnit.SECONDS))
                assertFalse(host.requiresAudioPermission())
                assertFalse(host.snapshot.value.audioPermissionRequested)
                release.countDown()
                waitFor { host.snapshot.value.audioPermissionRequested }
                assertTrue(host.requiresAudioPermission())
                assertTrue(host.snapshot.value.audioCaptureRequested)
                assertEquals(AudioPermission.NOT_REQUESTED, host.snapshot.value.audioPermission)
                host.configure(mapOf(PluginIds.CONFIGURED_MODEL to PluginSettings(false)))
                assertFalse(host.requiresAudioPermission())
                assertFalse(host.snapshot.value.audioPermissionRequested)
            } finally { release.countDown() }
        }
    }
}
