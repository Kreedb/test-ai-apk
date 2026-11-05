package com.example.minimalapk.pose

import android.os.Process
import java.io.File
import java.util.Locale

data class DeviceUsage(
    val cpuPercent: Float? = null,
    val gpuPercent: Float? = null,
    val npuPercent: Float? = null,
)

/**
 * Best-effort device utilization sampler.
 *
 * Android does not expose system-wide CPU/GPU/NPU utilization to normal apps,
 * so CPU is the current process's share of the device's total CPU capacity
 * (readable without root) and GPU/NPU are probed through common vendor sysfs
 * nodes, staying `null` when the device does not expose them.
 */
class DeviceUsageSampler {
    private var hasCpuBaseline = false
    private var lastCpuMillis = 0L
    private var lastWallMillis = 0L

    fun sample(): DeviceUsage = DeviceUsage(
        cpuPercent = readCpuPercent(),
        gpuPercent = readGpuPercent(),
        npuPercent = readNpuPercent(),
    )

    fun reset() {
        hasCpuBaseline = false
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

    private fun readGpuPercent(): Float? =
        readNodes(GPU_NODES)
            ?: readDirectoryLoad(DEV_FREQ, GPU_KEYWORDS, PERCENT_LOAD_FILES)
            ?: readDirectoryLoad(GPU_CLASS, emptyList(), PERCENT_LOAD_FILES)

    private fun readNpuPercent(): Float? =
        readNodes(NPU_NODES)
            ?: readDirectoryLoad(DEV_FREQ, NPU_KEYWORDS, PERCENT_LOAD_FILES)
            ?: readDirectoryLoad(NPU_CLASS, emptyList(), PERCENT_LOAD_FILES)

    private fun readNodes(nodes: List<UtilizationNode>): Float? {
        for (node in nodes) {
            val percent = readNode(node)
            if (percent != null) return percent
        }
        return null
    }

    private fun readNode(node: UtilizationNode): Float? =
        runCatching { File(node.path).takeIf(File::canRead)?.readText() }
            .getOrNull()
            ?.let(node.parse)

    private fun readDirectoryLoad(
        directoryPath: String,
        keywords: List<String>,
        fileNames: List<String>,
    ): Float? {
        val directories = runCatching { File(directoryPath).listFiles() }.getOrNull() ?: return null
        for (directory in directories) {
            if (keywords.isNotEmpty()) {
                val name = directory.name.lowercase(Locale.US)
                if (keywords.none(name::contains)) continue
            }
            for (fileName in fileNames) {
                val percent = readNode(UtilizationNode(File(directory, fileName).path, ::parsePercent))
                if (percent != null) return percent
            }
        }
        return null
    }

    private data class UtilizationNode(
        val path: String,
        val parse: (String) -> Float?,
    )

    private companion object {
        const val DEV_FREQ = "/sys/class/devfreq"
        const val GPU_CLASS = "/sys/class/gpu"
        const val NPU_CLASS = "/sys/class/npu"

        val PERCENT_LOAD_FILES = listOf("gpu_busy_percentage", "load", "busy", "utilization")
        val GPU_KEYWORDS = listOf("gpu", "kgsl", "mali", "adreno")
        val NPU_KEYWORDS = listOf("npu", "nna")

        val GPU_NODES = listOf(
            UtilizationNode("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", ::parsePercent),
            UtilizationNode("/sys/class/kgsl/kgsl-3d0/gpubusy", ::parseBusyTotal),
            UtilizationNode("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load", ::parsePercent),
            UtilizationNode("/sys/kernel/gpu/gpu_busy", ::parsePercent),
            UtilizationNode("/sys/class/misc/mali0/device/utilisation", ::parsePercent),
        )

        val NPU_NODES = listOf(
            UtilizationNode("/sys/class/misc/npu/device/load", ::parsePercent),
            UtilizationNode("/sys/devices/platform/npu/load", ::parsePercent),
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
