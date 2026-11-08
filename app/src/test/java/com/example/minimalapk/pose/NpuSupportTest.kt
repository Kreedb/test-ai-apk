package com.example.minimalapk.pose

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuSupportTest {
    @Test
    fun qualcommSm8850OnAndroid12IsSupported() {
        assertNull(
            PoseAnalyzer.npuUnavailableReason(31, arrayOf("arm64-v8a"), "Qualcomm", "SM8850"),
        )
        assertNull(
            PoseAnalyzer.npuUnavailableReason(34, arrayOf("arm64-v8a"), " QTI ", "SM8850-AC"),
        )
    }

    @Test
    fun preAndroid12IsRejected() {
        val reason = PoseAnalyzer.npuUnavailableReason(30, arrayOf("arm64-v8a"), "Qualcomm", "SM8850")
        assertNotNull(reason)
        assertTrue(checkNotNull(reason).contains("31"))
    }

    @Test
    fun nonArm64AbiIsRejected() {
        val reason = PoseAnalyzer.npuUnavailableReason(34, arrayOf("x86_64"), "Qualcomm", "SM8850")
        assertNotNull(reason)
        assertTrue(checkNotNull(reason).contains("arm64-v8a"))
    }

    @Test
    fun nonQualcommSocIsRejected() {
        val reason = PoseAnalyzer.npuUnavailableReason(34, arrayOf("arm64-v8a"), "MediaTek", "MT6991")
        assertNotNull(reason)
        assertTrue(checkNotNull(reason).contains("MediaTek"))
    }

    @Test
    fun unsupportedQualcommSocIsRejected() {
        // Only the HTP v81 (SM8850) runtime is bundled, so other Qualcomm SoCs must not probe it.
        val reason = PoseAnalyzer.npuUnavailableReason(34, arrayOf("arm64-v8a"), "Qualcomm", "SM8750")
        assertNotNull(reason)
        assertTrue(checkNotNull(reason).contains("SM8850"))
    }
}
