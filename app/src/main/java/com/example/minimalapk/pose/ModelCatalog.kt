package com.example.minimalapk.pose

import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.models.sha256
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
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

/**
 * Downloadable models. The bundled APK keeps only YOLO26n-pose; everything else is fetched from
 * here or a user-supplied URL, so extra models no longer inflate every ABI package.
 *
 * The URL is pinned to a commit so deleting the weight from the current tree does not break the
 * download. GitHub mirror settings rewrite it at fetch time; the pinned size and hash below still
 * validate the bytes that arrive from either route.
 */
object ModelCatalog {
    /** One download menu also covers the models used by the built-in scene plugins. */
    val configurableModels = ExampleCatalog.configurable + listOf(ExampleCatalog.superResolution, ExampleCatalog.personalization)

    val models = listOf(
        RemoteModel(
            name = "YOLO11n-pose 640",
            url = "https://raw.githubusercontent.com/Kreedb/test-ai-apk/" +
                "e9f51fe286a7ea8863d40113e51dc5a4b9d49e88/models/yolo11n_pose.tflite",
            boxFormat = BoxFormat.CENTER,
            note = "框为中心点+宽高，导入后自动设为 cxcywh",
            sizeBytes = 11_835_168,
            sha256 = "01b0d8c7e28369977c69d5c66a60c63301468a5a8ae24bafce942b61bab289c0",
        ),
    ) + configurableModels.map { example ->
        RemoteModel(
            name = example.name,
            url = example.url,
            sizeBytes = example.sizeBytes,
            sha256 = example.sha256,
            note = when (example) {
                ExampleCatalog.audio -> "音频文件推理；实时麦克风识别请使用声音识别插件"
                ExampleCatalog.personalization -> "保留训练/推理签名；训练与检查点在设备端学习插件中管理"
                ExampleCatalog.superResolution -> "图片超分；整帧相机增强请使用超分辨率插件"
                else -> "下载后点击模型卡片配置，开启后参与相机逐帧推理"
            },
        )
    }
}

internal object ModelDownload {
    /** The picker and the downloader share one cap before the bytes become a whole-file allocation. */
    const val MAX_BYTES = 64 * 1024 * 1024

    fun fetch(sourceUrl: String, mirror: GitHubMirrorPreference): ByteArray {
        // Only supported GitHub URLs are rewritten; every other source keeps its original address.
        val url = rewriteGitHubUrl(sourceUrl.trim(), mirror.prefix)
        val parsed = runCatching { URL(url) }.getOrElse {
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

    /**
     * Validates downloaded bytes before they can replace an installed file. Known catalog models
     * also pin their size and SHA-256, and every download must at least look like TFLite so an HTML
     * error page is never saved as a model.
     */
    fun validateDownloaded(bytes: ByteArray, expectedSize: Long?, expectedSha256: String?) {
        if (expectedSize != null) {
            require(bytes.size.toLong() == expectedSize) {
                "下载大小不符：期望 $expectedSize 字节，实际 ${bytes.size} 字节"
            }
        }
        if (expectedSha256 != null) {
            require(bytes.sha256() == expectedSha256) { "模型 SHA-256 不符，请重新下载" }
        }
        require(PoseModelImport.isTflite(bytes)) { "下载内容不是 TFLite 模型（可能是网页或错误页面）" }
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
