package com.example.minimalapk.plugins

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** Real batch consumer + host + capture gate; clock/deadline are deterministic, no AudioRecord mock. */
class LiteRtAudioRecoveryTest {
    private class Deadline : LiteRtRecordingDeadline {
        var current: (() -> Unit)? = null
        val callbacks = mutableListOf<() -> Unit>()
        var delay = 0L
        override fun schedule(delayNanos: Long, expired: () -> Unit) {
            delay = delayNanos
            current = expired
            callbacks += expired
        }
        override fun cancel() { current = null }
        override fun close() = cancel()
        fun fire() { checkNotNull(current).also { current = null }.invoke() }
    }

    private class Fixture(initialPermission: AudioPermission = AudioPermission.GRANTED) : AutoCloseable {
        var now = 1_000_000_000L
        val timer = Deadline()
        val plugin = LiteRtAudioPlugin(object : ContextWrapper(null) {
            override fun getFilesDir(): File = error("Partial/recovery windows must not start a model download or open")
        }, clock = { now }, recordingDeadline = timer)
        val host = PluginHost(listOf(plugin))
        val gate = AudioCaptureGate()
        val session: Long
        val settings = mapOf(PluginIds.LITERT_AUDIO to PluginSettings(true, mapOf("window_seconds" to "2")))

        init {
            host.configure(settings)
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            session = checkNotNull(host.sessionId())
            gate.setForeground(true)
            gate.setSession(session)
            gate.setPermission(initialPermission)
            report()
        }

        fun demand() {
            gate.setSubscribed(host.hasSubscribers(PluginCapability.AUDIO_PCM))
        }

        fun report() {
            gate.report()?.let { report ->
                if (report.withdrawBeforeGrant) host.permissionChanged(report.session, AudioPermission.MICROPHONE_BUSY)
                host.permissionChanged(report.session, report.permission)
                gate.acknowledge(report)
            }
            demand()
        }

        fun action(id: String) {
            assertTrue(host.action(PluginIds.LITERT_AUDIO, id))
            demand()
        }

        fun start(): AudioCaptureTicket {
            action("record")
            return checkNotNull(gate.begin()).also {
                gate.bufferSize(it, 1280)
                gate.observe(it, AudioCaptureAvailability.AVAILABLE)
                report()
            }
        }

        fun observe(ticket: AudioCaptureTicket, availability: AudioCaptureAvailability) {
            assertTrue(gate.observe(ticket, availability))
            report()
        }

        fun audio(ticket: AudioCaptureTicket, samples: Int): AudioReadOutcome {
            val bytes = samples * 2
            val read = checkNotNull(gate.beginRead(ticket))
            return gate.readCompleted(read, bytes).also { outcome ->
                if (outcome == AudioReadOutcome.DELIVER) {
                    host.audio(session, PcmBlock(ByteBuffer.allocate(bytes).asReadOnlyBuffer(),
                        AUDIO_SAMPLE_RATE, 1, PcmFormat.PCM_16_LE, now))
                }
                demand()
            }
        }

        override fun close() { host.close(); timer.close() }
    }

    @Test fun onlyBatchConsumerKeepsMuteObservationAndRestartsAFreshWindowAfterUnmute() {
        Fixture().use { f ->
            assertFalse(f.host.snapshot.value.audioCaptureRequested)
            assertNull(f.gate.begin())
            val ticket = f.start()
            assertEquals(AudioReadOutcome.DELIVER, f.audio(ticket, AUDIO_SAMPLE_RATE))
            val beforeMute = f.host.contextFor(PluginIds.LITERT_AUDIO)!!

            f.observe(ticket, AudioCaptureAvailability.CLIENT_SILENCED)
            assertFalse(beforeMute.isCurrent())
            assertEquals(AudioPermission.MICROPHONE_BUSY, f.host.snapshot.value.audioPermission)
            assertTrue(f.host.snapshot.value.audioCaptureRequested)
            assertTrue(f.gate.isRunning(ticket))
            assertNull("Recovery must retain the one recorder, not open a second", f.gate.begin())
            assertEquals(AudioReadOutcome.DROP, f.audio(ticket, AUDIO_SAMPLE_RATE))

            f.now += 1_000_000_000L
            f.observe(ticket, AudioCaptureAvailability.AVAILABLE)
            assertEquals(AudioPermission.GRANTED, f.host.snapshot.value.audioPermission)
            assertEquals(AudioReadOutcome.DROP, f.audio(ticket, 640)) // Drain the old native buffer.
            assertEquals(AudioReadOutcome.DELIVER, f.audio(ticket, AUDIO_SAMPLE_RATE))
            assertTrue("Pre-mute PCM must not complete the fresh two-second window", f.host.snapshot.value.audioCaptureRequested)
            assertTrue(f.host.snapshot.value.reports.single().output.actions.single { it.id == "record" }.enabled)
            assertEquals("Mute/regrant must not extend the user request deadline", 1, f.timer.callbacks.size)

            f.action("cancel")
            assertFalse(f.host.snapshot.value.audioCaptureRequested)
            assertFalse(f.gate.isRunning(ticket))
            f.gate.released(ticket)
            assertNull(f.gate.begin())
            assertNull(f.timer.current)
        }
    }

