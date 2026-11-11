package com.example.minimalapk.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real gate transitions driven by read results/platform observations, without an AudioRecord mock. */
class AudioCaptureGateTest {
    private fun gate(session: Long = 7) = AudioCaptureGate().apply {
        setSession(session)
        setForeground(true)
        setSubscribed(true)
        setPermission(AudioPermission.GRANTED)
        acknowledge(checkNotNull(report()))
    }

    private fun AudioCaptureGate.start(): AudioCaptureTicket = checkNotNull(begin()).also {
        bufferSize(it, 2_560)
        observe(it, AudioCaptureAvailability.AVAILABLE)
    }

    private fun AudioCaptureGate.read(ticket: AudioCaptureTicket, count: Int = 1_280): AudioReadOutcome =
        readCompleted(checkNotNull(beginRead(ticket)), count)

    @Test
    fun everyNegativeReadWithdrawsGrantAndLatchesUntilForegroundRetry() {
        // Android AudioRecord ERROR, BAD_VALUE, INVALID_OPERATION, DEAD_OBJECT, and vendor unknown.
        for (error in listOf(-1, -2, -3, -6, -99)) {
            val gate = gate()
            val ticket = gate.start()
            assertEquals(AudioReadOutcome.DELIVER, gate.read(ticket))
            assertEquals("read=$error", AudioReadOutcome.FAILED, gate.read(ticket, error))
            assertEquals(AudioPermission.MICROPHONE_BUSY, gate.permission)
            val report = checkNotNull(gate.report())
            gate.released(ticket)
            // Releasing the dead recorder must not discard the error awaiting the UI.
            assertEquals(report, gate.report())
            gate.acknowledge(report)
            repeat(3) {
                gate.setSession(ticket.session)
                gate.setSubscribed(true)
                gate.setPermission(AudioPermission.GRANTED)
                gate.setForeground(true)
                assertNull(gate.begin())
                assertNull(gate.report())
            }
            gate.setForeground(false)
            gate.setSession(null)
            gate.setSession(8)
            gate.setForeground(true)
            assertEquals(AudioPermission.GRANTED, gate.permission)
            assertNotNull(gate.begin())
        }
    }

    @Test
    fun zeroByteReadIsAnEmptyReadNotAMuteOrFailure() {
        val gate = gate()
        val ticket = gate.start()
        repeat(3) { assertEquals(AudioReadOutcome.EMPTY, gate.read(ticket, 0)) }
        assertEquals(AudioPermission.GRANTED, gate.permission)
        assertNull(gate.report())
        // No PCM-amplitude heuristic: real silence is deliverable when the platform says available.
        assertEquals(AudioReadOutcome.DELIVER, gate.read(ticket))
    }

    @Test
    fun expectedStopReadErrorsNeverBecomeMicrophoneBusy() {
        val stops: List<(AudioCaptureGate) -> Unit> = listOf(
            { it.stop() },
            { it.setForeground(false) },
            { it.setSession(null) },
            { it.setSubscribed(false) },
            { it.setPlaybackPaused(true) },
            { it.setPermission(AudioPermission.REVOKED) },
        )
        stops.forEach { stop ->
            val gate = gate()
            val ticket = gate.start()
            val read = checkNotNull(gate.beginRead(ticket))
            stop(gate)
            assertEquals(AudioReadOutcome.STOP, gate.readCompleted(read, -3))
            assertFalse(gate.fail(ticket))
            assertFalse(gate.observe(ticket, AudioCaptureAvailability.CLIENT_SILENCED))
            assertNotEquals(AudioPermission.MICROPHONE_BUSY, gate.permission)
            assertNull(gate.begin()) // The old record still owns the slot until release.
        }
    }

    @Test
    fun stopAlsoRejectsPositiveReadThatWasAlreadyInFlight() {
        val gate = gate()
        val ticket = gate.start()
        val read = checkNotNull(gate.beginRead(ticket))
        gate.setSubscribed(false)
        assertEquals(AudioReadOutcome.STOP, gate.readCompleted(read, 1_280))
        assertFalse(gate.canDeliver(read))
    }

