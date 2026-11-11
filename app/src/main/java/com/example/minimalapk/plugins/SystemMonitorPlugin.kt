package com.example.minimalapk.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.example.minimalapk.BuildConfig
import com.example.minimalapk.diagnostics.BackendComparisonService
import com.example.minimalapk.diagnostics.BackendComparisonState
import com.example.minimalapk.pose.DeviceUsage
import com.example.minimalapk.pose.DeviceUsageSampler
import com.example.minimalapk.pose.DeviceUsageSource
import com.example.minimalapk.pose.NpuProbeResult
import com.example.minimalapk.pose.NpuProbeScheme
import com.example.minimalapk.pose.NpuProbeState
import com.example.minimalapk.pose.NpuSource
import com.example.minimalapk.pose.PoseTimings
import com.example.minimalapk.pose.ProbeEvidence
import com.example.minimalapk.pose.UsageMetrics
import com.example.minimalapk.pose.formatNumber

internal data class SystemMonitorSettings(
    val showFps: Boolean = true,
    val showTimings: Boolean = true,
    val showBackend: Boolean = true,
    val showCpu: Boolean = true,
    val showGpu: Boolean = true,
    val showNpu: Boolean = true,
    val refreshMs: Int = DEFAULT_REFRESH_MS,
    val fpsWindowSeconds: Int = DEFAULT_FPS_WINDOW_SECONDS,
    val npuSource: NpuSource = NpuSource.AUTO,
) {
    val usageMetrics: UsageMetrics get() = UsageMetrics(showCpu, showGpu, showNpu)

    fun toConfig(): Map<String, String> = mapOf(
        "show_fps" to showFps.toString(),
        "show_timings" to showTimings.toString(),
        "show_backend" to showBackend.toString(),
        "show_cpu" to showCpu.toString(),
        "show_gpu" to showGpu.toString(),
        "show_npu" to showNpu.toString(),
        "refresh_ms" to refreshMs.toString(),
        "fps_window_s" to fpsWindowSeconds.toString(),
        "npu_source" to npuSource.id,
    )

    companion object {
        const val MIN_REFRESH_MS = 250
        const val MAX_REFRESH_MS = 5000
        const val DEFAULT_REFRESH_MS = 250
        const val MIN_FPS_WINDOW_SECONDS = 1
        const val MAX_FPS_WINDOW_SECONDS = 10
        const val DEFAULT_FPS_WINDOW_SECONDS = 2

        fun from(settings: PluginSettings): SystemMonitorSettings = SystemMonitorSettings(
            showFps = settings.config["show_fps"]?.toBooleanStrictOrNull() ?: true,
            showTimings = settings.config["show_timings"]?.toBooleanStrictOrNull() ?: true,
            showBackend = settings.config["show_backend"]?.toBooleanStrictOrNull() ?: true,
            showCpu = settings.config["show_cpu"]?.toBooleanStrictOrNull() ?: true,
            showGpu = settings.config["show_gpu"]?.toBooleanStrictOrNull() ?: true,
            showNpu = settings.config["show_npu"]?.toBooleanStrictOrNull() ?: true,
            refreshMs = settings.config["refresh_ms"]?.toIntOrNull()
                ?.coerceIn(MIN_REFRESH_MS, MAX_REFRESH_MS) ?: DEFAULT_REFRESH_MS,
            fpsWindowSeconds = settings.config["fps_window_s"]?.toIntOrNull()
                ?.coerceIn(MIN_FPS_WINDOW_SECONDS, MAX_FPS_WINDOW_SECONDS) ?: DEFAULT_FPS_WINDOW_SECONDS,
            npuSource = NpuSource.fromId(settings.config["npu_source"]),
        )
    }
}

internal data class MonitorOutput(
    val timestampNanos: Long,
    val metricLines: List<String>,
    val npuResults: List<NpuProbeResult>,
    val npuSource: NpuSource,
    val sampledAtNanos: Long?,
    val gpuEvidence: List<ProbeEvidence> = emptyList(),
    val gpuDetail: String? = null,
    val samplingFailure: String? = null,
)