    @Test fun idleBatchDoesNotAskForPermissionAndFirstRecordCanWaitForARealGrant() {
        Fixture(AudioPermission.NOT_REQUESTED).use { f ->
            assertFalse(f.host.requiresAudioPermission())
            assertFalse(f.host.snapshot.value.audioPermissionRequested)
            assertFalse(f.host.snapshot.value.audioCaptureRequested)
            assertTrue(f.host.snapshot.value.reports.single().output.actions.single { it.id == "record" }.enabled)

            f.action("record")
            assertTrue(f.host.requiresAudioPermission())
            assertTrue(f.host.snapshot.value.audioPermissionRequested)
            assertTrue(f.host.snapshot.value.audioCaptureRequested)
            assertNull("Intent must never bypass the runtime permission gate", f.gate.begin())
            val pending = f.host.contextFor(PluginIds.LITERT_AUDIO)!!
            f.gate.setPermission(AudioPermission.GRANTED)
            f.report()
            assertFalse(pending.isCurrent())
            assertTrue(f.host.snapshot.value.audioCaptureRequested)
            val ticket = checkNotNull(f.gate.begin())
            f.observe(ticket, AudioCaptureAvailability.AVAILABLE)
            assertEquals(AudioReadOutcome.DELIVER, f.audio(ticket, AUDIO_SAMPLE_RATE))
            assertTrue(f.host.snapshot.value.audioCaptureRequested)
            assertEquals("Permission delivery must not extend or duplicate the request", 1, f.timer.callbacks.size)
            f.action("cancel")
            assertFalse(f.host.requiresAudioPermission())
            assertFalse(f.host.snapshot.value.audioCaptureRequested)
            f.gate.released(ticket)
            assertNull(f.gate.begin())
        }
    }

    @Test fun deniedFirstRecordStopsDemandAndAnExplicitRetryCanAskAgainWithoutAutoRecordingAfterCancel() {
        Fixture(AudioPermission.NOT_REQUESTED).use { f ->
            f.action("record")
            f.gate.setPermission(AudioPermission.DENIED)
            f.report()
            assertFalse(f.host.requiresAudioPermission())
            assertFalse(f.host.snapshot.value.audioCaptureRequested)
            assertNull(f.timer.current)
            f.action("record")
            assertTrue(f.host.snapshot.value.audioPermissionRequested)
            assertNull(f.gate.begin())
            f.action("cancel")
            f.gate.setPermission(AudioPermission.GRANTED)
            f.report()
            assertFalse(f.host.requiresAudioPermission())
            assertFalse(f.host.snapshot.value.audioCaptureRequested)
            assertNull("A late permission grant cannot revive cancelled recording intent", f.gate.begin())
        }
    }

    @Test fun timedOutMutedOnlyConsumerCanExplicitlyRetryWithoutLeavingCameraAndNeverRetriesItself() {
        Fixture().use { f ->
            val ticket = f.start()
            f.observe(ticket, AudioCaptureAvailability.MICROPHONE_MUTED)
            assertEquals(12_000_000_000L, f.timer.delay)
            f.now += f.timer.delay
            f.timer.fire()
            f.demand()
            assertFalse(f.host.snapshot.value.audioCaptureRequested)
            assertFalse(f.gate.isRunning(ticket))
            f.gate.released(ticket)
            assertNull(f.gate.begin())
            assertNull(f.timer.current)
            assertEquals(1, f.timer.callbacks.size)
            assertFalse(f.host.action(PluginIds.LITERT_AUDIO, "record"))

            // Privacy was unmuted while no recorder existed; explicit retry observes it in-session.
            f.action("retry")
            val retried = checkNotNull(f.gate.begin())
            assertNotEquals(ticket, retried)
            assertEquals(f.session, f.host.sessionId())
            f.observe(retried, AudioCaptureAvailability.AVAILABLE)
            assertEquals(AudioPermission.GRANTED, f.host.snapshot.value.audioPermission)
            assertTrue(f.host.snapshot.value.audioCaptureRequested)
            assertEquals(2, f.timer.callbacks.size)
            f.action("cancel")
            f.gate.released(retried)
            assertFalse(f.gate.shouldCapture)
        }
    }

    @Test fun cancelledOldDeadlineCannotStopANewRequestAndDisableReleasesRecoveryDemand() {
        Fixture().use { f ->
            val first = f.start()
            val oldDeadline = f.timer.callbacks.single()
            f.action("cancel")
            f.gate.released(first)
            f.now += 1_000_000_000L
            val second = f.start()
            f.now += 11_000_000_000L
            oldDeadline()
            assertTrue(f.host.snapshot.value.audioCaptureRequested)
            f.observe(second, AudioCaptureAvailability.CLIENT_SILENCED)
            val lateDeadline = f.timer.callbacks.last()
            f.host.configure(mapOf(PluginIds.LITERT_AUDIO to PluginSettings(false)))
            f.demand()
            assertFalse(f.host.snapshot.value.audioCaptureRequested)
            assertFalse(f.gate.isRunning(second))
            f.now += 10_000_000_000L
            lateDeadline()
            assertTrue(f.host.snapshot.value.reports.isEmpty())
            assertFalse(f.host.snapshot.value.audioCaptureRequested)
        }
    }

    @Test fun fatalReadRemainsLatchedAndBoundedRecoveryCannotCreateAnAutomaticReopenLoop() {
        Fixture().use { f ->
            val first = f.start()
            assertTrue(f.gate.fail(first))
            f.report()
            f.gate.released(first)
            assertEquals(AudioPermission.MICROPHONE_BUSY, f.host.snapshot.value.audioPermission)
            assertNull(f.gate.begin())
            f.action("retry")
            assertNull("Explicit batch retry must not bypass the capture owner's fatal failure latch", f.gate.begin())
            f.now += f.timer.delay
            f.timer.fire()
            f.demand()
            assertFalse(f.host.snapshot.value.audioCaptureRequested)
            assertNull(f.timer.current)
            assertNull(f.gate.begin())
            assertEquals(2, f.timer.callbacks.size)
        }
    }
}
