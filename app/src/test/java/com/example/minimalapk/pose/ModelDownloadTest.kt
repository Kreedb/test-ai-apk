package com.example.minimalapk.pose

import com.example.minimalapk.models.sha256
import com.example.minimalapk.models.AssetHttpRequest
import com.example.minimalapk.models.AssetHttpResponse
import com.example.minimalapk.models.AssetTransfer
import com.example.minimalapk.models.AssetTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException

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

    @Test
    fun cappedReadsCheckCancellationAfterEveryReadIncludingEof() {
        for (cancelAtEof in listOf(false, true)) {
            val transfer = AssetTransfer()
            val stream = object : ByteArrayInputStream(model) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val read = super.read(bytes, offset, length)
                    if (!cancelAtEof || read == -1) transfer.cancel()
                    return read
                }
            }
            assertThrows(CancellationException::class.java) {
                stream.readCapped(64, transfer::checkpoint)
            }
        }
    }

    @Test
    fun cappedReadsAcceptExactLengthRejectOneExtraByteAndReportLongProgress() {
        val progress = mutableListOf<Long>()
        assertArrayEquals(model, ByteArrayInputStream(model).readCapped(model.size, onProgress = { progress += it }))
        assertEquals(listOf(0L, model.size.toLong()), progress)
        assertThrows(IllegalStateException::class.java) {
            ByteArrayInputStream(model + 1.toByte()).readCapped(model.size)
        }
        assertEquals(64 * 1024 * 1024, ModelDownload.MAX_BYTES)
    }

    @Test
    fun cancelledLegacyFetchClosesResponseAndCannotReachInstallation() {
        val transfer = AssetTransfer()
        var closed = false
        var installed = false
        val transport = object : AssetTransport {
            override fun open(request: AssetHttpRequest) = AssetHttpResponse(
                200, model.size.toLong(), null, "application/octet-stream",
                object : ByteArrayInputStream(model) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        val read = super.read(bytes, offset, length)
                        transfer.cancel()
                        return read
                    }
                    override fun close() { closed = true; super.close() }
                },
            )
        }
        assertThrows(CancellationException::class.java) {
            ModelDownload.fetch("https://example.com/model", GitHubMirrorPreference(), transfer, transport = transport)
            transfer.commit { installed = true }
        }
        assertTrue(closed)
        assertTrue(!installed)
    }
}
