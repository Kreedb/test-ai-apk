package com.example.minimalapk.plugins

import java.util.concurrent.CancellationException

/** Local request generations supplement the host's session/config token (cancel is not config). */
internal class LiteRtRequestGate {
    private var generation = 0L
    @Synchronized fun next(): Long = ++generation
    @Synchronized fun current(): Long = generation
    @Synchronized fun accepts(value: Long): Boolean = value == generation
}

/** Native ownership stays in AsyncModelSlot; this bounded dispatcher never waits on the camera/UI.
 * One request can wait for compilation. Cancel invalidates even a native call that cannot abort.
 */
internal class LiteRtWork<R : AutoCloseable>(
    name: String,
    private val open: (PluginContext) -> R,
    private val status: (PluginContext, String, Boolean) -> Unit,
    private val ready: (PluginContext) -> Unit,
) : AutoCloseable {
    private data class Owner(val context: PluginContext, val version: Long)
    private data class Work<R>(
        val owner: Owner,
        val generation: Long,
        val valid: () -> Boolean,
        val run: (R, () -> Boolean) -> Unit,
    )
    private class WorkFailure(val generation: Long, cause: Throwable) : RuntimeException(cause)
    private val lock = Any()
    private var version = 0L
    private var owner: Owner? = null
    private var loaded: Owner? = null
    private var waiting: Work<R>? = null
    private var unavailable = false
    private var loadGeneration = 0L
    private var deferred = false
    private var failureMessage: String? = null
    private val gate = LiteRtRequestGate()

    private val slot = AsyncModelSlot<Owner, R>(
        "${name}Owner",
        open = { request ->
            if (!isOwner(request)) throw CancellationException()
            open(request.context)
        },
        ready = { request, _ -> onReady(request) },
        failed = { request, failure ->
            synchronized(lock) {
                if (isOwner(request)) {
                    val requestedDuringLoad = waiting?.let { gate.accepts(it.generation) && it.valid() } == true
                    loaded = null
                    waiting = null
                    unavailable = true
                    failureMessage = (failure.cause ?: failure).message ?: "模型加载或推理失败"
                    if (if (failure is WorkFailure) gate.accepts(failure.generation)
                        else gate.accepts(loadGeneration) || requestedDuringLoad) {
                        status(request.context, checkNotNull(failureMessage), true)
                    }
                }
            }
        },
    )
    private val worker = BoundedFrameWorker<Work<R>>(
        "${name}Work",
        failed = { work, error ->
            synchronized(lock) {
                if (isWork(work)) status(work.owner.context, error.message ?: "处理失败", true)
            }
        },
    ) { work ->
        val ran = if (isWork(work)) slot.runIfReady { key, engine ->
            if (key == work.owner && isWork(work)) {
                try {
                    work.run(engine) { isWork(work) }
                } catch (error: CancellationException) {
                    // A canceled request must not poison a healthy model or become a failure report.
                } catch (error: Throwable) {
                    throw WorkFailure(work.generation, error)
                }
                true
            } else false
        } else false
        synchronized(lock) {
            if (ran == null && unavailable && isWork(work))
                status(work.owner.context, failureMessage ?: "模型已停止；请点击重新加载模型后重试", true)
        }
    }

    private fun onReady(request: Owner) {
        synchronized(lock) {
            if (isOwner(request)) {
                loaded = request
                unavailable = false
                val pending = waiting
                waiting = null
                if (pending == null && gate.accepts(loadGeneration)) ready(request.context)
                pending?.let { worker.offer(it) }
            }
        }
    }

    private fun isOwner(value: Owner): Boolean = synchronized(lock) {
        owner == value && value.context.isCurrent()
    }
    private fun isWork(value: Work<R>): Boolean = synchronized(lock) {
        isOwner(value.owner) && gate.accepts(value.generation) && value.valid()
    }

    fun isReady(context: PluginContext): Boolean = synchronized(lock) {
        loaded?.let { it.context.token == context.token && isOwner(it) } == true && !unavailable
    }

    fun start(context: PluginContext, deferLoad: Boolean = false) = synchronized(lock) {
        if (!context.isCurrent()) return@synchronized
        loadGeneration = gate.next()
        waiting = null
        loaded = null
        unavailable = false
        failureMessage = null
        deferred = deferLoad
        worker.clear()
        val next = Owner(context, ++version)
        owner = next
        status(context, if (deferLoad) "已启用；点击相机操作后检查并加载已安装模型" else "正在加载已安装模型…", false)
        slot.set(if (deferLoad) null else next)
    }

    /** replace=false is for successive bounded stream windows, not competing user requests. */
    fun submit(
        context: PluginContext,
        replace: Boolean = true,
        valid: () -> Boolean = { true },
        operation: (R, () -> Boolean) -> Unit,
    ): Boolean = synchronized(lock) {
        val current = owner?.takeIf { it.context.token == context.token && isOwner(it) } ?: return false
        if (!valid()) return false
        if (unavailable) {
            status(context, failureMessage ?: "模型未就绪；请先完成下载，再点击重新加载模型", true)
            return false
        }
        val generation = if (replace) gate.next() else gate.current()
        val work = Work(current, generation, valid, operation)
        if (loaded == current) worker.offer(work) else waiting = work
        if (deferred) {
            deferred = false
            slot.set(current)
        }
        true
    }

    /** Atomic with cancel/start: a result cannot pass a check, then publish after cancel. */
    fun publishIf(
        valid: () -> Boolean,
        context: PluginContext,
        output: PluginOutput,
        accepted: () -> Unit = {},
    ): Boolean = synchronized(lock) {
        if (valid() && context.isCurrent() && context.publish(output)) { accepted(); true } else false
    }

    fun cancel() = synchronized(lock) {
        gate.next()
        waiting = null
        worker.clear()
        // A canceled first request must not finish loading a multi-GB model just to leave it idle.
        // AsyncModelSlot closes any in-flight open only after native construction returns.
        if (loaded == null && !unavailable && !deferred && owner != null) {
            owner = Owner(checkNotNull(owner).context, ++version)
            deferred = true
            slot.set(null)
        }
    }

    fun stop() = synchronized(lock) {
        cancel()
        owner = null
        loaded = null
        slot.set(null)
    }

    override fun close() {
        stop()
        worker.close()
        slot.close()
    }
}
