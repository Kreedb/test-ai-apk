package com.example.minimalapk.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.example.minimalapk.BuildConfig
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.litert.lm.LocalTextModels
import com.example.minimalapk.models.AssetCatalog
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.PoseBackendPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.tensorflow.lite.TensorFlowLite
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide guard survives recomposition/tab changes until native cleanup really finishes. */
internal object AppSelfCheck {
    private const val TAG = "SelfCheck"
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(SelfCheckState())
    val state = mutableState.asStateFlow()
    private var active: Run? = null

    private class Run(val id: String) {
        val cancelled = AtomicBoolean(false)
        @Volatile var reason = "用户取消"
        fun checkpoint() { if (cancelled.get()) throw CancellationException(reason) }
    }

    /** Returns the owning run token, or null if another run still owns native resources. */
    @Synchronized
    fun start(context: Context): String? {
        if (active != null) return null
        val run = Run(UUID.randomUUID().toString().take(8))
        val application = context.applicationContext
        active = run
        mutableState.value = SelfCheckState(running = true, runId = run.id, current = "正在枚举全部模型")
        worker.launch { perform(application, run) }
        return run.id
    }

    /** Cooperative cancellation: never close an Interpreter from the main thread during invoke. */
    @Synchronized
    fun cancel(runId: String? = null, reason: String = "用户取消") {
        val run = active ?: return
        if (runId != null && run.id != runId) return
        if (run.cancelled.get()) return
        run.reason = reason
        run.cancelled.set(true)
        mutableState.value = mutableState.value.copy(cancelling = true,
            current = "正在取消，等待当前原生调用结束并释放资源")
    }

    private fun perform(context: Context, run: Run) {
        val started = System.nanoTime()
        val results = mutableListOf<CheckResult>()
        var targets = emptyList<CheckTarget>()
        fun info(message: String) = AppLog.i(TAG, "run=${run.id} $message")
        fun publish(current: String) {
            mutableState.value = SelfCheckState(running = true, cancelling = run.cancelled.get(),
                runId = run.id, current = current, total = targets.size, results = results.toList())
        }
        fun record(result: CheckResult, error: Throwable? = null) {
            results += result
            val text = "run=${run.id} ${result.logLine()}"
            when (result.status) {
                CheckStatus.PASS -> AppLog.i(TAG, text)
                CheckStatus.SKIP -> AppLog.w(TAG, text, error)
                CheckStatus.FAIL -> AppLog.e(TAG, text, error)
            }
            publish("已检查 ${results.size}/${targets.size} · ${result.id} ${result.status}")
        }
        try {
            info("BEGIN；PASS 仅表示已执行的 CPU 合成输入与输出校验；缺失/不支持/未执行=SKIP；" +
                "串行隔离实例；无自动下载；不调用训练/保存/恢复；取消在原生调用返回后生效")
            targets = inventory(context)
            try { logEnvironment(context, ::info) } catch (error: Exception) {
                AppLog.e(TAG, "run=${run.id} 环境信息读取失败；继续逐模型检查", error)
            }
            info("inventory=${targets.size} byKind=${targets.groupingBy { it.kind }.eachCount()}")
            publish("已枚举 ${targets.size} 项")
            targets.forEachIndexed { index, target ->
                if (run.cancelled.get()) {
                    record(CheckResult(target.id, CheckStatus.SKIP, "cancelled", "未开始：${run.reason}"))
                    return@forEachIndexed
                }
                val check = ModelSelfCheck(context, target, run::checkpoint,
                    log = { info("id=${target.id} $it") },
                    onStage = { stage -> publish("${index + 1}/${targets.size} · ${target.name} · $stage") })
                val result = try {
                    check.run()
                } catch (cancelled: CancellationException) {
                    CheckResult(target.id, CheckStatus.SKIP, check.stage,
                        "已取消：${run.reason}；未完成的输出校验不计 PASS；${check.coverageDetail()}", check.timings.toMap(),
                        check.inferenceRan, check.validatedOutputs)
                } catch (skipped: CheckSkipped) {
                    CheckResult(target.id, CheckStatus.SKIP, check.stage, "${skipped.message}；${check.coverageDetail()}",
                        check.timings.toMap(), check.inferenceRan, check.validatedOutputs)
                } catch (error: Throwable) {
                    // A broken/unsupported target must not suppress all subsequent model results.
                    // OOM ends this run after cleanup rather than retrying many native allocations.
                    val unsupported = unsupportedRuntimeReason(error)
                    val failed = CheckResult(target.id,
                        if (unsupported != null) CheckStatus.SKIP else CheckStatus.FAIL,
                        check.stage, (unsupported ?: "${error.javaClass.name}: ${error.message}; " +
                            "检查同一 run/id 的 hash、配置和 I/O；重新导入损坏文件或按模型说明修正配置") +
                            "; ${check.coverageDetail()}",
                        check.timings.toMap(), check.inferenceRan, check.validatedOutputs)
                    record(failed, error)
                    if (error is OutOfMemoryError) {
                        run.reason = "内存不足，剩余项未执行；关闭其他应用或使用更小的模型/输入"
                        run.cancelled.set(true)
                    }
                    return@forEachIndexed
                }
                record(result)
            }
        } catch (error: Throwable) {
            val failure = CheckResult("self-check", CheckStatus.FAIL, "inventory/runtime",
                "${error.javaClass.name}: ${error.message}；自检未完成，不能认为未列出的模型已通过")
            record(failure, error)
            targets.filter { target -> results.none { it.id == target.id } }.forEach { target ->
                record(CheckResult(target.id, CheckStatus.SKIP, "aborted", "自检中断，未执行"))
            }
        } finally {
            val finalState = SelfCheckState(runId = run.id, total = targets.size, results = results.toList(),
                finished = true, cancelled = run.cancelled.get(),
                current = if (run.cancelled.get()) "已取消：${run.reason}" else "自检完成")
            try {
                info("END elapsedMs=${(System.nanoTime() - started) / 1_000_000} " +
                    "cancelled=${finalState.cancelled} total=${targets.size} ${finalState.summary}; " +
                    "GPU/NPU、真实相机/麦克风和任务准确率未验证")
                // Keep one compact coverage matrix near the tail of AppLog's bounded ring buffer,
                // even if many per-stage records have displaced the beginning of a large run.
                try { logEnvironment(context, ::info) } catch (error: Exception) {
                    AppLog.w(TAG, "run=${run.id} 结束时环境信息读取失败", error)
                }
                info("RESULTS\n" + results.joinToString("\n") { it.logLine() })
            } finally {
                synchronized(this) {
                    mutableState.value = finalState
                    active = null
                }
            }
        }
    }

