package com.example.minimalapk.plugins

import java.util.concurrent.Executors

/** Optional async plugin helper: one running + one latest pending value, no unbounded frame queue.
 * Clear on disable/config/press cancellation; running work must still check its PluginContext.
 * Retained images are read-only GC-owned references, so replacement/drop needs no recycle call.
 */
internal class BoundedFrameWorker<T : Any>(
    name: String,
    private val failed: (T, Throwable) -> Unit,
    private val consume: (T) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }
    private var pending: T? = null
    private var scheduled = false
    private var closed = false

    fun offer(value: T): Boolean = synchronized(lock) {
        if (closed) return false
        pending = value
        if (!scheduled) {
            scheduled = true
            worker.execute {
                while (true) {
                    val next = synchronized(lock) {
                        val latest = pending
                        pending = null
                        if (latest == null) scheduled = false
                        latest
                    } ?: break
                    try {
                        consume(next)
                    } catch (error: Throwable) {
                        if (error !is java.util.concurrent.CancellationException) runCatching { failed(next, error) }
                    }
                }
            }
        }
        true
    }

    fun clear() = synchronized(lock) { pending = null }

    override fun close() = synchronized(lock) {
        closed = true
        pending = null
        worker.shutdown()
    }
}
