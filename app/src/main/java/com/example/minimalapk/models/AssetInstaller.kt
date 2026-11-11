package com.example.minimalapk.models

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal open class AssetInstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class AssetAccessRequiredException(val statusCode: Int) : AssetInstallException(
    "HTTP $statusCode：此资源需要来源站点授权；请依法取得完整文件后使用文件选择器导入。不是模型损坏。",
)

internal class AssetImportAccessException(cause: SecurityException) : AssetInstallException(
    "所选文件的读取授权已失效或被拒绝，请重新选择已获授权的完整文件。", cause,
)

internal data class AssetProgress(
    val setId: String,
    val fileName: String?,
    val fileBytes: Long,
    val fileTotalBytes: Long,
    val completedBytes: Long,
    val totalBytes: Long,
    val stage: String,
)

/** A SAF provider may not know [size]; the actual stream is always bounded and verified. */
internal data class AssetImportSource(val displayName: String, val size: Long?, val open: () -> InputStream)

/** Validate the entire selection before opening even the first provider stream. */
internal fun matchAssetImportSources(set: AssetSet, sources: List<AssetImportSource>): Map<String, AssetImportSource> {
    require(sources.size == set.files.size) { "需要完整的 ${set.files.size} 个文件，已选择 ${sources.size} 个" }
    require(sources.map { it.displayName }.toSet().size == sources.size) { "所选文件名重复，请按目录中的实际文件名选择" }
    val mapped = sources.associateBy { it.displayName }
    require(mapped.keys == set.files.map { it.name }.toSet()) {
        "文件名不匹配；缺少：${set.files.map { it.name }.filterNot { it in mapped }.joinToString()}；" +
            "多余：${mapped.keys.filterNot { name -> set.files.any { it.name == name } }.joinToString()}"
    }
    set.files.forEach { asset ->
        mapped.getValue(asset.name).size?.let { size ->
            require(size == asset.bytes) { "${asset.name} 大小不符：需要 ${asset.bytes} 字节，所选文件 $size 字节" }
        }
    }
    return mapped
}

/**
 * One writer per canonical root/set, even across instances. Every weight is streamed to a private
 * partial file. Complete files stay resumable until the whole set is verified, then a short
 * receipt+directory rename transaction publishes it. Readers hold AssetResourceStore leases.
 */