/** Pure, JVM-testable monitor state: camera FPS comes only from frame timestamps, refresh gating
 * comes from [settings], and usage sampling happens because a frame arrived, not from a second loop.
 */
internal class SystemMonitorEngine(
    private val usage: DeviceUsageSource,
    private val clock: () -> Long = System::nanoTime,
) {
    private val frameTimes = ArrayDeque<Long>()
    private var settings = SystemMonitorSettings()
    private var started = false
    private var needsSample = true
    private var lastSampleNanos = 0L

    @Synchronized
    fun start(next: SystemMonitorSettings) {
        settings = next
        started = true
        needsSample = true
        frameTimes.clear()
        usage.reset()
    }

    @Synchronized
    fun reconfigure(next: SystemMonitorSettings) {
        // Withdraw old-source/hidden samples, including reads still running on the probe worker.
        if (settings.npuSource != next.npuSource || settings.usageMetrics != next.usageMetrics) usage.reset()
        settings = next
        needsSample = true
    }

    @Synchronized
    fun stop() {
        started = false
        needsSample = true
        frameTimes.clear()
        usage.reset()
    }

    @Synchronized
    fun onFrame(
        frame: CameraFrame,
        timing: FrameProcessingTiming? = null,
        asyncResults: List<PluginTimingContribution> = emptyList(),
    ): MonitorOutput? {
        if (!started) return null
        val now = clock()
        if (frameTimes.isEmpty() || frame.timestampNanos > frameTimes.last()) {
            frameTimes.addLast(frame.timestampNanos)
        }
        // The window ends at the last captured timestamp, not at the post-inference clock reading:
        // frame timestamps are taken before pose analysis, so mixing them with `now` would bias FPS low.
        val fps = cameraFrameRate(
            frameTimes, frameTimes.last(), settings.fpsWindowSeconds.toLong() * 1_000_000_000L,
        )
        val intervalNanos = settings.refreshMs.toLong() * 1_000_000L
        if (!needsSample && now - lastSampleNanos < intervalNanos) return null
        needsSample = false
        lastSampleNanos = now

        val sample = if (settings.usageMetrics.any) usage.sample(settings.npuSource, now, settings.usageMetrics)
            else DeviceUsage()
        val primaryTimings = frame.poses.firstOrNull()?.timings
        return MonitorOutput(
            timestampNanos = frame.timestampNanos,
            metricLines = monitorMetricLines(
                settings, fps, primaryTimings, timing, frame.executionProvider, sample, asyncResults, now,
            ),
            npuResults = sample.npuResults,
            npuSource = settings.npuSource,
            sampledAtNanos = sample.sampledAtNanos,
            gpuEvidence = sample.gpuEvidence,
            gpuDetail = sample.gpuDetail,
            samplingFailure = sample.samplingFailure,
        )
    }
}

