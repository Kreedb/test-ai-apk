package com.example.minimalapk.pose

import android.os.Process
import java.util.Locale

internal data class DeviceUsage(
    val cpuPercent: Float? = null,
    val gpuPercent: Float? = null,
    val npuPercent: Float? = null,
    val gpuSource: String? = null,
    val npuResults: List<NpuProbeResult> = emptyList(),
    val sampledAtNanos: Long? = null,
    val gpuState: NpuProbeState? = null,
    val gpuDetail: String? = null,
    val gpuEvidence: List<ProbeEvidence> = emptyList(),
    val samplingFailure: String? = null,
) {
    /** First real value wins; a stale value is kept only when no fresh scheme succeeded. */
    val npu: NpuProbeResult?
        get() = npuResults.firstOrNull { it.state == NpuProbeState.OK && it.value != null && it.unit == "%" }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.STALE && it.value != null && it.unit == "%" }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.OK }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.STALE }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.PERMISSION_DENIED }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.PARSE_ERROR }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.TIMEOUT }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.TRUNCATED }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.UNSUPPORTED }
            ?: npuResults.firstOrNull()
}

internal data class UsageMetrics(val cpu: Boolean = true, val gpu: Boolean = true, val npu: Boolean = true) {
    val any: Boolean get() = cpu || gpu || npu
}

/** Sampling seam so the monitor plugin can be tested without touching Android sysfs. */
internal interface DeviceUsageSource {
    fun sample(
        source: NpuSource,
        nowNanos: Long,
        metrics: UsageMetrics = UsageMetrics(),
        stillNeeded: () -> Boolean = { true },
    ): DeviceUsage
    fun reset()
}

/**
 * Best-effort device utilization sampler.
 *
 * Android does not expose system-wide CPU/GPU/NPU utilization to normal apps.
 * CPU is this process's share of the device's total CPU capacity (readable without
 * root). NPU percentages require a documented driver contract; generic load attributes
 * remain raw diagnostics with their path and unsupported-unit reason instead of a fake 0%.
 */
