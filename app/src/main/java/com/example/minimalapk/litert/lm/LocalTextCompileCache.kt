package com.example.minimalapk.litert.lm

import java.io.File
import java.util.UUID

/**
 * Per-request packed-weight scratch, NOT a model installation or an inference-state cache.
 * XNNPACK's :memory uses memfd_create, which actually returned ENOSYS on our API24/kernel3.10
 * emulator. Its delegate logs that failure and silently disables weight sharing, causing >7 GiB
 * RSS. A private writable cache path works on API24 and is removed only after Engine.close().
 */
internal class LocalTextCompileCache private constructor(val directory: File) : AutoCloseable {
    fun requirePackedWeights() {
        check(directory.listFiles()?.any { it.name.endsWith(".xnnpack_cache") && it.isFile && it.length() > 0L } == true) {
            "LiteRT-LM 未创建 XNNPACK 共享权重缓存；拒绝在无缓存模式继续生成"
        }
    }

    override fun close() {
        check(!directory.exists() || directory.deleteRecursively()) { "无法清理本地文本临时权重缓存" }
    }

    companion object {
        // Same fixed bundle, host CPU2: 874,644,712-byte packed cache. Round up to 1 GiB and
        // reserve another 512 MiB. Checked again per request; no guessed model-sized heap array.
        const val REQUIRED_FREE_BYTES = 1536L * 1024 * 1024
        const val MEASURED_CACHE_BYTES = 874_644_712L

        /** Caller holds the process-wide native permit, including cleanup of crash leftovers. */
        fun create(root: File, freeSpace: (File) -> Long = { it.usableSpace }): LocalTextCompileCache {
            check(root.isDirectory || root.mkdirs()) { "无法创建本地文本私有编译缓存目录" }
            root.listFiles()?.filter { it.name.startsWith("request-") }?.forEach {
                check(it.deleteRecursively()) { "无法清理上次中断的本地文本编译缓存" }
            }
            check(freeSpace(root) >= REQUIRED_FREE_BYTES) {
                "本地文本编译需要至少 1536 MiB 可用存储（实测约 834 MiB 权重缓存加余量）；不会退回无缓存模式"
            }
            val directory = File(root, "request-${UUID.randomUUID()}")
            check(directory.mkdir()) { "无法创建本次文本请求的独立编译缓存" }
            try {
                // Fail before native initialization if this sandbox cannot create/sync files.
                val probe = File(directory, "write-probe")
                probe.outputStream().use { output -> output.write(1); output.fd.sync() }
                check(probe.delete()) { "本地文本编译缓存不可写" }
                return LocalTextCompileCache(directory)
            } catch (failure: Throwable) {
                directory.deleteRecursively()
                throw failure
            }
        }
    }
}
