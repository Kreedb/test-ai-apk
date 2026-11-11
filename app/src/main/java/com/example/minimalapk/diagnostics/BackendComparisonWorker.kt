package com.example.minimalapk.diagnostics

import java.util.concurrent.CancellationException

internal data class BackendComparisonState(
    val version: Long = 0,
    val runId: Long? = null,
    val running: Boolean = false,
    val cancelling: Boolean = false,
    val current: String = "",
    val report: BackendComparisonReport? = null,
    val failure: String? = null,
) {
    fun compactLines(): List<String> = when {
        running -> listOf("后端比较 #$runId · $current") + (report?.compactLines()?.drop(1) ?: emptyList())
        failure != null -> listOf("后端比较 #$runId · $current：${failure.take(180)}")
        else -> report?.compactLines() ?: emptyList()
    }
}

/** One run owns the guard until native cleanup returns, including after a camera/tab exit.
 * Creates a thread only on an explicit start; no executor, polling, timers or idle worker survives.
 */
internal class BackendComparisonWorker(
    private val clock: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    private data class Run(
        val id: Long,
        val cancellation: BackendComparisonCancellation,
        val update: (BackendComparisonState) -> Unit,
    )
    private val lock = Any()
    private var active: Run? = null
    private var sequence = 0L
    @Volatile var state = BackendComparisonState()
        private set

    fun start(
        plan: BackendComparisonPlan = BackendComparisonPlan(),
        stillNeeded: () -> Boolean = { true },
        update: (BackendComparisonState) -> Unit = {},
        operation: (BackendComparisonCancellation, (String, BackendComparisonReport?) -> Unit) -> BackendComparisonReport,
    ): Long? {
        val run = synchronized(lock) {
            if (active != null || !stillNeeded()) return null
            Run(++sequence, BackendComparisonCancellation(stillNeeded, clock, plan.budgetMs), update).also {
                active = it
                state = BackendComparisonState(state.version + 1, it.id, running = true,
                    current = "准备内置 YOLO26n · 预热${plan.warmups}/测${plan.repetitions}")
            }
        }
        notify(run, state)
        // A stuck driver keeps this one guard, rather than allowing repeated starts to accumulate
        // native workers. Cooperative cancel cannot promise to interrupt a blocked JNI call.
        try {
            Thread({ perform(run, operation) }, "BackendComparison-${run.id}").apply { isDaemon = true }.start()
        } catch (failure: Throwable) {
            val failed = synchronized(lock) {
                active = null
                state = BackendComparisonState(state.version + 1, run.id, current = "启动失败",
                    failure = comparisonFailure(failure))
                state
            }
            notify(run, failed)
        }
        return run.id
    }

    fun cancel(runId: Long?, reason: String = "用户取消"): Boolean {
        val changed = synchronized(lock) {
            val run = active?.takeIf { it.id == runId } ?: return false
            run.cancellation.cancel(reason)
            state = state.copy(version = state.version + 1, cancelling = true,
                current = "正在取消，等待当前原生调用返回并释放资源")
            run to state
        }
        notify(changed.first, changed.second)
        return true
    }

    private fun perform(
        run: Run,
        operation: (BackendComparisonCancellation, (String, BackendComparisonReport?) -> Unit) -> BackendComparisonReport,
    ) {
        var report: BackendComparisonReport? = null
        var failure: String? = null
        var identified = false
        try {
            info("run=${run.id} BEGIN fixed YOLO26n; CPU→GPU+CPU→available NPU; camera/native work may contend")
            run.cancellation.checkpoint()
            report = operation(run.cancellation) { current, partial ->
                if (!identified && partial != null) {
                    identified = true
                    info("run=${run.id} resource=${partial.resource} inputSha256Float32LE=${partial.inputSha256} " +
                        "device=${partial.device} plan=${partial.plan}")
                }
                info("run=${run.id} stage=$current")
                val next = synchronized(lock) {
                    state = state.copy(version = state.version + 1,
                        current = if (run.cancellation.reason == null) current else "正在取消，等待释放资源",
                        cancelling = run.cancellation.reason != null, report = partial)
                    state
                }
                notify(run, next)
            }
        } catch (cancelled: CancellationException) {
            run.cancellation.cancel(cancelled.message ?: "已取消")
            failure = run.cancellation.reason
        } catch (error: Throwable) {
            failure = comparisonFailure(error)
        } finally {
            // Even report formatting must not keep the guard after native cleanup has returned.
            runCatching { report?.logLines()?.forEach { info("run=${run.id} $it") } }
            info("run=${run.id} END cancelled=${run.cancellation.reason} failure=$failure")
            val finished = synchronized(lock) {
                state = BackendComparisonState(state.version + 1, run.id,
                    current = when {
                        run.cancellation.reason != null -> "已取消"
                        failure != null -> "失败"
                        else -> "已完成"
                    }, report = report, failure = failure)
                active = null
                state
            }
            notify(run, finished)
        }
    }

    private fun notify(run: Run, next: BackendComparisonState) {
        // Publishing is diagnostic only; a UI failure cannot strand ownership of native handles.
        runCatching { run.update(next) }.onFailure { info("run=${run.id} report callback failed: ${comparisonFailure(it)}") }
    }

    private fun info(message: String) { runCatching { log(message) } }
}
