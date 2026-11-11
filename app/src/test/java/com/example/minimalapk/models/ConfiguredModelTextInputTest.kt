package com.example.minimalapk.models

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredModelTextInputTest {
    @Test
    fun utf8ElementsHaveExactBytesWithoutJniTerminatorOrModifiedEncoding() {
        val input = configuredTextInput("hello\n世界\n🙂\na\u0000b", listOf(4)) as Array<*>
        assertArrayEquals(byteArrayOf(0x68, 0x65, 0x6c, 0x6c, 0x6f), input[0] as ByteArray)
        assertArrayEquals(byteArrayOf(0xe4.toByte(), 0xb8.toByte(), 0x96.toByte(), 0xe7.toByte(), 0x95.toByte(), 0x8c.toByte()), input[1] as ByteArray)
        assertArrayEquals(byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x99.toByte(), 0x82.toByte()), input[2] as ByteArray)
        assertArrayEquals(byteArrayOf(0x61, 0, 0x62), input[3] as ByteArray)
    }

    @Test
    fun scalarKeepsEmbeddedNewlineWhileTensorKeepsRankRowOrderAndEmptyLaterElements() {
        assertArrayEquals(byteArrayOf(0x61, 0x0a, 0x62), configuredTextInput("a\nb", emptyList()) as ByteArray)
        val rows = configuredTextInput("a\n\nbc\nd", listOf(2, 2)) as Array<*>
        val first = rows[0] as Array<*>
        val second = rows[1] as Array<*>
        assertEquals(2, rows.size)
        assertArrayEquals(byteArrayOf(0x61), first[0] as ByteArray)
        assertArrayEquals(byteArrayOf(), first[1] as ByteArray)
        assertArrayEquals(byteArrayOf(0x62, 0x63), second[0] as ByteArray)
        assertArrayEquals(byteArrayOf(0x64), second[1] as ByteArray)
        val filled = configuredTextInput("é", listOf(1, 2)) as Array<*>
        (filled.single() as Array<*>).forEach { assertArrayEquals(byteArrayOf(0xc3.toByte(), 0xa9.toByte()), it as ByteArray) }
    }

    @Test
    fun invalidArityAndTheActualRuntimeEmptyFirstElementLimitationAreExplicit() {
        assertThrows(IllegalArgumentException::class.java) { configuredTextInput("a\nb\nc", listOf(2)) }
        for ((text, shape) in listOf("" to emptyList(), "\nvalue" to listOf(2))) {
            val failure = assertThrows(IllegalArgumentException::class.java) { configuredTextInput(text, shape) }
            assertTrue(failure.message!!.contains("首个 TEXT 元素为空"))
        }
    }
}
