package com.example.minimalapk.plugins

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the capture gate's reports against the real host generation/PCM dispatch contract. */
class AudioCaptureDeliveryTest {
    private class Consumer(id: String, audio: Boolean) : CameraPlugin {
        override val descriptor = PluginDescriptor(id, id, if (audio) {
            setOf(PluginCapability.TEXT, PluginCapability.AUDIO_PERMISSION, PluginCapability.AUDIO_PCM)
        } else setOf(PluginCapability.TEXT, PluginCapability.CAMERA_FRAMES))
        val events = mutableListOf<PluginEvent>()
        override fun onEvent(event: PluginEvent, context: PluginContext) { events += event }
    }

    @Test
    fun readFailureAndSilencingWithdrawAudioResultsWithoutInvalidatingVideo() {
        for (silenced in listOf(false, true)) {
            val audio = Consumer("audio", true)
            val video = Consumer("video", false)
            PluginHost(listOf(audio, video)).use { host ->
                host.configure(mapOf("audio" to PluginSettings(true), "video" to PluginSettings(true)))
                host.lifecycle(CameraLifecycle.ENTER)
                host.lifecycle(CameraLifecycle.FOREGROUND)
                val session = checkNotNull(host.sessionId())
                val gate = AudioCaptureGate().apply {
                    setSession(session)
                    setForeground(true)
                    setSubscribed(true)
                    setPermission(AudioPermission.GRANTED)
                }
                val grant = checkNotNull(gate.report())
                host.permissionChanged(grant.session, grant.permission)
                gate.acknowledge(grant)
                val ticket = checkNotNull(gate.begin())
                gate.observe(ticket, AudioCaptureAvailability.AVAILABLE)
                val audioContext = checkNotNull(host.contextFor("audio"))
                val videoContext = checkNotNull(host.contextFor("video"))
                audioContext.publish(PluginOutput(10, listOf(PluginMessage("stale classification"))))
                videoContext.publish(PluginOutput(10, listOf(PluginMessage("video output"))))

                if (silenced) gate.observe(ticket, AudioCaptureAvailability.CLIENT_SILENCED)
                else assertEquals(AudioReadOutcome.FAILED,
                    gate.readCompleted(checkNotNull(gate.beginRead(ticket)), -6))
                val unavailable = checkNotNull(gate.report())
                assertEquals(AudioPermission.MICROPHONE_BUSY, unavailable.permission)
                host.permissionChanged(unavailable.session, unavailable.permission)
                gate.acknowledge(unavailable)

                assertFalse(audioContext.publish(PluginOutput(20, listOf(PluginMessage("late inference")))))
                assertEquals(listOf("video"), host.snapshot.value.reports.map { it.pluginId })
                assertTrue(videoContext.isCurrent())
                assertTrue(videoContext.publish(PluginOutput(20, listOf(PluginMessage("new video output")))))
                assertTrue(video.events.none { it is PluginEvent.AudioPermissionChanged })
                host.audio(session, block())
                assertTrue(audio.events.none { it is PluginEvent.Audio })
            }
        }
    }

    @Test
    fun recoveryNeedsANewHostGenerationBeforeHealthyPcmCanReachConsumers() {
        val audio = Consumer("audio", true)
        PluginHost(listOf(audio)).use { host ->
            host.configure(mapOf("audio" to PluginSettings(true)))
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            val session = checkNotNull(host.sessionId())
            val gate = AudioCaptureGate().apply {
                setSession(session)
                setForeground(true)
                setSubscribed(true)
                setPermission(AudioPermission.GRANTED)
            }
            val grant = checkNotNull(gate.report())
            host.permissionChanged(session, grant.permission)
            gate.acknowledge(grant)
            val ticket = checkNotNull(gate.begin())
            gate.bufferSize(ticket, 4)
            gate.observe(ticket, AudioCaptureAvailability.MICROPHONE_MUTED)
            val blocked = checkNotNull(gate.report())
            host.permissionChanged(session, blocked.permission)
            gate.acknowledge(blocked)
            val blockedContext = checkNotNull(host.contextFor("audio"))
            gate.observe(ticket, AudioCaptureAvailability.AVAILABLE)
            // A buffer drain and a healthy read still cannot reach consumers before host reporting.
            repeat(2) {
                assertEquals(AudioReadOutcome.DROP, gate.readCompleted(checkNotNull(gate.beginRead(ticket)), 4))
            }
            val recovered = checkNotNull(gate.report())
            host.permissionChanged(session, recovered.permission)
            gate.acknowledge(recovered)
            assertFalse(blockedContext.isCurrent())
            assertTrue(checkNotNull(host.contextFor("audio")).isCurrent())
            assertEquals(AudioReadOutcome.DELIVER, gate.readCompleted(checkNotNull(gate.beginRead(ticket)), 4))
            host.audio(session, block())
            assertEquals(1, audio.events.filterIsInstance<PluginEvent.Audio>().size)
        }
    }

    private fun block() = PcmBlock(ByteBuffer.allocate(4).asReadOnlyBuffer(),
        AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT, PcmFormat.PCM_16_LE, 30)
}
