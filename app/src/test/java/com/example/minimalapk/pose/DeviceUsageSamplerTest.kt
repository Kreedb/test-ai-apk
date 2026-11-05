package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceUsageSamplerTest {
    @Test
    fun parsesPercentWithUnitSuffix() {
        assertEquals(42f, parsePercent("42 %")!!, 0.01f)
    }

    @Test
    fun parsesPercentWithDecimal() {
        assertEquals(37.5f, parsePercent("37.5")!!, 0.01f)
    }

    @Test
    fun rejectsOutOfRangePercent() {
        assertNull(parsePercent("128"))
        assertNull(parsePercent("-4"))
    }

    @Test
    fun rejectsContentWithoutNumber() {
        assertNull(parsePercent("n/a"))
    }

    @Test
    fun parsesQualcommBusyTotalRatio() {
        assertEquals(25f, parseBusyTotal("25 100")!!, 0.01f)
    }

    @Test
    fun rejectsBusyTotalWithoutPositiveTotal() {
        assertNull(parseBusyTotal("0 0"))
        assertNull(parseBusyTotal("busy total"))
    }

    @Test
    fun computesCpuPercentFromProcessDelta() {
        assertEquals(50f, cpuPercent(cpuDeltaMillis = 50, wallDeltaMillis = 100, cores = 1)!!, 0.01f)
        assertEquals(25f, cpuPercent(cpuDeltaMillis = 50, wallDeltaMillis = 100, cores = 2)!!, 0.01f)
    }

    @Test
    fun rejectsCpuPercentWithoutPositiveDelta() {
        assertNull(cpuPercent(cpuDeltaMillis = 0, wallDeltaMillis = 0, cores = 1))
        assertNull(cpuPercent(cpuDeltaMillis = 10, wallDeltaMillis = -1, cores = 1))
        assertNull(cpuPercent(cpuDeltaMillis = 10, wallDeltaMillis = 100, cores = 0))
    }
}
