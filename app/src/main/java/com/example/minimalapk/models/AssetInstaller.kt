package com.example.minimalapk.models

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal class AssetInstallException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Streaming, resumable installer for catalog sets.
 *
 * Layout under [root] (`filesDir/model_assets`):
 *  - `<setId>/`                         the installed set, swapped in only after every file verifies
 *  - `.<setId>.partial/<file>.part`     resumable bytes plus a small `<file>.etag`
 *  - `.<setId>.staging/`                fully verified files waiting for the atomic directory swap
 *  - `.<setId>.backup/`                 previous set during the swap, restored on failure
 *
 * Files never pass through a `ByteArray`; counters and offsets are [Long] throughout.
 */
internal class AssetInstaller(
    private val root: File,
    private val transport: AssetTransport,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    fun installedDirectory(set: AssetSet): File = File(root, set.id)

    /** Cheap readiness: every file exists with the exact declared size. */
    fun isReady(set: AssetSet): Boolean = set.isReadyIn(root)

    fun install(set: AssetSet) {
        requireSafeSetId(set.id)
        require(set.files.isNotEmpty()) { "模型集合没有文件" }
        set.files.forEach { require(requireSafeFileName(it.name)) { "模型文件名不安全：${it.name}" } }
        check(root.isDirectory || root.mkdirs()) { "无法创建模型目录" }

        val target = File(root, set.id)
        val partialDirectory = File(root, ".${set.id}.partial")
        val stagingDirectory = File(root, ".${set.id}.staging")
        val backupDirectory = File(root, ".${set.id}.backup")

        recoverInterruptedSwap(target, backupDirectory)
        stagingDirectory.deleteRecursively()
        backupDirectory.deleteRecursively()
        ensureFreeSpace(set, partialDirectory)
        check(partialDirectory.isDirectory || partialDirectory.mkdirs()) { "无法创建临时下载目录" }
        check(stagingDirectory.isDirectory || stagingDirectory.mkdirs()) { "无法创建临时安装目录" }

        try {
            set.files.forEach { asset ->
                val part = File(partialDirectory, "${asset.name}.part")
                val etag = File(partialDirectory, "${asset.name}.etag")
                val staged = File(stagingDirectory, asset.name)
                prepareFile(asset, part, etag)
                check(part.length() == asset.bytes) { "暂存文件大小不符：${asset.name}" }
                check(part.renameTo(staged)) { "无法暂存文件：${asset.name}" }
                etag.delete()
            }
            set.files.forEach { asset ->
                val staged = File(stagingDirectory, asset.name)
                check(staged.isFile && staged.length() == asset.bytes) {
                    "暂存文件大小不符：${asset.name}"
                }
            }
            swapInstalled(target, stagingDirectory, backupDirectory)
            partialDirectory.deleteRecursively()
        } catch (throwable: Throwable) {
            // Partial files stay behind for a later resume; staging must never look installable.
            stagingDirectory.deleteRecursively()
            throw throwable
        }
    }

    /** Deletes the installed set and every private staging/partial directory for [setId]. */
    fun delete(setId: String) {
        requireSafeSetId(setId)
        File(root, setId).deleteRecursively()
        File(root, ".$setId.partial").deleteRecursively()
        File(root, ".$setId.staging").deleteRecursively()
        File(root, ".$setId.backup").deleteRecursively()
    }

    fun delete(set: AssetSet) = delete(set.id)

    private fun prepareFile(asset: AssetFile, part: File, etagFile: File) {
        if (part.isFile && part.length() == asset.bytes) {
            if (sha256Of(part) == asset.sha256) return
            part.delete()
            etagFile.delete()
        }
        if (part.isFile && part.length() > asset.bytes) {
            part.delete()
            etagFile.delete()
        }
        var restarted = false
        while (true) {
            val restart = try {
                attemptFile(asset, part, etagFile)
                false
            } catch (_: RestartDownload) {
                true
            }
            if (!restart) return
            if (restarted) throw AssetInstallException("下载服务器拒绝了续传请求")
            restarted = true
            part.delete()
            etagFile.delete()
        }
    }

    private fun attemptFile(asset: AssetFile, part: File, etagFile: File) {
        val existing = if (part.isFile) part.length() else 0L
        val storedEtag = etagFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        val resume = existing in 1..asset.bytes && storedEtag != null
        if (existing > 0L && !resume) {
            part.delete()
            etagFile.delete()
        }
        val offset = if (resume) existing else 0L

        transport.open(
            AssetHttpRequest(
                url = asset.url,
                rangeStart = offset,
                ifRangeEtag = if (resume) storedEtag else null,
            ),
        ).use { response ->
            when (response.code) {
                ASSET_HTTP_OK -> {
                    // A 200 means the server ignored the range (or the validator changed): start over.
                    if (offset > 0L) {
                        part.delete()
                        etagFile.delete()
                    }
                    rejectHtml(response)
                    if (response.contentLength >= 0 && response.contentLength != asset.bytes) {
                        throw sizeMismatch(asset, response.contentLength)
                    }
                    writeEtag(etagFile, response.etag)
                    val copied = copyBody(response.body, part, offset = 0L, expected = asset.bytes)
                    finishFile(asset, part, etagFile, copied)
                }

                ASSET_HTTP_PARTIAL -> {
                    if (!resume) throw AssetInstallException("服务器返回 206，但客户端没有请求续传")
                    if (response.etag != null && response.etag != storedEtag) {
                        throw RestartDownload()
                    }
                    rejectHtml(response)
                    val remaining = remainingDeclaredBytes(asset.bytes, offset)
                    if (response.contentLength >= 0 && response.contentLength != remaining) {
                        throw sizeMismatch(asset, offset + response.contentLength)
                    }
                    writeEtag(etagFile, response.etag ?: storedEtag)
                    val copied = copyBody(response.body, part, offset, asset.bytes)
                    finishFile(asset, part, etagFile, copied)
                }

                ASSET_HTTP_RANGE_NOT_SATISFIABLE -> {
                    if (offset == asset.bytes && sha256Of(part) == asset.sha256) return
                    part.delete()
                    etagFile.delete()
                    throw RestartDownload()
                }

                else -> throw AssetInstallException("下载失败：HTTP ${response.code}")
            }
        }
    }

    private fun finishFile(asset: AssetFile, part: File, etagFile: File, copied: CopyResult) {
        if (copied.bytes != asset.bytes) {
            throw AssetInstallException(
                "下载不完整：期望 ${asset.bytes} 字节，实际 ${copied.bytes} 字节；已保留部分文件以便续传",
            )
        }
        if (copied.sha256 != asset.sha256) {
            part.delete()
            etagFile.delete()
            throw AssetInstallException("SHA-256 不符，请重新下载")
        }
    }

    /**
     * Appends [offset]..[expected] from [body], hashing in the same pass. One extra byte is read
     * after the declared size so a longer response is rejected instead of silently truncated.
     */
    private fun copyBody(body: InputStream, target: File, offset: Long, expected: Long): CopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0L) {
            check(target.isFile && target.length() == offset) { "续传文件大小已变化" }
            FileInputStream(target).use { existing ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    val read = existing.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
        }
        FileOutputStream(target, offset > 0L).use { output ->
            val buffer = ByteArray(bufferSize)
            var written = offset
            while (true) {
                val capacity = nextChunkLength(
                    if (written < expected) expected - written + 1 else 1L,
                    buffer.size,
                )
                val read = body.read(buffer, 0, capacity)
                if (read < 0) break
                if (read == 0) continue
                written += read
                if (written > expected) {
                    output.close()
                    target.delete()
                    throw AssetInstallException("下载内容超过声明大小")
                }
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
            output.fd.sync()
            return CopyResult(written, digest.digest().toHex())
        }
    }

    private fun rejectHtml(response: AssetHttpResponse) {
        val contentType = response.contentType?.lowercase().orEmpty()
        if (contentType.startsWith("text/html")) {
            throw AssetInstallException("服务器返回了网页而不是模型文件")
        }
    }

    private fun sizeMismatch(asset: AssetFile, actual: Long): AssetInstallException =
        AssetInstallException("下载大小不符：期望 ${asset.bytes} 字节，实际 $actual 字节")

    private fun writeEtag(etagFile: File, etag: String?) {
        if (etag.isNullOrBlank()) {
            etagFile.delete()
        } else {
            etagFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
            etagFile.writeText(etag)
        }
    }

    private fun ensureFreeSpace(set: AssetSet, partialDirectory: File) {
        val alreadyDownloaded = set.files.sumOf { asset ->
            val part = File(partialDirectory, "${asset.name}.part")
            if (part.isFile) part.length().coerceIn(0L, asset.bytes) else 0L
        }
        val remaining = set.totalBytes - alreadyDownloaded
        if (remaining <= 0L) return
        val available = freeSpace(root)
        require(available >= reserveBytes) { "存储空间不足：需要至少 ${reserveBytes} 字节余量" }
        require(available - reserveBytes >= remaining) {
            "存储空间不足：还需要 $remaining 字节（另有 $reserveBytes 字节余量）"
        }
    }

    private fun recoverInterruptedSwap(target: File, backup: File) {
        if (!target.exists() && backup.exists()) {
            check(backup.renameTo(target)) { "无法恢复上次未完成的模型替换" }
        } else if (backup.exists()) {
            backup.deleteRecursively()
        }
    }

    private fun swapInstalled(target: File, staging: File, backup: File) {
        if (backup.exists()) check(backup.deleteRecursively()) { "无法清理旧的模型备份" }
        val hadPrevious = target.exists()
        if (hadPrevious) check(target.renameTo(backup)) { "无法暂存已安装的模型" }
        if (!staging.renameTo(target)) {
            if (hadPrevious) backup.renameTo(target)
            throw AssetInstallException("无法安装模型目录")
        }
        if (hadPrevious) backup.deleteRecursively()
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(bufferSize)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private class RestartDownload : Exception()

    private data class CopyResult(val bytes: Long, val sha256: String)

    companion object {
        const val DEFAULT_RESERVE_BYTES = 64L * 1024 * 1024
        const val DEFAULT_BUFFER_SIZE = 256 * 1024
    }
}

/** Long-safe arithmetic shared with tests that cannot create multi-gigabyte files. */
internal fun remainingDeclaredBytes(declared: Long, written: Long): Long {
    require(declared > 0L) { "声明大小必须大于 0" }
    require(written in 0L..declared) { "已写入字节数越界" }
    return declared - written
}

internal fun nextChunkLength(remaining: Long, bufferSize: Int): Int {
    require(remaining > 0L) { "剩余字节数必须大于 0" }
    require(bufferSize > 0) { "缓冲区必须大于 0" }
    return minOf(remaining, bufferSize.toLong()).toInt()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
