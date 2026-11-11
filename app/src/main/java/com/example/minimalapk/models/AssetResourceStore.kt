package com.example.minimalapk.models

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Keep until all native handles, mapped tables and lazy readers using [directory] have closed. */
internal class AssetLease internal constructor(
    val directory: File,
    val fingerprint: String,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

/**
 * The one process-wide owner for filesDir/model_assets. A mutation invalidates host generations
 * before waiting for engine leases. Installed bytes are never changed under a live reader.
 */
internal class AssetResourceStore private constructor(val root: File) {
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private val gates = mutableMapOf<String, AssetSetAccess>()

    internal fun access(id: String): AssetSetAccess = synchronized(gates) {
        gates.getOrPut(requireSafeSetId(id)) { AssetSetAccess { mutableRevision.update { it + 1 } } }
    }

    /** Status only. Engines must acquire a lease rather than retaining this unprotected path. */
    fun installedDirectory(set: AssetSet): File? {
        val installer = readerInstaller()
        installer.recover(set.id)
        return access(set.id).read { installer.installedDirectory(set).takeIf { set.isReadyIn(root) } }
    }

    fun acquire(set: AssetSet): AssetLease {
        readerInstaller().recover(set.id)
        return access(set.id).acquire(set, root)
    }

    fun install(
        set: AssetSet,
        transport: AssetTransport,
        transfer: AssetTransfer = AssetTransfer(),
        onProgress: (AssetProgress) -> Unit = {},
    ) = AssetInstaller(root, transport).install(set, transfer, onProgress)

    fun importFiles(
        set: AssetSet,
        sources: List<AssetImportSource>,
        transfer: AssetTransfer = AssetTransfer(),
        onProgress: (AssetProgress) -> Unit = {},
    ) = readerInstaller().importFiles(set, sources, transfer, onProgress)

    fun delete(setId: String, transfer: AssetTransfer = AssetTransfer()) = readerInstaller().delete(setId, transfer)

    private fun readerInstaller() = AssetInstaller(root, object : AssetTransport {
        override fun open(request: AssetHttpRequest): AssetHttpResponse = error("此操作不访问网络")
    })

    companion object {
        private val stores = mutableMapOf<String, AssetResourceStore>()

        fun get(context: Context): AssetResourceStore = at(File(context.applicationContext.filesDir, "model_assets"))

        /** File-only seam also ensures separate installer instances share the same writer barrier. */
        fun at(root: File): AssetResourceStore = synchronized(stores) {
            val canonical = root.canonicalFile
            stores.getOrPut(canonical.path) { AssetResourceStore(canonical) }
        }
    }
}

internal class AssetSetAccess(private val changed: () -> Unit) {
    private val writer = ReentrantLock(true)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // wait/notify releases this monitor while leases drain
    private val lock = Object()
    private var changing = false
    private var readers = 0

    fun <T> read(action: () -> T): T? = synchronized(lock) { if (changing) null else action() }

    fun acquire(set: AssetSet, root: File): AssetLease = synchronized(lock) {
        check(!changing) { "${set.name} 正在安装或删除，请等待完成后重试" }
        check(set.isReadyIn(root)) { "${set.name} 的完整资源未安装或版本不符，请下载或导入全部文件" }
        readers++
        AssetLease(File(root, set.id), set.fingerprint) {
            synchronized(lock) {
                readers--
                lock.notifyAll()
            }
        }
    }

    fun <T> mutate(transfer: AssetTransfer, waiting: () -> Unit = {}, action: () -> T): T {
        try {
            transfer.checkpoint()
            while (!writer.tryLock(100, TimeUnit.MILLISECONDS)) transfer.checkpoint()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("等待资源操作时取消").apply { initCause(error) }
        }
        try {
            transfer.checkpoint()
            synchronized(lock) { changing = true }
            changed()
            try {
                waiting()
                synchronized(lock) {
                    while (readers > 0) {
                        transfer.checkpoint()
                        try { lock.wait(100) } catch (error: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw CancellationException("等待模型释放时取消").apply { initCause(error) }
                        }
                    }
                }
                transfer.checkpoint()
                return action()
            } finally {
                synchronized(lock) { changing = false }
                changed()
            }
        } finally {
            writer.unlock()
        }
    }
}