    @Test
    fun systemMuteAndClientSilencingKeepOnlyOneRecorderAndDrainBeforeRecovery() {
        for (blocked in listOf(AudioCaptureAvailability.MICROPHONE_MUTED, AudioCaptureAvailability.CLIENT_SILENCED)) {
            val gate = gate()
            val ticket = gate.start()
            val inFlight = checkNotNull(gate.beginRead(ticket))
            assertTrue(gate.observe(ticket, blocked))
            assertFalse(gate.observe(ticket, blocked))
            assertEquals(AudioReadOutcome.DROP, gate.readCompleted(inFlight, 1_280))
            assertFalse(gate.canDeliver(inFlight))
            assertEquals(AudioPermission.MICROPHONE_BUSY, gate.permission)
            assertTrue(gate.isRunning(ticket))
            assertNull(gate.begin())
            gate.acknowledge(checkNotNull(gate.report()))
            repeat(4) { assertEquals(AudioReadOutcome.DROP, gate.read(ticket)) }

            val straddlesRecovery = checkNotNull(gate.beginRead(ticket))
            assertTrue(gate.observe(ticket, AudioCaptureAvailability.AVAILABLE))
            assertEquals(AudioReadOutcome.DROP, gate.readCompleted(straddlesRecovery, 1_280))
            val recovery = checkNotNull(gate.report())
            assertEquals(AudioPermission.GRANTED, recovery.permission)
            assertFalse(recovery.withdrawBeforeGrant)
            // Two complete native-buffer chunks are discarded, then wait for UI permission report.
            repeat(2) { assertEquals(AudioReadOutcome.DROP, gate.read(ticket)) }
            assertEquals(AudioReadOutcome.DROP, gate.read(ticket))
            gate.acknowledge(recovery)
            assertEquals(AudioReadOutcome.DELIVER, gate.read(ticket))
            assertTrue(gate.isRunning(ticket))
            assertNull(gate.begin())
        }
    }

    @Test
    fun rapidMuteUnmuteStillWithdrawsOldGenerationBeforeRestoringGrant() {
        val gate = gate()
        val ticket = gate.start()
        val inFlight = checkNotNull(gate.beginRead(ticket))
        gate.observe(ticket, AudioCaptureAvailability.CLIENT_SILENCED)
        gate.observe(ticket, AudioCaptureAvailability.AVAILABLE)
        val report = checkNotNull(gate.report())
        assertEquals(AudioPermission.GRANTED, report.permission)
        assertTrue(report.withdrawBeforeGrant)
        assertEquals(AudioReadOutcome.DROP, gate.readCompleted(inFlight, 1_280))
        repeat(2) { assertEquals(AudioReadOutcome.DROP, gate.read(ticket)) }
        assertEquals(AudioReadOutcome.DROP, gate.read(ticket))
        gate.acknowledge(report)
        assertEquals(AudioReadOutcome.DELIVER, gate.read(ticket))
    }

    @Test
    fun unknownRecordingConfigurationCannotFeedModelsAndRecoversWithoutReopen() {
        val gate = gate()
        val ticket = checkNotNull(gate.begin())
        gate.bufferSize(ticket, 1_280)
        assertEquals(AudioReadOutcome.DROP, gate.read(ticket)) // No initial observation yet.
        gate.observe(ticket, AudioCaptureAvailability.CONFIGURATION_UNAVAILABLE)
        gate.acknowledge(checkNotNull(gate.report()))
        repeat(3) { assertEquals(AudioReadOutcome.DROP, gate.read(ticket)) }
        gate.observe(ticket, AudioCaptureAvailability.AVAILABLE)
        gate.acknowledge(checkNotNull(gate.report()))
        assertEquals(AudioReadOutcome.DROP, gate.read(ticket)) // Native-buffer drain.
        assertEquals(AudioReadOutcome.DELIVER, gate.read(ticket))
        assertNull(gate.begin())
    }

    @Test
    fun aReportAcknowledgmentCannotHideANewerMuteTransition() {
        val gate = gate()
        val ticket = gate.start()
        gate.observe(ticket, AudioCaptureAvailability.MICROPHONE_MUTED)
        gate.acknowledge(checkNotNull(gate.report()))
        gate.observe(ticket, AudioCaptureAvailability.AVAILABLE)
        val pendingGrant = checkNotNull(gate.report())
        gate.observe(ticket, AudioCaptureAvailability.CLIENT_SILENCED)
        gate.acknowledge(pendingGrant)
        assertEquals(AudioPermission.MICROPHONE_BUSY, checkNotNull(gate.report()).permission)
        assertEquals(AudioReadOutcome.DROP, gate.read(ticket))
    }

