package com.example.minimalapk.pose

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuSupportTest {
    @Test
    fun qualcommArm64OnAndroid12IsSupported() {
        assertNull(PoseAnalyzer.npuUnavailableReason(31, arrayOf("arm64-v8a"), "Qualcomm"))
        assertNull(PoseAnalyzer.npuUnavailableReason(34, arrayOf("arm64-v8a"), " QTI "))
    }

    @Test
    fun preAndroid12IsRejected() {
        val reason = PoseAnalyzer.npuUnavailableReason(30, arrayOf("arm64-v8a"), "Qualcomm")
        assertNotNull(reason)
        assertTrue(checkNotNull(reason).contains("31"))
    }

    @Test
    fun nonArm64AbiIsRejected() {
        val reason = PoseAnalyzer.npuUnavailableReason(34, arrayOf("x86_64"), "Qualcomm")
        assertNotNull(reason)
        assertTrue(checkNotNull(reason).contains("arm64-v8a"))
    }

    @Test
    fun nonQualcommSocIsRejected() {
        val reason = PoseAnalyzer.npuUnavailableReason(34, arrayOf("arm64-v8a"), "MediaTek")
        assertNotNull(reason)
        assertTrue(checkNotNull(reason).contains("MediaTek"))
    }
}
