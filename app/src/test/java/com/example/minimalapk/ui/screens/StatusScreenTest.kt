package com.example.minimalapk.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusScreenTest {
    @Test
    fun readsHardwareLineFromCpuinfo() {
        val cpuinfo = """
            processor	: 0
            model name	: ARMv8 Processor rev 1
            Hardware	: Qualcomm Technologies, Inc SM8850
            processor	: 1
        """.trimIndent()

        assertEquals("Qualcomm Technologies, Inc SM8850", cpuHardware(cpuinfo))
    }

    @Test
    fun ignoresCpuinfoWithoutHardwareLine() {
        assertNull(cpuHardware("processor\t: 0\nmodel name\t: ARMv8 Processor"))
        assertNull(cpuHardware("Hardware\t:   "))
        assertNull(cpuHardware(null))
    }
}
