package com.example.minimalapk.litert.lm

import com.example.minimalapk.plugins.localTextReportParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTextReportTest {
    @Test
    fun reportRespectsHostBoundsWithoutSplittingEmojiOrSilentlyDroppingTail() {
        val text = "x".repeat(239) + "\uD83D\uDE00" + "中".repeat(400)
        val parts = localTextReportParts(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 240 && runCatching { strictUtf8Bytes(it) }.isSuccess })
        val overflow = localTextReportParts("x".repeat(2000))
        assertEquals(7, overflow.size)
        assertTrue(overflow.last().endsWith("[报告显示已截断]"))
        assertTrue(overflow.all { it.length <= 240 })
    }
}
