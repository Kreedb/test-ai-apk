package com.example.minimalapk.pose

import com.example.minimalapk.models.AssetTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Path barrier for imported pose weights. Backends always close on their creating worker. */
internal object PoseModelAccess {
    private val lock = Any()
    private val deleting = mutableSetOf<String>()
    private val readers = mutableMapOf<String, MutableSet<PoseModelReader>>()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    fun isChanging(): Boolean = synchronized(lock) { deleting.isNotEmpty() }

    fun open(file: File, id: String, worker: ExecutorService, load: () -> PoseBackend?): PoseBackend? {
        val path = file.canonicalPath
        val reader = synchronized(lock) {
            check(path !in deleting) { "姿态模型正在删除，请稍后重试" }
            check(file.isFile) { "姿态模型文件不存在，请重新导入" }
            PoseModelReader(id, worker) { closed -> synchronized(lock) {
                readers[path]?.let { active ->
                    active.remove(closed)
                    if (active.isEmpty()) readers.remove(path)
                }
            } }.also { readers.getOrPut(path) { mutableSetOf() }.add(it) }
        }
        return reader.open(load)
    }

    fun checkAvailable(file: File) = synchronized(lock) {
        check(file.canonicalPath !in deleting) { "姿态模型正在删除，请稍后重试" }
    }

    fun <T> deleting(file: File, id: String, shared: Boolean, transfer: AssetTransfer, commit: () -> T): T {
        transfer.checkpoint()
        val path = file.canonicalPath
        val active = synchronized(lock) {
            check(deleting.add(path)) { "姿态模型正在删除" }
            readers[path].orEmpty().filter { !shared || it.id == id }
        }
        changes.update { it + 1 } // Reject in-flight frame publications before any native close.
        return try {
            active.forEach { it.stop() }
            active.forEach { it.awaitClosed(transfer) }
            transfer.checkpoint()
            commit()
        } finally {
            synchronized(lock) { deleting.remove(path) }
            // Also reload after failed/cancelled deletion: its saved preferences did not change.
            changes.update { it + 1 }
        }
    }
}

internal class PoseModelReader(
    val id: String,
    private val worker: ExecutorService,
    private val release: (PoseModelReader) -> Unit,
) : PoseBackend {
    private val ownerThread = Thread.currentThread()
    private val finished = CountDownLatch(1)
    private var backend: PoseBackend? = null
    private var closed = false
    @Volatile private var stopped = false
    @Volatile private var closeFailure: Throwable? = null
    val isCurrent: Boolean get() = !stopped
    override val name: String get() = backend?.name.orEmpty()

    fun open(load: () -> PoseBackend?): PoseBackend? {
        check(Thread.currentThread() === ownerThread)
        try {
            if (!stopped) backend = load()
            if (backend == null || stopped) { close(); return null }
            return this
        } catch (error: Throwable) { close(); throw error }
    }

    override fun analyze(pixels: IntArray): FloatArray {
        check(Thread.currentThread() === ownerThread)
        if (stopped) throw java.util.concurrent.CancellationException("姿态模型已停止")
        return checkNotNull(backend).analyze(pixels)
    }

    fun stop() {
        stopped = true // Prevent new invokes immediately, even while the worker is in native code.
        if (finished.count == 0L) return
        try { worker.execute { runCatching { close() } } } catch (error: Exception) {
            // A shutdown worker may still be draining a timed-out load which closes itself.
            if (worker.isTerminated && finished.count != 0L) { closeFailure = error; finished.countDown() }
        }
    }

    fun awaitClosed(transfer: AssetTransfer) {
        try {
            while (!finished.await(100, TimeUnit.MILLISECONDS)) transfer.checkpoint()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("等待姿态模型关闭时取消").apply { initCause(error) }
        }
        closeFailure?.let { throw IllegalStateException("姿态模型未能安全关闭，文件已保留，请重启应用后重试", it) }
    }

    override fun close() {
        check(Thread.currentThread() === ownerThread) { "姿态后端必须在创建线程关闭" }
        stopped = true
        if (closed) return
        closed = true
        try { backend?.close(); release(this) }
        catch (error: Throwable) { closeFailure = error; throw error }
        finally { finished.countDown() }
    }
}