    private fun inventory(context: Context): List<CheckTarget> {
        val failures = mutableListOf<CheckTarget>()
        fun failure(section: String, error: Throwable) {
            failures += CheckTarget("inventory:$section", section, CheckKind.INVENTORY, inventoryError = error)
        }
        val poses = try {
            val loaded = ModelStore.load(context)
            // ModelStore.load omits missing imports. Include their saved records so deletion is
            // reported as MISSING rather than silently reducing the inventory.
            val raw = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getString("pose_models", null).orEmpty()
            val saved = ModelStore.decode(raw)
            if (raw.isNotBlank() && saved.size != raw.lineSequence().count { it.isNotBlank() }) {
                failure("pose-settings", IllegalArgumentException("部分姿态配置无法解析；请检查自定义模型列表"))
            }
            loaded + saved.filter { entry -> loaded.none { it.id == entry.id } }
        } catch (error: Exception) {
            failure("pose-settings", error)
            ModelStore.builtIn
        }
        val sets = try { AssetCatalog.load(context) } catch (error: Exception) {
            failure("asset-catalog", error)
            emptyList()
        }
        val localTextSets = try { LocalTextModels.assetSets(context) } catch (error: Exception) {
            failure("local-text-catalog", error)
            emptyList()
        }
        return selfCheckInventory(context.filesDir, poses, sets + localTextSets) + failures
    }

    private fun logEnvironment(context: Context, log: (String) -> Unit) {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
        val java = Runtime.getRuntime()
        log("app=${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} " +
            "build=${BuildConfig.BUILD_TYPE} device=${Build.MANUFACTURER}/${Build.MODEL} " +
            "hardware=${Build.HARDWARE} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
            "abis=${Build.SUPPORTED_ABIS.joinToString()} supportedBackendPreference=${PoseBackendPreference.load(context)} " +
            "selfCheckBackend=CPU interpreterThreads=2 genericInterpreterXNNPACK=false " +
            "poseBackend=LiteRT-CompiledModel-CPU examplePipelineOptions=existing-CPU-defaults")
        log("memoryAvailable=${memory.availMem} memoryTotal=${memory.totalMem} lowMemory=${memory.lowMemory} " +
            "javaMaxHeap=${java.maxMemory()} javaFreeHeap=${java.freeMemory()} " +
            "processors=${java.availableProcessors()} filesFreeBytes=${context.filesDir.usableSpace}")
        try {
            log("runtime=LiteRT/Interpreter version=${TensorFlowLite.runtimeVersion()} schema=${TensorFlowLite.schemaVersion()}")
        } catch (error: LinkageError) {
            AppLog.e(TAG, "runtime 版本探测失败；各模型仍独立报告加载结果", error)
        } catch (error: Exception) {
            AppLog.e(TAG, "runtime 版本探测失败；各模型仍独立报告加载结果", error)
        }
    }
}
