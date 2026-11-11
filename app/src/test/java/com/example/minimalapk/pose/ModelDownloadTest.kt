package com.example.minimalapk.pose

import com.example.minimalapk.models.sha256
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelDownloadTest {
    private val model = ByteArray(32).also {
        it[4] = 'T'.code.toByte()
        it[5] = 'F'.code.toByte()
        it[6] = 'L'.code.toByte()
        it[7] = '3'.code.toByte()
    }

    @Test
    fun acceptsMatchingSizeHashAndTfliteHeader() {
        ModelDownload.validateDownloaded(model, model.size.toLong(), model.sha256())
    }

    @Test
    fun rejectsSizeMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownload.validateDownloaded(model, model.size.toLong() + 1, model.sha256())
        }
    }

    @Test
    fun rejectsHashMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownload.validateDownloaded(model, model.size.toLong(), "0".repeat(64))
        }
    }

    @Test
    fun rejectsHtmlErrorPages() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownload.validateDownloaded("<html>not a model</html>".toByteArray(), null, null)
        }
    }
}
