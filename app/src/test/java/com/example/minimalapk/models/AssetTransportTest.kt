package com.example.minimalapk.models

import com.example.minimalapk.pose.GitHubMirrorPreference
import com.example.minimalapk.pose.ModelDownload
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

class AssetTransportTest {
    @Test
    fun mirrorsOnlySupportedGithubSourcesAndRetainsHttpsCdnRedirects() {
        val opened = mutableListOf<String>()
        val connections = mutableListOf<Connection>()
        val transport = HttpAssetTransport("https://mirror.example/", connect = { uri ->
            opened += uri.toString()
            Connection(uri.toURL(), if (opened.size == 1) 302 else 200,
                headers = if (opened.size == 1) mapOf("Location" to "https://cdn.example/model?X-Amz-Signature=signed") else emptyMap())
                .also { connections += it }
        })
        transport.open(AssetHttpRequest("https://raw.githubusercontent.com/owner/repo/revision/model")).use { it.body.read() }
        assertEquals(listOf("https://mirror.example/https://raw.githubusercontent.com/owner/repo/revision/model",
            "https://cdn.example/model?X-Amz-Signature=signed"), opened)
        assertTrue(connections.all { it.disconnected })
        assertTrue(connections.all { !it.instanceFollowRedirects })
        for (host in listOf("huggingface.co", "storage.googleapis.com")) {
            val direct = HttpAssetTransport("https://mirror.example/", connect = { uri ->
                assertEquals(host, uri.host)
                Connection(uri.toURL(), 200)
            })
            direct.open(AssetHttpRequest("https://$host/repo/model")).close()
        }
    }

    @Test
    fun rejectsDowngradeUserInfoTokensAndExcessiveRedirectsWithoutOpeningThem() {
        for (destination in listOf("http://cdn.example/file", "https://user:pass@cdn.example/file",
            "https://cdn.example/file?token=private")) {
            var opens = 0
            lateinit var connection: Connection
            val transport = HttpAssetTransport(connect = { uri ->
                opens++
                Connection(uri.toURL(), 302, mapOf("Location" to destination)).also { connection = it }
            })
            assertThrows(IllegalArgumentException::class.java) {
                transport.open(AssetHttpRequest("https://example.com/model"))
            }
            assertEquals(1, opens)
            assertTrue(connection.disconnected)
        }
        var opens = 0
        val looping = HttpAssetTransport(maxRedirects = 2, connect = { uri ->
            opens++
            Connection(uri.toURL(), 307, mapOf("Location" to "/again"))
        })
        assertThrows(IllegalArgumentException::class.java) { looping.open(AssetHttpRequest("https://example.com/model")) }
        assertEquals(3, opens)
    }

    @Test
    fun exposesContentRangeAndSendsOnlyRangeValidatorAcrossRedirects() {
        val connections = mutableListOf<Connection>()
        val transport = HttpAssetTransport(connect = { uri ->
            Connection(uri.toURL(), if (connections.isEmpty()) 302 else 206,
                if (connections.isEmpty()) mapOf("Location" to "https://cdn.example/model")
                else mapOf("Content-Range" to "bytes 7-11/12", "ETag" to "\"version\""))
                .also { connections += it }
        })
        transport.open(AssetHttpRequest("https://example.com/model", 7L, "\"version\"")).use { response ->
            assertEquals("bytes 7-11/12", response.contentRange)
            assertEquals("\"version\"", response.etag)
        }
        connections.forEach {
            assertEquals("bytes=7-", it.getRequestProperty("Range"))
            assertEquals("\"version\"", it.getRequestProperty("If-Range"))
            assertNull(it.getRequestProperty("Authorization"))
            assertNull(it.getRequestProperty("Cookie"))
        }
    }

    @Test
    fun cancellationDuringConnectDisconnectsAndDoesNotReturnAResponse() {
        val transfer = AssetTransfer()
        lateinit var connection: Connection
        val transport = HttpAssetTransport(connect = { uri ->
            object : Connection(uri.toURL(), 200) {
                override fun getResponseCode(): Int { transfer.cancel(); return 200 }
            }.also { connection = it }
        })
        assertThrows(CancellationException::class.java) {
            transport.open(AssetHttpRequest("https://example.com/model", transfer = transfer))
        }
        assertTrue(connection.disconnected)
    }

    @Test
    fun streamOpenFailureAlwaysDisconnectsConnection() {
        lateinit var connection: Connection
        val transport = HttpAssetTransport(connect = { uri ->
            object : Connection(uri.toURL(), 200) {
                override fun getInputStream(): InputStream = throw IOException("body unavailable")
            }.also { connection = it }
        })
        assertThrows(IOException::class.java) { transport.open(AssetHttpRequest("https://example.com/model")) }
        assertTrue(connection.disconnected)
    }

    @Test
    fun legacyFetchUsesTheSameHttpsRedirectAndCancellationPolicy() {
        val connections = mutableListOf<Connection>()
        val transport = HttpAssetTransport(connect = { uri ->
            Connection(uri.toURL(), 302, mapOf("Location" to "http://unsafe.example/file")).also { connections += it }
        })
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownload.fetch("https://example.com/model", GitHubMirrorPreference(), transport = transport)
        }
        assertTrue(connections.single().disconnected)
        val cancelled = AssetTransfer().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            ModelDownload.fetch("https://example.com/model", GitHubMirrorPreference(), cancelled,
                transport = object : AssetTransport {
                    override fun open(request: AssetHttpRequest): AssetHttpResponse = error("must not connect")
                })
        }
    }

    private open class Connection(
        url: URL,
        private val code: Int,
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getContentLengthLong() = 0L
        override fun getContentType() = "application/octet-stream"
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    }
}