internal class AssetInstaller(
    private val root: File,
    private val transport: AssetTransport,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    init {
        require(reserveBytes >= 0L && bufferSize > 0)
    }

    private val store = AssetResourceStore.at(root)

    fun installedDirectory(set: AssetSet): File = File(root, requireSafeSetId(set.id))

    fun isReady(set: AssetSet): Boolean {
        recover(set.id)
        return store.access(set.id).read { set.isReadyIn(root) } == true
    }

    fun install(
        set: AssetSet,
        transfer: AssetTransfer = AssetTransfer(),
        onProgress: (AssetProgress) -> Unit = {},
    ) = installFrom(set, null, transfer, onProgress)

    fun importFiles(
        set: AssetSet,
        sources: List<AssetImportSource>,
        transfer: AssetTransfer = AssetTransfer(),
        onProgress: (AssetProgress) -> Unit = {},
    ) {
        validateSet(set)
        transfer.checkpoint()
        val matched = matchAssetImportSources(set, sources)
        try {
            installFrom(set, matched, transfer, onProgress)
        } catch (error: SecurityException) {
            transfer.checkpoint()
            throw AssetImportAccessException(error)
        }
    }

    private fun installFrom(
        set: AssetSet,
        sources: Map<String, AssetImportSource>?,
        transfer: AssetTransfer,
        onProgress: (AssetProgress) -> Unit,
    ) {
        validateSet(set)
        var completed = 0L
        fun report(asset: AssetFile? = null, bytes: Long = 0L, stage: String) {
            transfer.checkpoint()
            onProgress(AssetProgress(set.id, asset?.name, bytes, asset?.bytes ?: 0L,
                completed + bytes, set.totalBytes, stage))
        }
        store.access(set.id).mutate(transfer, waiting = { report(stage = "等待使用此资源的模型释放") }) {
            check(root.isDirectory || root.mkdirs()) { "无法创建模型目录" }
            val target = installedDirectory(set)
            val partial = File(root, ".${set.id}.partial")
            val staging = File(root, ".${set.id}.staging")
            val backup = File(root, ".${set.id}.backup")
            recoverLocked(set.id)

            // Upgrade old verified-by-size installations without copying/moving their weights.
            // A receipt is written only after every declared hash has actually been checked.
            if (set.files.all { File(target, it.name).let { file -> file.isFile && file.length() == it.bytes } } &&
                set.files.all { asset ->
                    report(asset, stage = "核验已有资源")
                    sha256Of(File(target, asset.name), transfer) == asset.sha256.lowercase()
                }) {
                transfer.commit { writeReceipt(target, set) }
                partial.deleteRecursively()
                staging.deleteRecursively()
                return@mutate
            }

            val version = File(partial, ".fingerprint")
            if (readSmall(version) != set.fingerprint) {
                removeDirectory(partial)
                removeDirectory(staging)
            }
            check(partial.isDirectory || partial.mkdirs()) { "无法创建临时下载目录" }
            if (!version.exists()) writeSmall(version, set.fingerprint)
            restoreStaged(set, staging, partial)
            removeDirectory(staging)
            // Unknown/weak validators cannot be used with If-Range. Completed files can be
            // reused after hashing even when their server did not send an ETag.
            set.files.forEach { asset ->
                val part = File(partial, "${asset.name}.part")
                val etag = File(partial, "${asset.name}.etag")
                if (part.exists() && (part.length() > asset.bytes ||
                        (part.length() < asset.bytes && (sources != null || strongEtag(readSmall(etag)) == null)))) {
                    check(part.delete()) { "无法清理失效的部分文件" }
                    etag.delete()
                }
            }
            fun space() = ensureFreeSpace(set, partial)
            space()
            try {
                set.files.forEach { asset ->
                    transfer.checkpoint()
                    val part = File(partial, "${asset.name}.part")
                    val etag = File(partial, "${asset.name}.etag")
                    val progress: (Long) -> Unit = { bytes -> report(asset, bytes, if (sources == null) "下载并校验" else "导入并校验") }
                    if (sources == null) {
                        prepareFile(asset, part, etag, transfer, ::space, progress)
                    } else {
                        // SAF streams have no stable HTTP validator. Restart this component; a
                        // prior COMPLETE verified component is reusable, partial bytes are not.
                        if (!part.isFile || part.length() != asset.bytes || sha256Of(part, transfer) != asset.sha256.lowercase()) {
                            part.delete()
                            etag.delete()
                            space()
                            transfer.checkpoint()
                            transfer.use(sources.getValue(asset.name).open()) { input ->
                                finishFile(asset, part, etag, copyBody(input, part, 0L, asset.bytes, transfer, progress))
                            }
                        }
                    }
                    completed += asset.bytes
                    report(stage = "文件校验完成")
                }
                check(staging.isDirectory || staging.mkdirs()) { "无法创建临时安装目录" }
                set.files.forEach { asset ->
                    transfer.checkpoint()
                    check(rename(File(partial, "${asset.name}.part"), File(staging, asset.name))) { "无法暂存文件：${asset.name}" }
                }
                writeReceipt(staging, set)
                report(stage = "完成安装")
                transfer.commit { swapInstalled(target, staging, backup) }
                partial.deleteRecursively()
            } catch (error: Throwable) {
                // Preserve verified components and one resumable incomplete file. Recovery also
                // handles a process death in the middle of moving components to staging.
                try {
                    restoreStaged(set, staging, partial)
                    staging.deleteRecursively()
                } catch (cleanup: Throwable) {
                    // Leave recoverable staging intact and keep cancellation/the original error.
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
    }

    /** Recovery is safe before status/acquire as well as install; no engine sees the rename gap. */
    fun recover(setId: String, transfer: AssetTransfer = AssetTransfer()) {
        requireSafeSetId(setId)
        val access = store.access(setId)
        val needsRecovery = access.read {
            File(root, ".$setId.backup").exists() || File(root, ".$setId.deleted").exists()
        } ?: false
        if (needsRecovery) access.mutate(transfer) { recoverLocked(setId) }
    }

    fun delete(setId: String, transfer: AssetTransfer = AssetTransfer()) {
        requireSafeSetId(setId)
        store.access(setId).mutate(transfer) {
            recoverLocked(setId)
            val target = File(root, setId)
            val deleted = File(root, ".$setId.deleted")
            transfer.commit {
                if (target.exists()) check(rename(target, deleted)) { "无法删除模型目录，原资源已保留" }
                // The tombstone means process-death recovery completes deletion, never restores
                // a backup that the user just requested to remove.
                listOf("partial", "staging", "backup", "deleted").forEach { suffix ->
                    removeDirectory(File(root, ".$setId.$suffix"))
                }
            }
        }
    }

    fun delete(set: AssetSet, transfer: AssetTransfer = AssetTransfer()) = delete(set.id, transfer)

    private fun prepareFile(
        asset: AssetFile, part: File, etag: File, transfer: AssetTransfer,
        space: () -> Unit, progress: (Long) -> Unit,
    ) {
        if (part.isFile && part.length() == asset.bytes) {
            if (sha256Of(part, transfer) == asset.sha256.lowercase()) { progress(asset.bytes); return }
            part.delete()
            etag.delete()
        }
        var restarted = false
        while (true) {
            transfer.checkpoint()
            space()
            try {
                attemptFile(asset, part, etag, transfer, progress)
                return
            } catch (_: RestartDownload) {
                part.delete()
                etag.delete()
                if (restarted) throw AssetInstallException("下载服务器拒绝了有效的续传请求，请重新下载")
                restarted = true
            }
        }
    }

    private fun attemptFile(
        asset: AssetFile, part: File, etag: File, transfer: AssetTransfer, progress: (Long) -> Unit,
    ) {
        val storedEtag = strongEtag(readSmall(etag))
        val existing = if (part.isFile) part.length() else 0L
        val offset = if (existing in 1 until asset.bytes && storedEtag != null) existing else 0L
        transfer.checkpoint()
        transfer.use(transport.open(AssetHttpRequest(asset.url, offset, if (offset > 0) storedEtag else null, transfer))) { response ->
            when (response.code) {
                ASSET_HTTP_OK -> {
                    rejectHtml(response)
                    if (response.contentLength >= 0L && response.contentLength != asset.bytes) throw sizeMismatch(asset, response.contentLength)
                    part.delete()
                    writeEtag(etag, response.etag)
                    // A server returning 200 to Range restarts from zero, using the newly freed
                    // partial-file space. No bytes from different representations are combined.
                    finishFile(asset, part, etag, copyBody(response.body, part, 0L, asset.bytes, transfer, progress))
                }
                ASSET_HTTP_PARTIAL -> {
                    if (offset == 0L || response.etag != null && response.etag != storedEtag ||
                        !validAssetContentRange(response.contentRange, offset, asset.bytes)) throw RestartDownload()
                    rejectHtml(response)
                    val remaining = remainingDeclaredBytes(asset.bytes, offset)
                    if (response.contentLength >= 0L && response.contentLength != remaining) throw RestartDownload()
                    writeEtag(etag, response.etag ?: storedEtag)
                    finishFile(asset, part, etag, copyBody(response.body, part, offset, asset.bytes, transfer, progress))
                }
                ASSET_HTTP_RANGE_NOT_SATISFIABLE -> throw RestartDownload()
                401, 403 -> throw AssetAccessRequiredException(response.code)
                else -> throw AssetInstallException("下载失败：HTTP ${response.code}")
            }
        }
    }

    private fun finishFile(asset: AssetFile, part: File, etag: File, copied: CopyResult) {
        if (copied.bytes != asset.bytes) throw AssetInstallException(
            "下载/导入不完整：${asset.name} 期望 ${asset.bytes} 字节，实际 ${copied.bytes} 字节；完整旧资源已保留",
        )
        if (copied.sha256 != asset.sha256.lowercase()) {
            part.delete()
            etag.delete()
            throw AssetInstallException("${asset.name} SHA-256 不符，请重新下载或选择正确版本")
        }
    }

    private fun copyBody(
        body: InputStream, target: File, offset: Long, expected: Long,
        transfer: AssetTransfer, progress: (Long) -> Unit,
    ): CopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0L) {
            check(target.isFile && target.length() == offset) { "续传文件大小已变化" }
            hashInto(target, digest, transfer)
        }
        transfer.checkpoint()
        FileOutputStream(target, offset > 0L).use { output ->
            val buffer = ByteArray(bufferSize)
            var written = offset
            progress(written)
            while (true) {
                transfer.checkpoint()
                val remaining = remainingDeclaredBytes(expected, written)
                val read = body.read(buffer, 0, if (remaining == 0L) 1 else nextChunkLength(remaining, buffer.size))
                transfer.checkpoint()
                if (read < 0) break
                if (read == 0) continue
                if (read.toLong() > remaining) {
                    output.close()
                    target.delete()
                    throw AssetInstallException("下载/导入内容超过声明大小")
                }
                // Recheck remaining space during the stream as other apps/downloads consume it.
                ensureAvailable(read.toLong())
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                written += read
                progress(written)
            }
            transfer.checkpoint()
            output.fd.sync()
            return CopyResult(written, digest.digest().toHex())
        }
    }

    private fun rejectHtml(response: AssetHttpResponse) {
        if (response.contentType?.lowercase()?.startsWith("text/html") == true) {
            throw AssetInstallException("服务器返回了网页而不是模型文件")
        }
    }

    private fun sizeMismatch(asset: AssetFile, actual: Long) =
        AssetInstallException("${asset.name} 下载大小不符：期望 ${asset.bytes} 字节，实际 $actual 字节")

    private fun writeEtag(file: File, value: String?) {
        val strong = strongEtag(value)
        if (strong == null) file.delete() else writeSmall(file, strong)
    }

    private fun ensureFreeSpace(set: AssetSet, partial: File) {
        val downloaded = set.files.sumOf { asset ->
            File(partial, "${asset.name}.part").let { if (it.isFile) it.length().coerceIn(0L, asset.bytes) else 0L }
        }
        ensureAvailable(set.totalBytes - downloaded)
    }

    private fun ensureAvailable(needed: Long) {
        val available = freeSpace(root)
        require(available >= reserveBytes && available - reserveBytes >= needed) {
            "存储空间不足：还需要 $needed 字节（另有 $reserveBytes 字节余量）"
        }
    }

    private fun recoverLocked(id: String) {
        val target = File(root, id)
        val backup = File(root, ".$id.backup")
        val deleted = File(root, ".$id.deleted")
        if (deleted.exists()) {
            listOf("partial", "staging", "backup", "deleted").forEach { removeDirectory(File(root, ".$id.$it")) }
        } else if (backup.exists()) {
            if (!target.exists()) check(rename(backup, target)) { "无法恢复上次未完成的模型替换；备份仍已保留" }
            else removeDirectory(backup)
        }
    }

    private fun restoreStaged(set: AssetSet, staging: File, partial: File) {
        if (!staging.isDirectory) return
        check(partial.isDirectory || partial.mkdirs()) { "无法恢复临时资源" }
        set.files.forEach { asset ->
            val staged = File(staging, asset.name)
            if (staged.isFile) check(rename(staged, File(partial, "${asset.name}.part"))) { "无法恢复 ${asset.name} 的临时文件" }
        }
    }

    private fun swapInstalled(target: File, staging: File, backup: File) {
        val hadPrevious = target.exists()
        if (hadPrevious) check(rename(target, backup)) { "无法暂存已安装的模型" }
        if (!rename(staging, target)) {
            if (hadPrevious && !rename(backup, target)) {
                throw AssetInstallException("无法安装或立即恢复模型；旧资源保留在备份，下次读取时恢复")
            }
            throw AssetInstallException("无法安装模型目录，旧资源已保留")
        }
        // A cleanup failure must not turn a successfully committed install into a false failure.
        // recover() finishes this cleanup before another operation or engine acquisition.
        if (hadPrevious) backup.deleteRecursively()
    }

    private fun writeReceipt(directory: File, set: AssetSet) = writeSmall(File(directory, ASSET_RECEIPT), "1\n${set.fingerprint}\n")

    private fun sha256Of(file: File, transfer: AssetTransfer): String = MessageDigest.getInstance("SHA-256").let {
        hashInto(file, it, transfer)
        it.digest().toHex()
    }

    private fun hashInto(file: File, digest: MessageDigest, transfer: AssetTransfer) {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(bufferSize)
            while (true) {
                transfer.checkpoint()
                val read = input.read(buffer)
                transfer.checkpoint()
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
    }

    private fun validateSet(set: AssetSet) {
        requireSafeSetId(set.id)
        require(set.files.isNotEmpty()) { "模型集合没有文件" }
        require(set.files.map { it.name }.toSet().size == set.files.size) { "模型集合存在重复文件名" }
        set.files.forEach {
            require(requireSafeFileName(it.name)) { "模型文件名不安全" }
            require(it.bytes > 0L && Regex("[a-fA-F0-9]{64}").matches(it.sha256)) { "模型文件大小或 SHA-256 无效" }
        }
        set.totalBytes // Validate aggregate Long arithmetic too, including programmatic sets.
    }

    private fun readSmall(file: File): String? =
        if (file.isFile && file.length() <= 2048) file.readText(Charsets.UTF_8) else null

    private fun writeSmall(file: File, text: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(temporary.renameTo(file)) { "无法保存资源校验记录" }
        } finally { temporary.delete() }
    }

    private fun removeDirectory(file: File) {
        check(!file.exists() || file.deleteRecursively()) { "无法清理模型临时目录：${file.name}" }
    }

    private class RestartDownload : Exception()
    private data class CopyResult(val bytes: Long, val sha256: String)

    companion object {
        const val DEFAULT_RESERVE_BYTES = 64L * 1024 * 1024
        const val DEFAULT_BUFFER_SIZE = 256 * 1024
    }
}

internal fun strongEtag(value: String?): String? = value?.trim()?.takeIf {
    it.length in 2..1024 && it.startsWith('"') && it.endsWith('"') &&
        it.drop(1).dropLast(1).none { char -> char == '"' || char.code < 33 || char.code == 127 }
}

internal fun validAssetContentRange(value: String?, offset: Long, declared: Long): Boolean {
    val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE).matchEntire(value?.trim().orEmpty()) ?: return false
    val (start, end, total) = match.destructured
    return offset > 0L && declared > offset && start.toLongOrNull() == offset &&
        end.toLongOrNull() == declared - 1L && total.toLongOrNull() == declared
}

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