    @Test
    fun aReadStartedBeforeGrantWasReportedIsNeverDeliveredAfterward() {
        val gate = gate()
        val ticket = gate.start()
        gate.observe(ticket, AudioCaptureAvailability.MICROPHONE_MUTED)
        gate.observe(ticket, AudioCaptureAvailability.AVAILABLE)
        repeat(2) { assertEquals(AudioReadOutcome.DROP, gate.read(ticket)) }
        val pending = checkNotNull(gate.beginRead(ticket))
        gate.acknowledge(checkNotNull(gate.report()))
        assertEquals(AudioReadOutcome.DROP, gate.readCompleted(pending, 1_280))
        assertEquals(AudioReadOutcome.DELIVER, gate.read(ticket))
    }

    @Test
    fun releaseIsRequiredBeforePlaybackResumeCanCreateAnotherRecorder() {
        val gate = gate()
        val first = gate.start()
        val pending = checkNotNull(gate.beginRead(first))
        gate.setPlaybackPaused(true)
        gate.setPlaybackPaused(false)
        assertNull(gate.begin())
        assertEquals(AudioReadOutcome.STOP, gate.readCompleted(pending, -3))
        gate.released(first)
        val second = gate.start()
        assertNotEquals(first, second)
        assertFalse(gate.observe(first, AudioCaptureAvailability.CLIENT_SILENCED))
        assertFalse(gate.fail(first))
        gate.released(first) // Late old cleanup cannot clear the new owner.
        assertEquals(AudioReadOutcome.DELIVER, gate.read(second))
        assertNull(gate.begin())
        assertNull(gate.report())
    }

    @Test
    fun backgroundReleaseAndLateReadsCannotAffectNewSession() {
        val gate = gate()
        val first = gate.start()
        val pending = checkNotNull(gate.beginRead(first))
        gate.observe(first, AudioCaptureAvailability.CLIENT_SILENCED)
        val oldReport = checkNotNull(gate.report())
        gate.setForeground(false)
        gate.setSubscribed(false)
        gate.setSession(null)
        assertNull(gate.report())
        assertNull(gate.begin())
        gate.setSession(8)
        gate.setForeground(true)
        gate.setSubscribed(true)
        assertNull(gate.begin()) // Wait for background release, even on a quick return.
        gate.released(first)
        val second = gate.start()
        gate.acknowledge(oldReport)
        assertEquals(8, checkNotNull(gate.report()).session)
        gate.acknowledge(checkNotNull(gate.report()))
        assertEquals(AudioReadOutcome.STOP, gate.readCompleted(pending, -6))
        assertFalse(gate.observe(first, AudioCaptureAvailability.MICROPHONE_MUTED))
        assertFalse(gate.fail(first))
        assertEquals(AudioReadOutcome.DELIVER, gate.read(second))
    }

    @Test
    fun startFailureAndRepeatedPermissionChecksDoNotCauseRestartLoops() {
        val gate = gate()
        val first = checkNotNull(gate.begin())
        assertTrue(gate.fail(first))
        gate.released(first)
        repeat(5) {
            gate.setSubscribed(false)
            gate.setSubscribed(true)
            gate.setPlaybackPaused(true)
            gate.setPlaybackPaused(false)
            gate.setPermission(AudioPermission.GRANTED)
            assertNull(gate.begin())
        }
        // An actual revoke/regrant can retry; a repeated granted check above cannot.
        gate.setPermission(AudioPermission.REVOKED)
        gate.setPermission(AudioPermission.GRANTED)
        assertNotNull(gate.begin())
    }

    @Test
    fun mutingDoesNotOverwriteRevocationAndForegroundDoesNotInventPermission() {
        val gate = gate()
        val ticket = gate.start()
        gate.observe(ticket, AudioCaptureAvailability.CLIENT_SILENCED)
        gate.setPermission(AudioPermission.REVOKED)
        assertEquals(AudioPermission.REVOKED, gate.permission)
        assertFalse(gate.observe(ticket, AudioCaptureAvailability.AVAILABLE))
        gate.released(ticket)
        gate.setForeground(false)
        gate.setForeground(true)
        assertEquals(AudioPermission.REVOKED, gate.permission)
        assertNull(gate.begin())
    }

    @Test
    fun backgroundAndDisablingReleaseEvenWhenSilenced() {
        val gate = gate()
        val first = gate.start()
        gate.observe(first, AudioCaptureAvailability.CLIENT_SILENCED)
        gate.setSubscribed(false)
        assertFalse(gate.isRunning(first))
        gate.released(first)
        assertNull(gate.begin())
        gate.setForeground(false)
        gate.setSubscribed(true)
        assertNull(gate.begin())
    }
}
