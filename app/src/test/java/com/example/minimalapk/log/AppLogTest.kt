package com.example.minimalapk.log

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogTest {
    @Test
    fun keepsOnlyTheNewestEntries() {
        AppLog.clear()
        repeat(1001) { AppLog.i("test", "entry $it") }

        val entries = AppLog.snapshot()

        assertEquals(1000, entries.size)
        assertEquals("entry 1", entries.first().message)
        assertEquals("entry 1000", entries.last().message)
        AppLog.clear()
    }
}
