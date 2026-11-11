package com.example.minimalapk.diagnostics

import android.content.Context
import com.example.minimalapk.log.AppLog

/** Shared across camera instances; a cancelling native call cannot be replaced by another worker. */
internal object BackendComparisonService {
    private val worker = BackendComparisonWorker(log = { AppLog.i("BackendComparison", it) })
    val state: BackendComparisonState get() = worker.state

    fun start(
        context: Context,
        stillNeeded: () -> Boolean,
        update: (BackendComparisonState) -> Unit,
    ): Long? {
        val application = context.applicationContext
        val plan = BackendComparisonPlan()
        return worker.start(plan, stillNeeded, update) { cancellation, progress ->
            BackendComparisonLiteRt(application).run(cancellation, plan, progress = progress)
        }
    }

    fun cancel(runId: Long?, reason: String = "用户取消"): Boolean = worker.cancel(runId, reason)
}
