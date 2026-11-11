package com.example.minimalapk.pose

import android.os.Process
import java.io.File
import java.util.Locale

internal data class DeviceUsage(
    val cpuPercent: Float? = null,
    val gpuPercent: Float? = null,
    val npuPercent: Float? = null,
    val gpuSource: String? = null,
    val npuResults: List<NpuProbeResult> = emptyList(),
) {
    /** First real value wins; a stale value is kept only when no fresh scheme succeeded. */
    val npu: NpuProbeResult?
        get() = npuResults.firstOrNull { it.state == NpuProbeState.OK }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.STALE }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.PERMISSION_DENIED }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.PARSE_ERROR }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.UNSUPPORTED }
            ?: npuResults.firstOrNull()
}

/** Sampling seam so the monitor plugin can be tested without touching Android sysfs. */
internal interface DeviceUsageSource {
    fun sample(source: NpuSource, nowNanos: Long): DeviceUsage
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
) : DeviceUsageSource {
    private val npuReader = NpuProbeReader(files)
    private val lastGood = mutableMapOf<String, NpuProbeResult>()
    private var hasCpuBaseline = false
    private var lastCpuMillis = 0L
    private var lastWallMillis = 0L

    fun sample(): DeviceUsage = sample(NpuSource.AUTO, System.nanoTime())

    override fun sample(source: NpuSource, nowNanos: Long): DeviceUsage {
        val gpu = readGpu()
        val npuResults = npuReader.probe(source, nowNanos).map { staleOr(it, nowNanos) }
        // Auto may retain a stale earlier source before finding a fresh known source. Match the
        // diagnostic selection's fresh-first order, and never expose raw values as percentages.
        val primary = npuResults.firstOrNull { it.state == NpuProbeState.OK }
            ?: npuResults.firstOrNull { it.state == NpuProbeState.STALE }
        return DeviceUsage(
            cpuPercent = readCpuPercent(),
            gpuPercent = gpu?.value,
            npuPercent = primary?.takeIf { it.unit == "%" }?.value,
            gpuSource = gpu?.sourcePath,
            npuResults = npuResults,
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
        val cached = lastGood[key] ?: return result
        val age = nowNanos - cached.timestampNanos
        if (age in 0..STALE_NANOS) {
            return cached.copy(
                state = NpuProbeState.STALE,
                sourcePath = result.sourcePath ?: cached.sourcePath,
                rawExcerpt = result.rawExcerpt ?: cached.rawExcerpt,
                detail = "当前读取失败（${result.state.label}），显示 ${age / 1_000_000L} ms 前上次成功值",
            )
        }
        lastGood.remove(key)
        return result
    }

    private fun readCpuPercent(): Float? {
        val cpuMillis = Process.getElapsedCpuTime()
        val wallMillis = System.nanoTime() / 1_000_000L
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
        return cpuPercent(cpuDelta, wallDelta)
    }

    private fun readGpu(): UtilizationReading? =
        readNodes(GPU_NODES)
            ?: readDirectoryLoad(DEV_FREQ, GPU_KEYWORDS, PERCENT_LOAD_FILES)
            ?: readDirectoryLoad(GPU_CLASS, emptyList(), PERCENT_LOAD_FILES)

    private fun readNodes(nodes: List<UtilizationNode>): UtilizationReading? {
        for (node in nodes) {
            val reading = readNode(node)
            if (reading != null) return reading
        }
        return null
    }

    private fun readNode(node: UtilizationNode): UtilizationReading? =
        runCatching { File(node.path).takeIf(File::canRead)?.readText() }
            .getOrNull()
            ?.let(node.parse)
            ?.let { UtilizationReading(it, node.path) }

    private fun readDirectoryLoad(
        directoryPath: String,
        keywords: List<String>,
        fileNames: List<String>,
    ): UtilizationReading? {
        val directories = runCatching { File(directoryPath).listFiles() }.getOrNull() ?: return null
        for (directory in directories.take(64)) {
            if (keywords.isNotEmpty()) {
                val name = directory.name.lowercase(Locale.US)
                if (keywords.none(name::contains)) continue
            }
            for (fileName in fileNames) {
                val path = File(directory, fileName).path
                val percent = readNode(UtilizationNode(path, ::parsePercent))
                if (percent != null) return percent
            }
        }
        return null
    }

    private data class UtilizationReading(val value: Float, val sourcePath: String)

    private data class UtilizationNode(
        val path: String,
        val parse: (String) -> Float?,
    )

    private companion object {
        const val DEV_FREQ = "/sys/class/devfreq"
        const val GPU_CLASS = "/sys/class/gpu"
        const val STALE_NANOS = 5_000_000_000L

        val PERCENT_LOAD_FILES = listOf("gpu_busy_percentage", "load", "busy", "utilization")
        val GPU_KEYWORDS = listOf("gpu", "kgsl", "mali", "adreno")

        val GPU_NODES = listOf(
            UtilizationNode("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", ::parsePercent),
            UtilizationNode("/sys/class/kgsl/kgsl-3d0/gpubusy", ::parseBusyTotal),
            UtilizationNode("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load", ::parsePercent),
            UtilizationNode("/sys/kernel/gpu/gpu_busy", ::parsePercent),
            UtilizationNode("/sys/class/misc/mali0/device/utilisation", ::parsePercent),
        )
    }
}

internal fun cpuPercent(
    cpuDeltaMillis: Long,
    wallDeltaMillis: Long,
    cores: Int = Runtime.getRuntime().availableProcessors(),
): Float? {
    if (cpuDeltaMillis < 0L || wallDeltaMillis <= 0L || cores <= 0) return null
    return (cpuDeltaMillis.toFloat() / (wallDeltaMillis * cores) * 100f).coerceIn(0f, 100f)
}

private val NUMBER_REGEX = Regex("""-?\d+(?:\.\d+)?""")

internal fun parsePercent(raw: String): Float? {
    val value = NUMBER_REGEX.find(raw)?.value?.toFloatOrNull() ?: return null
    return value.takeIf { it in 0f..100f }
}

internal fun parseBusyTotal(raw: String): Float? {
    val parts = raw.trim().split(Regex("\\s+"))
    if (parts.size < 2) return null
    val busy = parts[0].toFloatOrNull() ?: return null
    val total = parts[1].toFloatOrNull() ?: return null
    if (busy < 0f || total <= 0f) return null
    return (busy / total * 100f).coerceIn(0f, 100f)
}