internal fun monitorMetricLines(
    settings: SystemMonitorSettings,
    fps: Float,
    primaryTimings: PoseTimings?,
    timing: FrameProcessingTiming?,
    executionProvider: String?,
    usage: DeviceUsage,
    asyncResults: List<PluginTimingContribution> = emptyList(),
    nowNanos: Long = 0,
): List<String> = buildList {
    if (settings.showFps) {
        add("帧率 ${if (fps.isFinite() && fps > 0f) "${formatNumber(fps)} FPS" else "—"}")
    }
    if (settings.showTimings) {
        // PoseAnalyzer's inference interval includes RGB conversion, tensor write and readback.
        // It is one component, never the total of every synchronous/asynchronous plugin.
        add("主姿态 预处理 ${timingsText(primaryTimings) { it.preprocessMs }} · " +
            "推理链路(含读回) ${timingsText(primaryTimings) { it.inferenceMs }} · " +
            "后处理 ${timingsText(primaryTimings) { it.postprocessMs }}")
        add("同步帧处理 ${durationText(timing?.totalMs)}（含分发 ${durationText(timing?.dispatchMs)}；不含异步/显示）")
    }
    if (settings.showBackend) {
        val mixed = executionProvider?.let { "GPU" in it || "NPU" in it } == true
        add("主姿态后端 ${executionProvider ?: "—"}${if (mixed) " · 实际分区未知（可含 CPU）" else ""}")
    }
    val age = usage.sampledAtNanos?.let {
        " · 采样 ${formatNumber(((nowNanos - it).coerceAtLeast(0) / 1e9).toFloat())}s前"
    }.orEmpty()
    if (settings.showCpu) add("CPU(应用/总算力) ${percentText(usage.cpuPercent)}$age")
    if (settings.showGpu) {
        val source = usage.gpuSource?.let { " · ${shortProbePath(it)}" } ?: ""
        val state = if (usage.gpuPercent == null) usage.gpuState?.label?.let { " $it" }.orEmpty() else ""
        add("GPU ${percentText(usage.gpuPercent)}$state$source$age")
    }
    if (settings.showNpu) add(npuMetricLine(usage) + age)
    if (settings.showTimings && asyncResults.isNotEmpty()) {
        val latest = asyncResults.sortedByDescending { it.completedNanos }
        // The host's eight message slots are shared with usage metrics. Explicitly show omitted
        // count rather than imply that these overlapping calls are an all-plugin frame total.
        add(("异步最近完成（不相加）：" + latest.take(2).joinToString("；") {
            val age = if (it.completedNanos > 0 && nowNanos >= it.completedNanos) {
                "${formatNumber(((nowNanos - it.completedNanos) / 1e9).toFloat())}s前"
            } else "时间未知"
            "${it.name.take(18)} ${durationText(it.execution.elapsedMs)} · $age"
        } + if (latest.size > 2) "；另${latest.size - 2}项" else "").take(PluginHost.MAX_MESSAGE_CHARS))
    }
}

internal fun npuMetricLine(usage: DeviceUsage): String {
    usage.samplingFailure?.let { return "NPU 采样失败：${it.take(100)}" }
    val result = usage.npu ?: return "NPU —"
    val stale = result.state == NpuProbeState.STALE
    if (result.state != NpuProbeState.OK && !stale) {
        val reason = result.detail?.take(40) ?: result.sourcePath ?: ""
        return "NPU ${result.state.label}${if (reason.isNotEmpty()) "：$reason" else ""}"
    }
    val value = result.value ?: return "NPU ${result.scope}（${result.unit}）：${result.state.label} · ${shortProbePath(result.sourcePath)}"
    val suffix = if (stale) "（stale）" else ""
    if (result.unit != "%") {
        return "NPU ${result.scope} ${formatNumber(value)} ${result.unit}$suffix（非百分比）"
    }
    return when (result.scheme) {
        NpuProbeScheme.SAMSUNG_RQ_LOAD ->
            "NPU 请求队列忙碌率 ${formatNumber(value)}%$suffix · ${shortProbePath(result.sourcePath)}"
        NpuProbeScheme.ROCKCHIP ->
            "NPU ${if (result.perCore.size > 1) "核均值 " else ""}${formatNumber(value)}%$suffix · ${shortProbePath(result.sourcePath)}"
        else -> "NPU ${formatNumber(value)}%$suffix · ${shortProbePath(result.sourcePath)}"
    }
}

internal data class MonitorDeviceInfo(
    val deviceModel: String,
    val soc: String,
    val android: String,
    val appVersion: String,
)

private fun timingsText(timings: PoseTimings?, select: (PoseTimings) -> Float): String =
    durationText(timings?.let { select(it).toDouble() })

private fun durationText(value: Double?): String =
    value?.takeIf { it.isFinite() && it >= 0 && it <= Float.MAX_VALUE }
        ?.let { "${formatNumber(it.toFloat())} ms" } ?: "—"

private fun percentText(value: Float?): String =
    value?.let { "${formatNumber(it)}%" } ?: "—"

internal fun shortProbePath(path: String?): String =
    path?.removePrefix("/sys/")?.takeIf { it.isNotEmpty() } ?: "—"

private fun defaultMonitorDeviceInfo(): MonitorDeviceInfo {
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
    } else {
        Build.HARDWARE
    }
    return MonitorDeviceInfo(
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        soc = soc.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: "未知",
        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        appVersion = BuildConfig.VERSION_NAME,
    )
}

