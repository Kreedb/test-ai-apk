package com.example.minimalapk.litert.lm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalTextCompileCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun lowStorageFailsBeforeNativeLoadAndInterruptedScratchIsRemoved() {
        val root = folder.newFolder()
        val interrupted = File(root, "request-interrupted").apply { mkdir() }
        File(interrupted, "incomplete.xnnpack_cache").writeText("incomplete")
        assertThrows(IllegalStateException::class.java) {
            LocalTextCompileCache.create(root) { LocalTextCompileCache.REQUIRED_FREE_BYTES - 1 }
        }
        assertFalse(interrupted.exists())
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun cacheAbsenceIsExplicitAndEachRequestHasFreshScratchThatCloses() {
        val root = folder.newFolder()
        val first = LocalTextCompileCache.create(root) { Long.MAX_VALUE }
        assertThrows(IllegalStateException::class.java) { first.requirePackedWeights() }
        File(first.directory, "weight.xnnpack_cache").writeText("native packed cache fixture")
        first.requirePackedWeights()
        val previous = first.directory
        first.close()
        assertFalse(previous.exists())
        LocalTextCompileCache.create(root) { Long.MAX_VALUE }.use { next ->
            assertTrue(next.directory != previous)
            assertThrows(IllegalStateException::class.java) { next.requirePackedWeights() }
        }
        assertTrue(root.listFiles().isNullOrEmpty())
    }
}
