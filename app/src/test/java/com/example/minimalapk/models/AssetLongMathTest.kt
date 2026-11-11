package com.example.minimalapk.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssetLongMathTest {
    @Test
    fun byteCountsBeyondIntMaxUseLongArithmetic() {
        val declared = Int.MAX_VALUE.toLong() + 123_456_789L

        assertEquals(declared - 7L, remainingDeclaredBytes(declared, 7L))
        assertEquals(declared, remainingDeclaredBytes(declared, 0L))
        assertEquals(0L, remainingDeclaredBytes(declared, declared))
    }

    @Test
    fun chunkLengthIsClampedWithoutIntOverflow() {
        val large = Int.MAX_VALUE.toLong() + 5_000_000_000L

        assertEquals(256 * 1024, nextChunkLength(large, 256 * 1024))
        assertEquals(1, nextChunkLength(1L, 256 * 1024))
        assertEquals(1024, nextChunkLength(1024L, 256 * 1024))
    }

    @Test
    fun invalidCountersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            remainingDeclaredBytes(10L, 11L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            remainingDeclaredBytes(0L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            nextChunkLength(0L, 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            nextChunkLength(1L, 0)
        }
    }
}
