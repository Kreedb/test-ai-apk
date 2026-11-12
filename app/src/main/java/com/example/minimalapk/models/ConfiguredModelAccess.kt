package com.example.minimalapk.models

import org.tensorflow.lite.Interpreter
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Native handles for configured models. Cancellation never closes an Interpreter mid-invoke. */
internal object ConfiguredModelAccess {
    private val lock = Any()
    private val deleting = mutableSetOf<String>()
    private val readers = mutableMapOf<String, MutableSet<ConfiguredInterpreter>>()
    private val inspections = mutableMapOf<String, MutableSet<ConfiguredInspection>>()
    private val oneShot = ReentrantLock(true)
    private val requestOrder = Any()
    private var requestedRun: Pair<String, AssetTransfer>? = null

    /** One active native single-shot and one latest intent, even across closing/reopening a page. */
    fun <T> run(model: ConfiguredModel, transfer: AssetTransfer, action: () -> T): T {
        transfer.checkpoint()
        val request = model.file.absolutePath to transfer
        synchronized(requestOrder) {
            transfer.checkpoint()
            val previous = synchronized(lock) {
                check(request.first !in deleting) { "模型正在删除" }
                requestedRun
            }
            previous?.second?.cancel()
            synchronized(lock) {
                check(request.first !in deleting) { "模型正在删除" }
                requestedRun = request
            }
        }
        var acquired = false
        try {
            transfer.checkpoint()
            while (!oneShot.tryLock(100, TimeUnit.MILLISECONDS)) transfer.checkpoint()
            acquired = true
            transfer.checkpoint()
            return action()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            transfer.checkpoint()
            throw error
        } finally {
            if (acquired) oneShot.unlock()
            synchronized(lock) { if (requestedRun == request) requestedRun = null }
        }
    }

    fun cancelRun() { synchronized(lock) { requestedRun?.second }?.cancel() }

    fun cancelRun(model: ConfiguredModel) {
        synchronized(lock) { requestedRun?.takeIf { it.first == model.file.absolutePath }?.second }?.cancel()
    }

    fun checkAvailable(model: ConfiguredModel) = synchronized(lock) {
        check(model.file.absolutePath !in deleting) { "模型正在删除，请稍后重试" }
    }

    fun open(model: ConfiguredModel, options: Interpreter.Options = Interpreter.Options().setNumThreads(2)): ConfiguredInterpreter {
        val owner = synchronized(lock) {
            val path = model.file.absolutePath
            check(path !in deleting) { "模型正在删除，请稍后重试" }
            check(model.file.isFile) { "模型文件不存在，请重新导入" }
            ConfiguredInterpreter { reader ->
                synchronized(lock) {
                    readers[path]?.let { active ->
                        active.remove(reader)
                        if (active.isEmpty()) readers.remove(path)
                    }
                }
            }.also { readers.getOrPut(path) { mutableSetOf() }.add(it) }
        }
        try { owner.open(model.file, options) } catch (error: Throwable) { owner.close(); throw error }
        return owner
    }

    /** A diagnostic may need two interpreters or a fixed audio pipeline. Hold one path lease
     * across all of them so deletion cancels its next step and waits for every finally block. */
    fun <T> inspect(model: ConfiguredModel, action: (ConfiguredInspection) -> T): T {
        val path = model.file.absolutePath
        val inspection = synchronized(lock) {
            check(path !in deleting) { "模型正在删除，请稍后重试" }
            ConfiguredInspection().also { inspections.getOrPut(path) { mutableSetOf() }.add(it) }
        }
        return try { action(inspection) } finally {
            synchronized(lock) {
                inspections[path]?.let { active ->
                    active.remove(inspection)
                    if (active.isEmpty()) inspections.remove(path)
                }
            }
            inspection.finish()
        }
    }

    fun cancel(model: ConfiguredModel) {
        cancelRun(model)
        val active = synchronized(lock) {
            readers[model.file.absolutePath]?.toList().orEmpty() to inspections[model.file.absolutePath]?.toList().orEmpty()
        }
        active.second.forEach { it.cancel() }
        active.first.forEach { it.cancel() }
    }

    /** The caller invalidates observers, invokes drain, then unlinks; opens are barred throughout. */
    fun <T> delete(model: ConfiguredModel, action: (drain: () -> Unit) -> T): T {
        val path = model.file.absolutePath
        val active = synchronized(lock) {
            check(deleting.add(path)) { "模型正在删除" }
            readers[path]?.toList().orEmpty() to inspections[path]?.toList().orEmpty()
        }
        return try {
            action {
                cancelRun(model)
                active.second.forEach { it.cancel() }
                active.first.forEach { it.cancel() }
                active.second.forEach { it.awaitFinished() }
                active.first.forEach { it.close() }
            }
        } finally { synchronized(lock) { deleting.remove(path) } }
    }
}

internal class ConfiguredInspection {
    private val finished = java.util.concurrent.CountDownLatch(1)
    val cancellation = AssetTransfer()
    fun cancel() = cancellation.cancel()
    fun checkpoint() = cancellation.checkpoint()
    fun finish() = finished.countDown()
    fun awaitFinished() = finished.await()
}

internal class ConfiguredInterpreter(
    private val release: (ConfiguredInterpreter) -> Unit,
) : AutoCloseable {
    private val operation = ReentrantLock()
    private val nativeLock = Any()
    private var closed = false
    private var closeFailure: Throwable? = null
    private var interpreter: Interpreter? = null
    val cancellation = AssetTransfer()

    // LiteRT explicitly allows setCancelled from another thread; close and setCancelled must
    // still exclude each other. A running invoke owns operation until its finally block drains.
    private val registration = cancellation.onCancel {
        synchronized(nativeLock) { if (!closed) interpreter?.setCancelled(true) }
    }

    internal fun open(file: File, options: Interpreter.Options) = operation.withLock {
        cancellation.checkpoint()
        val opened = Interpreter(file, options.setCancellable(true))
        synchronized(nativeLock) { interpreter = opened }
        cancellation.checkpoint()
    }

    fun cancel() = cancellation.cancel()

    fun <T> run(discard: (T) -> Unit = {}, action: (Interpreter) -> T): T = operation.withLock {
        cancellation.checkpoint()
        val result = try { action(checkNotNull(interpreter)) } catch (error: Throwable) {
            cancellation.checkpoint()
            throw error
        }
        try { cancellation.checkpoint() } catch (error: Throwable) {
            discard(result)
            throw error
        }
        result
    }

    override fun close() {
        cancel()
        var released = false
        try {
            operation.withLock {
                synchronized(nativeLock) {
                    if (closed) { closeFailure?.let { throw it }; return }
                    closed = true
                    try { interpreter?.close(); released = true }
                    catch (error: Throwable) { closeFailure = error; throw error }
                    finally { interpreter = null; registration.close() }
                }
            }
        } finally { if (released) release(this) }
    }
}
