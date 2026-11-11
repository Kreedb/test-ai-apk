package com.example.minimalapk.plugins

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** One loader/runner/closer thread and a conflated desired configuration. Loading never blocks the
 * camera; runIfReady borrows only a ready model and permits at most one in-flight inference.
 * Config changes immediately prevent new borrows; an existing borrow drains before close/reload.
 */
internal class AsyncModelSlot<K : Any, R : AutoCloseable>(
    name: String,
    private val open: (K) -> R,
    private val ready: (K, R) -> Unit = { _, _ -> },
    private val failed: (K, Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }
    private var desired: K? = null
    private var loadedKey: K? = null
    private var model: R? = null
    private var revision = 0L
    private var loadedRevision = -1L
    private var queued = false
    private var busy = false
    private var closed = false

    fun set(key: K?): Unit = synchronized(lock) {
        if (closed || desired == key) return
        desired = key
        revision++
        if (!queued) {
            queued = true
            worker.execute { reconcile() }
        }
    }

    private fun reconcile() {
        while (true) {
            val request = synchronized(lock) {
                if (loadedRevision == revision) {
                    queued = false
                    return
                }
                val old = model
                model = null
                loadedKey = null
                Triple(desired, revision, old)
            }
            runCatching { request.third?.close() }
            var opened: R? = null
            var failure: Throwable? = null
            try {
                request.first?.let { opened = open(it) }
            } catch (error: Throwable) {
                failure = error
            }
            val accepted = synchronized(lock) {
                if (request.second == revision) {
                    model = opened
                    loadedKey = request.first
                    loadedRevision = request.second
                    true
                } else false
            }
            if (!accepted) runCatching { opened?.close() }
            else request.first?.let { key ->
                opened?.let { runCatching { ready(key, it) } }
                failure?.takeUnless { it is CancellationException }?.let { runCatching { failed(key, it) } }
            }
        }
    }

    fun <T> runIfReady(operation: (K, R) -> T): T? {
        val task = synchronized(lock) {
            val current = model ?: return null
            val key = loadedKey ?: return null
            val version = revision
            if (closed || busy || loadedRevision != version || desired != key) return null
            busy = true
            worker.submit<T?> {
                try {
                    if (synchronized(lock) { closed || revision != version }) null
                    else operation(key, current)
                } catch (error: Throwable) {
                    // Do not retry a broken model on every camera frame. A new configuration/session
                    // may reopen it. This close is ordered after the inference on the same thread.
                    synchronized(lock) { if (model === current) model = null }
                    runCatching { current.close() }
                    if (error !is CancellationException) runCatching { failed(key, error) }
                    null
                } finally {
                    synchronized(lock) { busy = false }
                }
            }
        }
        return try {
            task.get()
        } catch (error: InterruptedException) {
            // The worker owns all borrowed inputs until it actually finishes; do not cancel it.
            Thread.currentThread().interrupt()
            throw CancellationException("Model wait interrupted")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        desired = null
        revision++
        if (!queued) {
            queued = true
            worker.execute { reconcile() }
        }
        worker.shutdown()
    }
}
