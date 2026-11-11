package com.example.minimalapk.plugins

import org.junit.Assert.*
import org.junit.Test

class LiteRtMemoryTest {
    @Test fun knownRamPeakIsRoundedUpWithHeadroomAndRequiresObservableAvailableMemory() {
        val required = LiteRtMemory.RAM_REQUIRED_AVAILABLE_BYTES
        assertTrue(required > LiteRtMemory.RAM_MEASURED_PEAK_BYTES + 512L * 1024 * 1024)
        assertNotNull(LiteRtMemory.blockingReason("image-tagging", null))
        assertNotNull(LiteRtMemory.blockingReason("image-tagging", LiteRtMemorySnapshot(0, false)))
        assertNotNull(LiteRtMemory.blockingReason("image-tagging", LiteRtMemorySnapshot(required - 1, false)))
        assertNull(LiteRtMemory.blockingReason("image-tagging", LiteRtMemorySnapshot(required, false)))
        assertNotNull(LiteRtMemory.blockingReason("image-tagging", LiteRtMemorySnapshot(required * 2, true)))
    }

    @Test fun unknownOtherTaskHasNoFabricatedMemoryEstimateButRespectsSystemLowMemory() {
        assertNull(LiteRtMemory.blockingReason("ocr", null))
        assertNull(LiteRtMemory.blockingReason("ocr", LiteRtMemorySnapshot(1024, false)))
        assertNotNull(LiteRtMemory.blockingReason("ocr", LiteRtMemorySnapshot(1024, true)))
        val reason = LiteRtMemory.blockingReason("image-tagging", LiteRtMemorySnapshot(1024, false))!!
        assertTrue(reason.contains("3.50 GiB"))
        assertTrue(reason.contains("系统当前报告"))
    }
}
