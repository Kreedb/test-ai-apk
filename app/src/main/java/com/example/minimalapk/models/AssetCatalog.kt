package com.example.minimalapk.models

import android.content.Context
import java.io.File
import java.net.URI

/** One downloadable file in a catalog set. Sizes and hashes come from the pinned upstream JSON. */
internal data class AssetFile(
    val name: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * A task-level download unit. All files must be staged and verified together before the set can
 * replace an already installed version. Attribution travels with the set because the upstream
 * catalog mixes licenses at file/model level.
 */
internal data class AssetSet(
    val id: String,
    val name: String,
    val group: String,
    val backend: String,
    val licenseName: String,
    val licenseUrl: String,
    val upstreamUrl: String,
    val modelCardUrl: String,
    val files: List<AssetFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }

    /** Cheap readiness check: exact sizes only, no hashing on the hot path. */
    fun isReadyIn(root: File): Boolean = files.all { file ->
        val installed = File(File(root, id), file.name)
        installed.isFile && installed.length() == file.bytes
    }
}

/**
 * Parses the generated tab-separated catalog. Deliberately line-oriented so the app needs no JSON
 * library: every non-comment line is one file row and escapes keep values unambiguous.
 */
internal object AssetCatalog {
    const val ASSET_PATH = "models/litert-models.tsv"

    private const val COLUMN_COUNT = 12
    private val taskIdPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    private val backends = setOf("cpu", "gpu", "npu", "mixed")

    fun load(context: Context): List<AssetSet> =
        context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }

    /**
     * [allowInsecureUrls] is a narrow test seam. Production parsing accepts HTTPS only, and never
     * accepts embedded credentials.
     */
    fun parse(text: String, allowInsecureUrls: Boolean = false): List<AssetSet> {
        val builders = LinkedHashMap<String, SetBuilder>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val fields = line.split('\t')
            require(fields.size == COLUMN_COUNT) {
                "模型目录第 ${index + 1} 行需要 $COLUMN_COUNT 列，实际 ${fields.size} 列"
            }
            val values = fields.map { unescapeField(it) }
            require(values.all { it.isNotBlank() }) { "模型目录第 ${index + 1} 行存在空字段" }

            val taskId = values[0]
            require(taskIdPattern.matches(taskId)) { "模型目录第 ${index + 1} 行 taskId 不安全：$taskId" }
            val name = values[1]
            val group = values[2]
            val backend = values[3]
            require(backend in backends) { "模型目录第 ${index + 1} 行 backend 不支持：$backend" }
            val licenseName = values[4]
            val licenseUrl = values[5]
            val upstreamUrl = values[6]
            val modelCardUrl = values[7]
            val fileName = values[8]
            val url = values[9]
            val bytes = values[10].toLongOrNull()
                ?: throw IllegalArgumentException("模型目录第 ${index + 1} 行字节数无效")
            require(bytes > 0) { "模型目录第 ${index + 1} 行字节数必须大于 0" }
            val sha256 = values[11].lowercase()
            require(sha256Pattern.matches(sha256)) {
                "模型目录第 ${index + 1} 行 SHA-256 必须是 64 位十六进制"
            }

            require(requireSafeFileName(fileName)) { "模型目录第 ${index + 1} 行文件名不安全：$fileName" }
            validateUrl(licenseUrl, allowInsecureUrls, index)
            validateUrl(upstreamUrl, allowInsecureUrls, index)
            validateUrl(modelCardUrl, allowInsecureUrls, index)
            validateUrl(url, allowInsecureUrls, index)

            val builder = builders.getOrPut(taskId) {
                SetBuilder(
                    AssetSet(
                        id = taskId,
                        name = name,
                        group = group,
                        backend = backend,
                        licenseName = licenseName,
                        licenseUrl = licenseUrl,
                        upstreamUrl = upstreamUrl,
                        modelCardUrl = modelCardUrl,
                        files = emptyList(),
                    ),
                )
            }
            require(builder.meta.name == name && builder.meta.group == group &&
                builder.meta.backend == backend && builder.meta.licenseName == licenseName &&
                builder.meta.licenseUrl == licenseUrl && builder.meta.upstreamUrl == upstreamUrl &&
                builder.meta.modelCardUrl == modelCardUrl
            ) { "模型目录中 taskId=$taskId 的元数据不一致" }
            require(builder.fileNames.add(fileName)) {
                "模型目录中 taskId=$taskId 存在重复文件名：$fileName"
            }
            builder.files += AssetFile(name = fileName, url = url, bytes = bytes, sha256 = sha256)
        }
        require(builders.isNotEmpty()) { "模型目录为空" }
        return builders.values.map { it.meta.copy(files = it.files.toList()) }
    }

    private fun validateUrl(url: String, allowInsecureUrls: Boolean, line: Int) {
        val uri = runCatching { URI(url) }.getOrElse {
            throw IllegalArgumentException("模型目录第 ${line + 1} 行 URL 无效")
        }
        val scheme = uri.scheme?.lowercase()
        val supported = scheme == "https" || (allowInsecureUrls && scheme == "http")
        require(supported) { "模型目录第 ${line + 1} 行只允许 HTTPS URL" }
        require(!uri.host.isNullOrEmpty()) { "模型目录第 ${line + 1} 行 URL 缺少主机名" }
        require(uri.rawUserInfo == null) { "模型目录第 ${line + 1} 行 URL 不能包含凭据" }
    }

    private fun unescapeField(value: String): String {
        if ('\\' !in value) return value
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\') {
                output.append(char)
                index++
                continue
            }
            require(index + 1 < value.length) { "模型目录字段以反斜杠结尾" }
            when (val escaped = value[index + 1]) {
                '\\' -> output.append('\\')
                't' -> output.append('\t')
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                else -> throw IllegalArgumentException("模型目录字段包含未知转义：\\$escaped")
            }
            index += 2
        }
        return output.toString()
    }

    private class SetBuilder(val meta: AssetSet) {
        val files = mutableListOf<AssetFile>()
        val fileNames = mutableSetOf<String>()
    }
}

/** A set id becomes a directory name, so keep it to a conservative ASCII identifier. */
internal fun requireSafeSetId(id: String): String {
    require(Regex("[a-z0-9][a-z0-9-]{0,63}").matches(id)) { "模型集合 ID 不安全：$id" }
    return id
}

/** File names must stay directly below the set directory: no separators, dots-only or control chars. */
internal fun requireSafeFileName(name: String): Boolean =
    name.isNotEmpty() &&
        name.length <= 128 &&
        name != "." &&
        name != ".." &&
        !name.startsWith(".") &&
        !name.contains("..") &&
        name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
