package com.example.minimalapk.plugins

import com.example.minimalapk.pose.DeviceUsage
import com.example.minimalapk.pose.DeviceUsageSource
import com.example.minimalapk.pose.NpuSource
import com.example.minimalapk.pose.UsageMetrics
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One running read and one latest request. No timer, camera pixels or UI lock is retained.
 * A wedged sysfs driver may delay its own worker, never preview/disable/background callbacks.
 * Stop/config generations discard its late results and clear pending work without waiting for I/O.
 */
internal class MonitorUsageSampler(
    private val delegate: DeviceUsageSource,
    private val clock: () -> Long = System::nanoTime,
    executor: Executor? = null,
) : DeviceUsageSource, AutoCloseable {
    private data class Request(val source: NpuSource, val metrics: UsageMetrics, val generation: Long)
    private val ownedExecutor = if (executor == null) Executors.newSingleThreadExecutor {
        Thread(it, "SystemMonitorProbe").apply { isDaemon = true }
    } else null
    private val executor = executor ?: checkNotNull(ownedExecutor)
    private val lock = Any()
    private var generation = 0L
    private var pending: Request? = null
    private var resetPending = false
    private var running = false
    private var closed = false
    private var result = DeviceUsage()

    override fun sample(source: NpuSource, nowNanos: Long, metrics: UsageMetrics, stillNeeded: () -> Boolean): DeviceUsage =
        synchronized(lock) {
            if (closed || !metrics.any) return DeviceUsage()
            pending = Request(source, metrics, generation)
            scheduleLocked()
            result
        }

    override fun reset(): Unit = synchronized(lock) {
        if (closed) return
        generation++
        pending = null
        result = DeviceUsage()
        resetPending = true
        scheduleLocked()
    }

    private fun scheduleLocked() {
        if (running) return
        running = true
        // Submit while holding the short state lock so close cannot shut down the executor
        // between setting running=true and enqueueing the task. I/O runs outside this lock.
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val (reset, request) = synchronized(lock) {
                if (!resetPending && pending == null) { running = false; return }
                (resetPending to pending).also { resetPending = false; pending = null }
            }
            if (reset) delegate.reset()
            if (request == null || synchronized(lock) { closed || generation != request.generation }) continue
            val now = clock()
            val next = try {
                delegate.sample(request.source, now, request.metrics) {
                    synchronized(lock) { !closed && generation == request.generation }
                }
            } catch (error: Exception) {
                DeviceUsage(samplingFailure = error.message ?: error.javaClass.simpleName, sampledAtNanos = now)
            }
            synchronized(lock) {
                if (!closed && generation == request.generation) result = next
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            pending = null
            result = DeviceUsage()
            resetPending = true
            scheduleLocked()
            ownedExecutor?.shutdown()
        }
    }
}