internal class DeviceUsageSampler internal constructor(
    private val files: ProbeFileSystem = RealProbeFileSystem,
    private val cpuMillis: () -> Long = Process::getElapsedCpuTime,
    private val cores: () -> Int = { Runtime.getRuntime().availableProcessors() },
) : DeviceUsageSource {
    private val npuReader = NpuProbeReader(files)
    private val lastGood = mutableMapOf<String, NpuProbeResult>()
    private var hasCpuBaseline = false
    private var lastCpuMillis = 0L
    private var lastWallMillis = 0L

    override fun sample(source: NpuSource, nowNanos: Long, metrics: UsageMetrics, stillNeeded: () -> Boolean): DeviceUsage {
        if (!stillNeeded()) return DeviceUsage()
        val cpu = if (metrics.cpu) readCpuPercent(nowNanos) else { hasCpuBaseline = false; null }
        val gpuFiles = ProbeRunFileSystem(files, stillNeeded = stillNeeded)
        val gpu = if (metrics.gpu) readGpu(gpuFiles) else null
        val npuResults = if (metrics.npu) npuReader.probe(source, nowNanos, stillNeeded).map { staleOr(it, nowNanos) }
            else { lastGood.clear(); emptyList() }
        // Auto may retain a stale earlier source before finding a fresh known source. Match the
        // diagnostic selection's fresh-first order, and never expose raw values as percentages.
        val percentages = npuResults.filter { it.value != null && it.unit == "%" }
        val primary = percentages.firstOrNull { it.state == NpuProbeState.OK }
            ?: percentages.firstOrNull { it.state == NpuProbeState.STALE }
        return DeviceUsage(
            cpuPercent = cpu,
            gpuPercent = gpu?.value,
            npuPercent = primary?.takeIf { it.unit == "%" }?.value,
            gpuSource = gpu?.sourcePath,
            npuResults = npuResults,
            sampledAtNanos = nowNanos,
            gpuState = gpu?.state,
            gpuDetail = gpu?.detail,
            gpuEvidence = gpuFiles.evidence.toList(),
        )
    }

    override fun reset() {
        hasCpuBaseline = false
        lastGood.clear()
    }

    private fun staleOr(result: NpuProbeResult, nowNanos: Long): NpuProbeResult {
        val key = "${result.scheme.id}|${result.sourcePath}"
        if (result.state == NpuProbeState.OK) {
            lastGood[key] = result
            return result
        }
        // A driver-reported stale value already has its own current diagnostic (e.g. a disabled
        // Samsung scheduler); do not replace it with a previous success and hide that reason.
        if (result.state == NpuProbeState.STALE) return result
        val cached = lastGood[key] ?: return result
        val age = nowNanos - cached.timestampNanos
        if (age in 0..STALE_NANOS) {
            return cached.copy(
                state = NpuProbeState.STALE,
                sourcePath = result.sourcePath ?: cached.sourcePath,
                rawExcerpt = result.rawExcerpt ?: cached.rawExcerpt,
                detail = "当前读取失败（${result.state.label}${result.detail?.let { ": $it" }.orEmpty()}），显示 ${age / 1_000_000L} ms 前上次成功值",
                evidence = result.evidence,
            )
        }
        lastGood.remove(key)
        return result
    }

    private fun readCpuPercent(nowNanos: Long): Float? {
        val cpuMillis = cpuMillis()
        val wallMillis = nowNanos / 1_000_000L
        if (!hasCpuBaseline) {
            hasCpuBaseline = true
            lastCpuMillis = cpuMillis
            lastWallMillis = wallMillis
            return null
        }
        val cpuDelta = cpuMillis - lastCpuMillis
        val wallDelta = wallMillis - lastWallMillis
        lastCpuMillis = cpuMillis
        lastWallMillis = wallMillis
        return cpuPercent(cpuDelta, wallDelta, cores())
    }

    private fun readGpu(files: ProbeRunFileSystem): UtilizationReading? {
        val known = GPU_NODES.map { readNode(files, it) }
        known.firstOrNull { it.value != null }?.let { return it }
        // A generic "load"/"busy" name cannot establish percentage units for an unknown GPU
        // driver any more than for an NPU. Retain the source as raw instead of guessing.
        val rawPaths = GPU_RAW_NODES + listOf(DEV_FREQ, GPU_CLASS).flatMap { root ->
            files.listDirectories(root).filter { path ->
                root == GPU_CLASS || GPU_KEYWORDS.any(path.substringAfterLast('/').lowercase(Locale.US)::contains)
            }.flatMap { path -> RAW_LOAD_FILES.map { "$path/$it" } }
        }
        val unknown = rawPaths.map { readNode(files, UtilizationNode(it, null)) }
        return unknown.firstOrNull { it.state == NpuProbeState.UNSUPPORTED }
            ?: (known + unknown).firstOrNull { it.state != NpuProbeState.MISSING }
            ?: known.firstOrNull()
    }

    private fun readNode(files: ProbeRunFileSystem, node: UtilizationNode): UtilizationReading {
        val read = files.read(node.path)
        val value = (read as? ProbeRead.Content)?.text?.let { node.parse?.invoke(it) }
        val state = when (read) {
            is ProbeRead.Content -> when {
                value != null -> NpuProbeState.OK
                read.text.isBlank() || node.parse != null -> NpuProbeState.PARSE_ERROR
                else -> NpuProbeState.UNSUPPORTED
            }
            ProbeRead.Missing -> NpuProbeState.MISSING
            ProbeRead.PermissionDenied -> NpuProbeState.PERMISSION_DENIED
            is ProbeRead.Failure -> read.state
        }
        return UtilizationReading(value, node.path, state, when (read) {
            is ProbeRead.Failure -> read.message
            is ProbeRead.Content -> if (node.parse == null) "raw（单位/作用域未确认）" else "KGSL 驱动采样窗忙碌时间占比（%）"
            else -> null
        })
    }

    private data class UtilizationReading(
        val value: Float?, val sourcePath: String, val state: NpuProbeState, val detail: String?,
    )

    private data class UtilizationNode(
        val path: String,
        val parse: ((String) -> Float?)?,
    )

    private companion object {
        const val DEV_FREQ = "/sys/class/devfreq"
        const val GPU_CLASS = "/sys/class/gpu"
        const val STALE_NANOS = 5_000_000_000L

        val RAW_LOAD_FILES = listOf("gpu_busy_percentage", "load", "busy", "utilization")
        val GPU_KEYWORDS = listOf("gpu", "kgsl", "mali", "adreno")

        val GPU_NODES = listOf(
            // LineageOS/android_kernel_qcom_sm8250@e3f6acd662618dabb3a7a953e48743019badc373
            // drivers/gpu/msm/kgsl_pwrctrl.c: gpubusy_show/gpu_busy_percentage_show expose the
            // SAME completed driver interval (busy_old/total_old), not cumulative counters.
            UtilizationNode("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", ::parsePercent),
            UtilizationNode("/sys/class/kgsl/kgsl-3d0/gpubusy", ::parseBusyTotal),
        )
        val GPU_RAW_NODES = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/mali0/device/utilisation",
        )
    }
}

internal fun cpuPercent(
    cpuDeltaMillis: Long,
    wallDeltaMillis: Long,
    cores: Int = Runtime.getRuntime().availableProcessors(),
): Float? {
    if (cpuDeltaMillis < 0L || wallDeltaMillis <= 0L || cores <= 0) return null
    return (cpuDeltaMillis.toDouble() / (wallDeltaMillis.toDouble() * cores) * 100.0).coerceIn(0.0, 100.0).toFloat()
}

private val PERCENT_REGEX = Regex("""(\d+(?:\.\d+)?)\s*%?""")

internal fun parsePercent(raw: String): Float? {
    val value = PERCENT_REGEX.matchEntire(raw.trim())?.groupValues?.get(1)?.toFloatOrNull() ?: return null
    return value.takeIf { it in 0f..100f }
}

internal fun parseBusyTotal(raw: String): Float? {
    val parts = raw.trim().split(Regex("\\s+"))
    if (parts.size != 2) return null
    val busy = parts[0].toDoubleOrNull() ?: return null
    val total = parts[1].toDoubleOrNull() ?: return null
    if (!busy.isFinite() || !total.isFinite() || busy < 0 || total <= 0 || busy > total) return null
    return (busy / total * 100).toFloat()
}
