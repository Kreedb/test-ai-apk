package com.example.minimalapk.pose

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A model the app fetches on demand instead of bundling; installed through the normal import path. */
data class RemoteModel(
    val name: String,
    val url: String,
    val boxFormat: BoxFormat? = null,
    val note: String = "",
)

/**
 * Downloadable models. The bundled APK keeps only YOLO26n-pose; everything else is fetched from
 * here or a user-supplied URL, so extra models no longer inflate every ABI package.
 *
 * shortcut: the file is served from `models/` on this repository's `main` branch via
 * raw.githubusercontent.com; renaming the branch/directory or making the repo private breaks it.
 */
object ModelCatalog {
    val models = listOf(
        RemoteModel(
            name = "YOLO11n-pose 640",
            url = "https://raw.githubusercontent.com/Kreedb/test-ai-apk/main/models/yolo11n_pose.tflite",
            boxFormat = BoxFormat.CENTER,
            note = "框为中心点+宽高，导入后自动设为 cxcywh",
        ),
    )
}

internal object ModelDownload {
    /** The picker and the downloader share one cap before the bytes become a whole-file allocation. */
    const val MAX_BYTES = 64 * 1024 * 1024

    fun fetch(url: String): ByteArray {
        val parsed = runCatching { URL(url.trim()) }.getOrElse {
            throw IllegalArgumentException("下载地址无效")
        }
        require(parsed.protocol == "https") { "只支持 https 下载地址" }
        val connection = parsed.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalArgumentException("HTTP $code")
            }
            val length = connection.contentLengthLong
            if (length > MAX_BYTES) {
                throw IllegalArgumentException("模型文件超过 ${MAX_BYTES / 1024 / 1024} MB 上限")
            }
            connection.inputStream.use { it.readCapped(MAX_BYTES) }
        } finally {
            connection.disconnect()
        }
    }
}

/** Bounds the read before it becomes a whole-file allocation. */
internal fun InputStream.readCapped(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read > limit - output.size()) {
            error("模型文件超过 ${limit / 1024 / 1024} MB 上限")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
