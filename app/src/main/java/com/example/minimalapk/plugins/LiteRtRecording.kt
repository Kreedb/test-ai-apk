package com.example.minimalapk.plugins

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Deadline is measured from the user's action and never extended by permission notifications. */
internal class LiteRtRecordingIntent(private val clock: () -> Long = System::nanoTime) {
    private var sequence = 0L
    private var active: Long? = null
    private var session = 0L
    private var deadline = 0L

    @Synchronized fun begin(context: PluginContext, seconds: Int): Long {
        require(seconds in 2..10)
        session = context.token.session
        deadline = clock() + budgetNanos(seconds)
        return (++sequence).also { active = it }
    }

    @Synchronized fun wants(context: PluginContext): Boolean =
        active != null && session == context.token.session && context.isCurrent() && clock() < deadline

    @Synchronized fun expire(request: Long): Boolean {
        if (active != request || clock() < deadline) return false
        active = null
        return true
    }

    @Synchronized fun cancel() { active = null }

    companion object {
        const val RECOVERY_ALLOWANCE_SECONDS = 10
        fun budgetNanos(seconds: Int): Long = (seconds + RECOVERY_ALLOWANCE_SECONDS) * 1_000_000_000L
    }
}

/** One replaceable timer per bounded recorder, cancelled on completion/stop; never a retry loop. */
internal interface LiteRtRecordingDeadline : AutoCloseable {
    fun schedule(delayNanos: Long, expired: () -> Unit)
    fun cancel()
}

internal class LiteRtRecordingTimer : LiteRtRecordingDeadline {
    private val worker = ScheduledThreadPoolExecutor(1) {
        Thread(it, "LiteRtRecordingDeadline").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private var pending: java.util.concurrent.ScheduledFuture<*>? = null

    @Synchronized override fun schedule(delayNanos: Long, expired: () -> Unit) {
        pending?.cancel(false)
        pending = worker.schedule(Runnable { expired() }, delayNanos, TimeUnit.NANOSECONDS)
    }

    @Synchronized override fun cancel() { pending?.cancel(false); pending = null }

    @Synchronized override fun close() { cancel(); worker.shutdown() }
}