internal class SystemMonitorPlugin(
    private val usage: DeviceUsageSource = MonitorUsageSampler(DeviceUsageSampler()),
    private val clock: () -> Long = System::nanoTime,
    private val copyDiagnosis: ((String) -> Unit)? = null,
    private val deviceInfo: () -> MonitorDeviceInfo = ::defaultMonitorDeviceInfo,
) : CameraPlugin {
    constructor(context: Context, usage: DeviceUsageSource = MonitorUsageSampler(DeviceUsageSampler())) : this(usage) {
        application = context.applicationContext
    }

    private var application: Context? = null
    override val descriptor = PluginDescriptor(
        PluginIds.SYSTEM_MONITOR, "系统监控",
        setOf(PluginCapability.CAMERA_FRAMES, PluginCapability.MONITOR, PluginCapability.TEXT, PluginCapability.ACTIONS),
        actions = setOf(COMPARE, CANCEL_COMPARISON, HIDE_COMPARISON, COPY_DIAGNOSIS),
    )
    private val engine = SystemMonitorEngine(usage, clock)
    private val lock = Any()
    private var owner: PluginToken? = null
    private var lastOutput: MonitorOutput? = null
    private var comparison = BackendComparisonState()
    private var comparisonVisible = false
    private var runId: Long? = null
    private var lastPublishedNanos = Long.MIN_VALUE
    private var copyNotice: String? = null

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started -> synchronized(lock) {
                if (!context.isCurrent()) return
                owner = context.token
                lastOutput = null
                copyNotice = null
                lastPublishedNanos = Long.MIN_VALUE
                engine.start(SystemMonitorSettings.from(context.settings))
                refreshComparison()
                publish(context)
            }
            PluginEvent.ConfigurationChanged -> synchronized(lock) {
                if (!context.isCurrent()) return
                BackendComparisonService.cancel(runId, "监控配置已改变")
                owner = context.token
                lastOutput = null
                copyNotice = null
                engine.reconfigure(SystemMonitorSettings.from(context.settings))
                refreshComparison()
                publish(context)
            }
            is PluginEvent.Stopped -> synchronized(lock) {
                if (owner == context.token) stop()
            }
            is PluginEvent.FrameCompleted -> synchronized(lock) {
                if (owner != context.token || !context.isCurrent()) return
                // Frame is also delivered by the host; only completion samples usage/FPS, once.
                lastOutput = engine.onFrame(event.frame, event.timing, event.asyncResults) ?: return
                refreshComparison()
                publish(context)
            }
            is PluginEvent.Action -> synchronized(lock) {
                if (owner != context.token || !context.isCurrent()) return
                when (event.id) {
                    COMPARE -> {
                        val app = application ?: return
                        comparisonVisible = true
                        runId = BackendComparisonService.start(app, context::isCurrent) { next ->
                            synchronized(lock) {
                                if (owner == context.token && context.isCurrent() && next.version >= comparison.version) {
                                    comparison = next
                                    publish(context)
                                }
                            }
                        } ?: runId
                    }
                    CANCEL_COMPARISON -> BackendComparisonService.cancel(comparison.runId)
                    HIDE_COMPARISON -> comparisonVisible = false
                    COPY_DIAGNOSIS -> {
                        val output = lastOutput ?: return
                        if (output.npuResults.isEmpty() && output.samplingFailure == null) return
                        val text = monitorDiagnosisReport(deviceInfo(), output, SystemMonitorSettings.from(context.settings))
                        copyNotice = try {
                            val copy = copyDiagnosis
                            if (copy != null) copy(text) else {
                                val app = application ?: return
                                val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("系统监控 NPU 诊断", text))
                            }
                            "已复制完整诊断（含来源原文及读取限制），请分别记录空闲/推理场景"
                        } catch (error: Exception) {
                            "复制失败：${error.message ?: error.javaClass.simpleName}"
                        }
                    }
                }
                refreshComparison()
                publish(context)
            }
            else -> Unit
        }
    }

    private fun refreshComparison() {
        if (application != null) comparison = BackendComparisonService.state
    }

    /** Caller holds lock, so delayed frame samples cannot erase a newly completed report/action. */
    private fun publish(context: PluginContext) {
        if (owner != context.token || !context.isCurrent()) return
        val report = if (comparisonVisible) comparison.compactLines() else emptyList()
        val output = lastOutput
        val metrics = output?.metricLines.orEmpty()
        val messages = if (report.isNotEmpty()) {
            // Reserve backend rows while keeping frame/async time meanings visible under load.
            val timingLines = metrics.filter {
                it.startsWith("帧率") || it.startsWith("主姿态 ") ||
                    it.startsWith("同步帧处理") || it.startsWith("异步最近完成")
            }
            (timingLines + metrics.filterNot { it in timingLines })
                .take(PluginHost.MAX_MESSAGES - report.size).map { PluginMessage(it) } +
                report.map { PluginMessage(it) }
        } else metrics.map { PluginMessage(it) }
        // Keep configured metrics in the top-right panel even during ALL. The copy action carries
        // the full bounded report directly; STATUS previews must never be its backing data.
        val notice = copyNotice ?: output?.takeIf { it.npuSource == NpuSource.ALL }?.let {
            if (it.npuResults.isEmpty()) "NPU 测试全部：等待采样；需开启 NPU 指标"
            else "NPU 测试全部：${it.npuResults.size} 项结果；点击“复制完整 NPU 诊断”获取每条来源原文"
        }
        val displayedMessages = if (notice == null || messages.size >= PluginHost.MAX_MESSAGES) messages else
            messages + PluginMessage(notice, MessageKind.STATUS)
        // Async report completion uses a monotonic publication time, not an earlier frame capture
        // timestamp (the host otherwise correctly drops it as out-of-order).
        lastPublishedNanos = maxOf(lastPublishedNanos, clock())
        context.publish(PluginOutput(lastPublishedNanos, displayedMessages.take(PluginHost.MAX_MESSAGES),
            actions = buildList {
                if (application != null) {
                    add(PluginAction(COMPARE, "比较 CPU / GPU / NPU", enabled = !comparison.running))
                    if (comparison.running) add(PluginAction(CANCEL_COMPARISON, "取消比较（等待释放）", !comparison.cancelling))
                    if (comparisonVisible && !comparison.running) add(PluginAction(HIDE_COMPARISON, "收起比较报告"))
                }
                if (application != null || copyDiagnosis != null) {
                    val label = when {
                        copyNotice?.startsWith("已复制") == true -> "已复制 · 再次复制完整 NPU 诊断"
                        copyNotice?.startsWith("复制失败") == true -> "复制失败 · 重试完整 NPU 诊断"
                        else -> "复制完整 NPU 诊断"
                    }
                    add(PluginAction(COPY_DIAGNOSIS, label, enabled = output?.npuResults?.isNotEmpty() == true || output?.samplingFailure != null))
                }
            }))
    }

    private fun stop() = synchronized(lock) {
        owner = null
        lastOutput = null
        copyNotice = null
        comparisonVisible = false
        BackendComparisonService.cancel(runId, "监控已停止/相机离开前台")
        engine.stop()
    }

    override fun close() {
        stop()
        (usage as? AutoCloseable)?.close()
    }

    companion object {
        const val COMPARE = "backend_compare"
        const val CANCEL_COMPARISON = "backend_compare_cancel"
        const val HIDE_COMPARISON = "backend_compare_hide"
        const val COPY_DIAGNOSIS = "npu_diagnosis_copy"
    }
}

/** Camera FPS only: sample timestamps come from the frame stream, never from inference timing. */
internal fun cameraFrameRate(
    timestamps: ArrayDeque<Long>,
    nowNanos: Long,
    windowNanos: Long,
): Float {
    while (timestamps.size > 2 && nowNanos - timestamps.first() > windowNanos) timestamps.removeFirst()
    if (timestamps.size < 2) return 0f
    val spanNanos = (nowNanos - timestamps.first()).toFloat()
    if (spanNanos <= 0f) return 0f
    return (timestamps.size - 1) * 1_000_000_000f / spanNanos
}
