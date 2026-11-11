package com.example.minimalapk.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureTest {
    @Test
    fun defaultChunkIsFrameAlignedAndWithinTheBoundedDuration() {
        val bytes = pcmChunkBytes()
        // 16 kHz mono PCM-16 at 40 ms = 640 samples = 1280 bytes.
        assertEquals(1_280, bytes)
        assertEquals(0, bytes % (AUDIO_CHANNEL_COUNT * PcmFormat.PCM_16_LE.bytesPerSample))
        assertTrue(AUDIO_CHUNK_MS in AUDIO_CHUNK_MIN_MS..AUDIO_CHUNK_MAX_MS)
    }

    @Test
    fun captureRejectsNonPcm16OrUnboundedChunks() {
        assertThrows(IllegalArgumentException::class.java) {
            pcmChunkBytes(format = PcmFormat.PCM_FLOAT_LE)
        }
        assertThrows(IllegalArgumentException::class.java) { pcmChunkBytes(chunkMs = 10) }
        assertThrows(IllegalArgumentException::class.java) { pcmChunkBytes(chunkMs = 101) }
        assertThrows(IllegalArgumentException::class.java) { pcmChunkBytes(sampleRate = 0) }
        assertThrows(IllegalArgumentException::class.java) { pcmChunkBytes(channels = 3) }
    }

    @Test
    fun gateRequiresForegroundSubscriptionSessionAndGrantTogether() {
        val gate = AudioCaptureGate()
        gate.setForeground(true)
        gate.setSubscribed(true)
        gate.setSession(7)
        gate.setPermission(AudioPermission.GRANTED)
        assertTrue(gate.shouldCapture)

        gate.setForeground(false)
        assertFalse(gate.shouldCapture)
        gate.setForeground(true)

        gate.setSubscribed(false)
        assertFalse(gate.shouldCapture)
        gate.setSubscribed(true)

        gate.setSession(null)
        assertFalse(gate.shouldCapture)
        gate.setSession(7)

        gate.setPermission(AudioPermission.REVOKED)
        assertFalse(gate.shouldCapture)
        gate.setPermission(AudioPermission.MICROPHONE_BUSY)
        assertFalse(gate.shouldCapture)
        gate.setPermission(AudioPermission.GRANTED)
        assertTrue(gate.shouldCapture)
    }

    @Test
    fun permissionChecksDistinguishFirstRequestDenialAndRevocation() {
        assertEquals(
            AudioPermission.NOT_REQUESTED,
            audioPermissionAfterCheck(granted = false, requested = false, previous = AudioPermission.NOT_REQUESTED),
        )
        assertEquals(
            AudioPermission.DENIED,
            audioPermissionAfterCheck(granted = false, requested = true, previous = AudioPermission.NOT_REQUESTED),
        )
        assertEquals(
            AudioPermission.REVOKED,
            audioPermissionAfterCheck(granted = false, requested = true, previous = AudioPermission.GRANTED),
        )
        assertEquals(
            AudioPermission.REVOKED,
            audioPermissionAfterCheck(granted = false, requested = true, previous = AudioPermission.MICROPHONE_BUSY),
        )
        assertEquals(
            AudioPermission.GRANTED,
            audioPermissionAfterCheck(granted = true, requested = false, previous = AudioPermission.NOT_REQUESTED),
        )
    }
}
