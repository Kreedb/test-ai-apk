package com.example.minimalapk.models

import com.example.minimalapk.pose.rewriteGitHubUrl
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

internal const val ASSET_HTTP_OK = 200
internal const val ASSET_HTTP_PARTIAL = 206
internal const val ASSET_HTTP_RANGE_NOT_SATISFIABLE = 416

/** A request for a whole file or the remainder of a partial one. Never carries credentials. */
internal data class AssetHttpRequest(
    val url: String,
    val rangeStart: Long = 0L,
    val ifRangeEtag: String? = null,
    val transfer: AssetTransfer = AssetTransfer(),
)

internal class AssetHttpResponse(
    val code: Int,
    val contentLength: Long,
    val etag: String?,
    val contentType: String?,
    val body: InputStream,
    val contentRange: String? = null,
) : Closeable {
    override fun close() = body.close()
}

/** Test seam: JVM tests supply codes, ranges, ETags and truncated bodies without network access. */
internal interface AssetTransport {
    fun open(request: AssetHttpRequest): AssetHttpResponse
}

/**
 * Real transport. Redirects are followed manually so an HTTPS catalog URL can never be downgraded
 * and redirect chains stay bounded. Only supported GitHub URLs go through the mirror rewrite;
 * Hugging Face and Google Storage URLs stay direct.
 */
internal class HttpAssetTransport(
    private val mirrorPrefix: String? = null,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxRedirects: Int = 5,
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : AssetTransport {
    override fun open(request: AssetHttpRequest): AssetHttpResponse {
        request.transfer.checkpoint()
        require(request.rangeStart >= 0L) { "续传偏移无效" }
        var target = rewriteGitHubUrl(request.url, mirrorPrefix)
        var redirects = 0
        while (true) {
            request.transfer.checkpoint()
            val uri = requireHttpsUri(target, "下载地址")
            val connection = connect(uri)
            val cancellation = request.transfer.onCancel { connection.disconnect() }
            var handedOff = false
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (request.rangeStart > 0L) {
                    connection.setRequestProperty("Range", "bytes=${request.rangeStart}-")
                    request.ifRangeEtag?.let { connection.setRequestProperty("If-Range", it) }
                }
                request.transfer.checkpoint()
                val code = connection.responseCode
                request.transfer.checkpoint()
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                    require(!location.isNullOrBlank()) { "下载重定向缺少 Location" }
                    val next = runCatching { uri.resolve(location) }.getOrElse {
                        throw IllegalArgumentException("下载重定向地址无效")
                    }
                    requireHttpsUri(next.toString(), "下载重定向地址")
                    require(++redirects <= maxRedirects) { "下载重定向次数过多" }
                    target = next.toString()
                    continue
                }
                val stream = if (code in 200..299) connection.inputStream
                    else connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
                val response = AssetHttpResponse(
                    code = code,
                    contentLength = connection.contentLengthLong,
                    etag = connection.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() },
                    contentType = connection.contentType,
                    body = ConnectionInputStream(stream, connection, cancellation),
                    contentRange = connection.getHeaderField("Content-Range"),
                )
                handedOff = true
                return response
            } catch (error: Exception) {
                request.transfer.checkpoint()
                throw error
            } finally {
                if (!handedOff) {
                    cancellation.close()
                    connection.disconnect()
                }
            }
        }
    }

    private fun requireHttpsUri(raw: String, label: String): URI {
        val uri = runCatching { URI(raw.trim()) }.getOrElse {
            throw IllegalArgumentException("$label 无效")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "$label 只允许 HTTPS" }
        require(!uri.host.isNullOrEmpty()) { "$label 缺少主机名" }
        require(uri.rawUserInfo == null) { "$label 不能包含凭据" }
        // Catalog sources never need token-bearing query strings. Signed CDN redirect parameters
        // are allowed, but personal credentials must not become stored/logged source URLs.
        require(!Regex("(^|[&;])(access_token|token|authorization|hf_token)=", RegexOption.IGNORE_CASE)
            .containsMatchIn(uri.query.orEmpty())) { "$label 不能包含访问 token；请授权获取后通过文件选择器导入" }
        require(uri.port == -1 || uri.port == 443) { "$label 端口不受支持" }
        return uri
    }

    private class ConnectionInputStream(
        stream: InputStream,
        private val connection: HttpURLConnection,
        private val cancellation: AutoCloseable,
    ) : FilterInputStream(stream) {
        override fun close() {
            try {
                super.close()
            } finally {
                cancellation.close()
                connection.disconnect()
            }
        }
    }
}
