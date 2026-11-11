package com.example.minimalapk.pose

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/** One selectable NPU source. AUTO only chooses among percentage schemes; ALL is a diagnosis run. */
internal enum class NpuSource(val id: String, val label: String) {
    AUTO("auto", "自动"),
    VENDOR_PERCENT("vendor_percent", "厂商负载节点"),
    DEVFREQ("devfreq", "devfreq 设备"),
    ROCKCHIP("rockchip", "Rockchip RKNPU"),
    SAMSUNG_RQ_LOAD("samsung_rq_load", "Samsung 请求队列"),
    QUALCOMM_DIAGNOSTIC("qualcomm_diag", "Qualcomm DSP 诊断"),
    ALL("all", "测试全部");

    val scheme: NpuProbeScheme?
        get() = when (this) {
            VENDOR_PERCENT -> NpuProbeScheme.VENDOR_PERCENT
            DEVFREQ -> NpuProbeScheme.DEVFREQ
            ROCKCHIP -> NpuProbeScheme.ROCKCHIP
            SAMSUNG_RQ_LOAD -> NpuProbeScheme.SAMSUNG_RQ_LOAD
            QUALCOMM_DIAGNOSTIC -> NpuProbeScheme.QUALCOMM_DIAGNOSTIC
            AUTO, ALL -> null
        }

    companion object {
        fun fromId(value: String?): NpuSource = entries.firstOrNull { it.id == value } ?: AUTO
    }
}

internal enum class NpuProbeScheme(val id: String, val label: String) {
    VENDOR_PERCENT("vendor_percent", "厂商负载节点"),
    DEVFREQ("devfreq", "devfreq 设备"),
    ROCKCHIP("rockchip", "Rockchip RKNPU"),
    SAMSUNG_RQ_LOAD("samsung_rq_load", "Samsung 请求队列"),
    QUALCOMM_DIAGNOSTIC("qualcomm_diag", "Qualcomm DSP 诊断"),
    COUNTER_DELTA("counter_delta", "busy/total 计数差分"),
}

internal enum class NpuProbeState(val label: String) {
    OK("ok"),
    MISSING("missing"),
    PERMISSION_DENIED("permission_denied"),
    UNSUPPORTED("unsupported"),
    PARSE_ERROR("parse_error"),
    STALE("stale"),
}

/** One additional attribute read from the same source, e.g. Samsung `load` / `fps_load`. */
internal data class NpuReading(
    val label: String,
    val value: Float,
    val unit: String,
    val raw: String,
)

internal data class NpuProbeResult(
    val scheme: NpuProbeScheme,
    val sourcePath: String?,
    val value: Float?,
    /** Display unit for [value]; a diagnosis-only source may report ticks/raw. */
    val unit: String,
    /** What the number actually means; never silently relabel it as hardware MAC utilization. */
    val scope: String,
    val timestampNanos: Long,
    val state: NpuProbeState,
    val perCore: List<Float> = emptyList(),
    val readings: List<NpuReading> = emptyList(),
    val rawExcerpt: String? = null,
    val detail: String? = null,
)

internal sealed interface ProbeRead {
    data class Content(val text: String) : ProbeRead
    data object Missing : ProbeRead
    data object PermissionDenied : ProbeRead
    data class Failure(val message: String) : ProbeRead
}

/** Small filesystem seam so all parsers and states are exercised on the JVM. */
internal interface ProbeFileSystem {
    fun read(path: String): ProbeRead
    fun listDirectories(parent: String): List<String>
}

internal object RealProbeFileSystem : ProbeFileSystem {
    override fun read(path: String): ProbeRead {
        val file = File(path)
        if (!file.exists()) {
            // Java reports EACCES as "missing"; a closer existing parent that is unreadable is a
            // clearer permission_denied signal, especially for 0400 debugfs files.
            return if (hasUnreadableParent(file)) ProbeRead.PermissionDenied else ProbeRead.Missing
        }
        if (!file.canRead()) return ProbeRead.PermissionDenied
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(MAX_PROBE_BYTES)
                val count = input.read(buffer)
                if (count <= 0) ProbeRead.Content("")
                else ProbeRead.Content(String(buffer, 0, count, StandardCharsets.UTF_8))
            }
        } catch (_: SecurityException) {
            ProbeRead.PermissionDenied
        } catch (error: Exception) {
            ProbeRead.Failure(error.message ?: "读取失败")
        }
    }

    override fun listDirectories(parent: String): List<String> {
        val directory = File(parent)
        if (!directory.exists() || !directory.canRead()) return emptyList()
        return try {
            directory.listFiles()?.asSequence()
                ?.filter { it.isDirectory }
                ?.take(MAX_PROBE_DIRECTORIES)
                ?.map { it.absolutePath }
                ?.toList()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hasUnreadableParent(file: File): Boolean {
        var parent = file.parentFile
        var depth = 0
        while (parent != null && depth++ < 4) {
            if (parent.exists()) return !parent.canRead()
            parent = parent.parentFile
        }
        return false
    }

    private const val MAX_PROBE_BYTES = 4096
    private const val MAX_PROBE_DIRECTORIES = 64
}

/** Pure, bounded NPU probes. Each parser follows one documented vendor format; none guesses a
 * number out of arbitrary text, and none treats frequency/address/trans_stat as utilization.
 */
internal class NpuProbeReader(private val files: ProbeFileSystem = RealProbeFileSystem) {
    fun probe(source: NpuSource, nowNanos: Long): List<NpuProbeResult> = when (source) {
        NpuSource.AUTO -> probeAuto(nowNanos)
        NpuSource.ALL -> probeAll(nowNanos)
        else -> probeScheme(checkNotNull(source.scheme), nowNanos)
    }

    fun probeAll(nowNanos: Long): List<NpuProbeResult> = buildList {
        usageSchemes.forEach { addAll(probeScheme(it, nowNanos)) }
        addAll(probeQualcomm(nowNanos))
        add(probeCounterDelta(nowNanos))
    }

    private fun probeAuto(nowNanos: Long): List<NpuProbeResult> {
        val results = mutableListOf<NpuProbeResult>()
        for (scheme in autoOrder) {
            val next = probeScheme(scheme, nowNanos)
            results += next
            if (next.any { it.state == NpuProbeState.OK }) break
        }
        return results
    }

    private fun probeScheme(scheme: NpuProbeScheme, nowNanos: Long): List<NpuProbeResult> = when (scheme) {
        NpuProbeScheme.VENDOR_PERCENT -> probeVendor(nowNanos)
        NpuProbeScheme.DEVFREQ -> probeDevfreq(nowNanos)
        NpuProbeScheme.ROCKCHIP -> probeRockchip(nowNanos)
        NpuProbeScheme.SAMSUNG_RQ_LOAD -> probeSamsung(nowNanos)
        NpuProbeScheme.QUALCOMM_DIAGNOSTIC -> probeQualcomm(nowNanos)
        NpuProbeScheme.COUNTER_DELTA -> listOf(probeCounterDelta(nowNanos))
    }

    /** `misc/npu` and `platform/npu` expose a percentage value; the whole file must be that value. */
    private fun probeVendor(nowNanos: Long): List<NpuProbeResult> {
        var failure: NpuProbeResult? = null
        for (path in VENDOR_PERCENT_NODES) {
            when (val read = files.read(path)) {
                is ProbeRead.Content -> {
                    val percent = parseStrictPercent(read.text)
                    if (percent != null) return listOf(result(
                        NpuProbeScheme.VENDOR_PERCENT, path, percent, "%", LOAD_SCOPE, nowNanos,
                    ))
                    if (failure == null || failure.state == NpuProbeState.MISSING) {
                        failure = stateResult(
                            NpuProbeScheme.VENDOR_PERCENT, path, NpuProbeState.PARSE_ERROR, nowNanos,
                            excerpt = excerpt(read.text), detail = "不是完整的百分比数值",
                        )
                    }
                }
                ProbeRead.Missing -> if (failure == null) {
                    failure = stateResult(NpuProbeScheme.VENDOR_PERCENT, path, NpuProbeState.MISSING, nowNanos)
                }
                ProbeRead.PermissionDenied -> failure =
                    stateResult(NpuProbeScheme.VENDOR_PERCENT, path, NpuProbeState.PERMISSION_DENIED, nowNanos)
                is ProbeRead.Failure -> if (failure == null || failure.state == NpuProbeState.MISSING) {
                    failure = stateResult(
                        NpuProbeScheme.VENDOR_PERCENT, path, NpuProbeState.PARSE_ERROR, nowNanos,
                        detail = read.message,
                    )
                }
            }
        }
        return listOf(failure ?: stateResult(
            NpuProbeScheme.VENDOR_PERCENT, VENDOR_PERCENT_NODES.last(), NpuProbeState.MISSING, nowNanos,
        ))
    }

    /** devfreq discovery by device name/identity, then only known load attributes. `busy`,
     * `total`, `trans_stat`, `cur_freq` and address nodes are deliberately not utilization.
     */
    private fun probeDevfreq(nowNanos: Long): List<NpuProbeResult> {
        val devices = files.listDirectories(DEV_FREQ).filter(::isNpuIdentity)
        if (devices.isEmpty()) {
            return listOf(stateResult(
                NpuProbeScheme.DEVFREQ, DEV_FREQ, NpuProbeState.UNSUPPORTED, nowNanos,
                detail = "未发现名称含 npu/nna 的 devfreq 设备",
            ))
        }
        var failure: NpuProbeResult? = null
        for (device in devices) {
            for (attribute in DEVFREQ_LOAD_ATTRIBUTES) {
                val path = "$device/$attribute"
                when (val read = files.read(path)) {
                    is ProbeRead.Content -> {
                        val percent = parseStrictPercent(read.text)
                        if (percent != null) return listOf(result(
                            NpuProbeScheme.DEVFREQ, path, percent, "%", LOAD_SCOPE, nowNanos,
                        ))
                        if (failure == null || failure.state == NpuProbeState.MISSING) {
                            failure = stateResult(
                                NpuProbeScheme.DEVFREQ, path, NpuProbeState.PARSE_ERROR, nowNanos,
                                excerpt = excerpt(read.text), detail = "不是 0–100 的负载百分比",
                            )
                        }
                    }
                    ProbeRead.Missing -> if (failure == null) {
                        failure = stateResult(NpuProbeScheme.DEVFREQ, device, NpuProbeState.MISSING, nowNanos)
                    }
                    ProbeRead.PermissionDenied -> failure =
                        stateResult(NpuProbeScheme.DEVFREQ, path, NpuProbeState.PERMISSION_DENIED, nowNanos)
                    is ProbeRead.Failure -> if (failure == null || failure.state == NpuProbeState.MISSING) {
                        failure = stateResult(
                            NpuProbeScheme.DEVFREQ, path, NpuProbeState.PARSE_ERROR, nowNanos,
                            detail = read.message,
                        )
                    }
                }
            }
        }
        return listOf(failure ?: stateResult(
            NpuProbeScheme.DEVFREQ, devices.first(), NpuProbeState.MISSING, nowNanos,
        ))
    }

    /** Rockchip rknpu_debugger.c: `NPU load: Core0: 12%, Core1: ...`; `%2.d%%` may render a zero
     * as whitespace plus `%`. The core number is never the load. `rknpu_devfreq` busy/total is a
     * fixed 1/1 placeholder and is intentionally not read.
     */
    private fun probeRockchip(nowNanos: Long): List<NpuProbeResult> {
        var failure: NpuProbeResult? = null
        for (path in ROCKCHIP_LOAD_NODES) {
            when (val read = files.read(path)) {
                is ProbeRead.Content -> {
                    val parsed = parseRockchipLoad(read.text)
                    if (parsed != null) return listOf(result(
                        NpuProbeScheme.ROCKCHIP, path, parsed.average, "%", LOAD_SCOPE, nowNanos,
                        perCore = parsed.perCore,
                        detail = parsed.coreIndexes.zip(parsed.perCore)
                            .joinToString(" ") { (core, load) -> "Core$core=${formatNumber(load)}%" },
                        rawExcerpt = excerpt(read.text),
                    ))
                    if (failure == null || failure.state == NpuProbeState.MISSING) {
                        failure = stateResult(
                            NpuProbeScheme.ROCKCHIP, path, NpuProbeState.PARSE_ERROR, nowNanos,
                            excerpt = excerpt(read.text), detail = "不是 `NPU load: CoreN: x%` 格式",
                        )
                    }
                }
                ProbeRead.Missing -> if (failure == null) {
                    failure = stateResult(NpuProbeScheme.ROCKCHIP, path, NpuProbeState.MISSING, nowNanos)
                }
                ProbeRead.PermissionDenied -> failure =
                    stateResult(NpuProbeScheme.ROCKCHIP, path, NpuProbeState.PERMISSION_DENIED, nowNanos)
                is ProbeRead.Failure -> if (failure == null || failure.state == NpuProbeState.MISSING) {
                    failure = stateResult(
                        NpuProbeScheme.ROCKCHIP, path, NpuProbeState.PARSE_ERROR, nowNanos,
                        detail = read.message,
                    )
                }
            }
        }
        return listOf(failure ?: stateResult(
            NpuProbeScheme.ROCKCHIP, ROCKCHIP_LOAD_NODES.last(), NpuProbeState.MISSING, nowNanos,
        ))
    }

    /** Samsung Exynos npu-scheduler.c registers `rq_load` on the NPU device. Raw 0–10000 is
     * 0.01% units and means scheduler request-queue busy, not hardware MAC utilization. `load`
     * is policy-dependent and `fps_load` is execution time vs target frame time; both stay
     * separate additional readings.
     */
    private fun probeSamsung(nowNanos: Long): List<NpuProbeResult> {
        val devices = npuDeviceCandidates()
        if (devices.isEmpty()) {
            return listOf(stateResult(
                NpuProbeScheme.SAMSUNG_RQ_LOAD, NPU_CLASS, NpuProbeState.UNSUPPORTED, nowNanos,
                detail = "未发现名称含 npu/nna 的 NPU 设备",
            ))
        }
        var failure: NpuProbeResult? = null
        for (device in devices) {
            val path = "$device/rq_load"
            when (val read = files.read(path)) {
                is ProbeRead.Content -> {
                    val percent = parseSamsungRqLoad(read.text)
                    if (percent == null) {
                        if (failure == null || failure.state == NpuProbeState.MISSING) {
                            failure = stateResult(
                                NpuProbeScheme.SAMSUNG_RQ_LOAD, path, NpuProbeState.PARSE_ERROR, nowNanos,
                                excerpt = excerpt(read.text), detail = "rq_load 必须是 0–10000 的原始值",
                            )
                        }
                        continue
                    }
                    val readings = mutableListOf(NpuReading(
                        "rq_load", percent, "%（原始 0–10000 = 0.01%）", read.text.trim().take(40),
                    ))
                    readNumber(device, "load")?.let { (value, raw) ->
                        readings += NpuReading("load（受 load_policy 影响）", value, "raw", raw)
                    }
                    readNumber(device, "fps_load")?.let { (value, raw) ->
                        readings += NpuReading("fps_load（执行时间/目标帧时间）", value, "raw", raw)
                    }
                    val policy = readText(device, "load_policy")
                    return listOf(result(
                        NpuProbeScheme.SAMSUNG_RQ_LOAD, path, percent, "%",
                        "NPU 请求队列忙碌率", nowNanos, readings = readings,
                        detail = "load_policy=${policy ?: "未知"}；load 受策略影响，fps_load 为执行时间/目标帧时间，均不并入 rq_load",
                    ))
                }
                ProbeRead.Missing -> if (failure == null) {
                    failure = stateResult(
                        NpuProbeScheme.SAMSUNG_RQ_LOAD, device, NpuProbeState.MISSING, nowNanos,
                    )
                }
                ProbeRead.PermissionDenied -> failure =
                    stateResult(NpuProbeScheme.SAMSUNG_RQ_LOAD, path, NpuProbeState.PERMISSION_DENIED, nowNanos)
                is ProbeRead.Failure -> if (failure == null || failure.state == NpuProbeState.MISSING) {
                    failure = stateResult(
                        NpuProbeScheme.SAMSUNG_RQ_LOAD, path, NpuProbeState.PARSE_ERROR, nowNanos,
                        detail = read.message,
                    )
                }
            }
        }
        return listOf(failure ?: stateResult(
            NpuProbeScheme.SAMSUNG_RQ_LOAD, devices.first(), NpuProbeState.MISSING, nowNanos,
        ))
    }

    /** qcom_stats.c creates debug-only cdsp/cdsp1 files (0400). Accumulated Duration is hardware
     * ticks; this is DSP sleep/statistics diagnostics, never NPU utilization, with no hardcoded
     * clock conversion.
     */
    private fun probeQualcomm(nowNanos: Long): List<NpuProbeResult> = QUALCOMM_STATS_NODES.map { path ->
        when (val read = files.read(path)) {
            is ProbeRead.Content -> {
                val line = read.text.lineSequence()
                    .firstOrNull { it.contains("Accumulated Duration", ignoreCase = true) }
                    ?.trim()
                when {
                    line != null -> result(
                        NpuProbeScheme.QUALCOMM_DIAGNOSTIC, path, null, "ticks",
                        QUALCOMM_SCOPE, nowNanos, rawExcerpt = excerpt(line),
                        detail = "Accumulated Duration 为硬件计数器 ticks；不换算为百分比",
                    )
                    read.text.isBlank() -> stateResult(
                        NpuProbeScheme.QUALCOMM_DIAGNOSTIC, path, NpuProbeState.PARSE_ERROR, nowNanos,
                        detail = "节点内容为空", unit = "ticks", scope = QUALCOMM_SCOPE,
                    )
                    else -> stateResult(
                        NpuProbeScheme.QUALCOMM_DIAGNOSTIC, path, NpuProbeState.PARSE_ERROR, nowNanos,
                        excerpt = excerpt(read.text), detail = "未找到 Accumulated Duration",
                        unit = "ticks", scope = QUALCOMM_SCOPE,
                    )
                }
            }
            ProbeRead.Missing -> stateResult(
                NpuProbeScheme.QUALCOMM_DIAGNOSTIC, path, NpuProbeState.MISSING, nowNanos,
                unit = "ticks", scope = QUALCOMM_SCOPE,
            )
            ProbeRead.PermissionDenied -> stateResult(
                NpuProbeScheme.QUALCOMM_DIAGNOSTIC, path, NpuProbeState.PERMISSION_DENIED, nowNanos,
                unit = "ticks", scope = QUALCOMM_SCOPE,
            )
            is ProbeRead.Failure -> stateResult(
                NpuProbeScheme.QUALCOMM_DIAGNOSTIC, path, NpuProbeState.PARSE_ERROR, nowNanos,
                detail = read.message, unit = "ticks", scope = QUALCOMM_SCOPE,
            )
        }
    }

    /** No documented busy/total node has been shown to update and be exported to a normal app;
     * Rockchip rknpu_devfreq initializes busy_time/total_time to 1/1, so it is explicitly blocked.
     */
    private fun probeCounterDelta(nowNanos: Long): NpuProbeResult = stateResult(
        NpuProbeScheme.COUNTER_DELTA, null, NpuProbeState.UNSUPPORTED, nowNanos,
        detail = "未找到可证明会更新且对普通 App 导出的 busy/total 计数；不使用 Rockchip devfreq 固定 1/1 占位",
    )

    private fun isNpuIdentity(path: String): Boolean {
        val base = path.substringAfterLast('/').lowercase(Locale.US)
        val name = (files.read("$path/name") as? ProbeRead.Content)?.text?.lowercase(Locale.US).orEmpty()
        return base.contains("npu") || base.contains("nna") || name.contains("npu") || name.contains("nna")
    }

    private fun npuDeviceCandidates(): List<String> {
        val roots = listOf(DEV_FREQ, NPU_CLASS, MISC_CLASS)
        val devices = roots.flatMap { root ->
            files.listDirectories(root).filter { directory ->
                val base = directory.substringAfterLast('/').lowercase(Locale.US)
                when (root) {
                    MISC_CLASS -> base == "npu" || base == "nna"
                    else -> isNpuIdentity(directory)
                }
            }
        }.distinct()
        return devices.flatMap { listOf(it, "$it/device") }.distinct()
    }

    private fun readText(base: String, name: String): String? =
        (files.read("$base/$name") as? ProbeRead.Content)?.text?.trim()?.takeIf { it.isNotEmpty() }

    private fun readNumber(base: String, name: String): Pair<Float, String>? {
        val raw = readText(base, name) ?: return null
        val value = parseRawNumber(raw) ?: return null
        return value to raw.take(40)
    }

    private fun result(
        scheme: NpuProbeScheme,
        path: String,
        value: Float?,
        unit: String,
        scope: String,
        nowNanos: Long,
        perCore: List<Float> = emptyList(),
        readings: List<NpuReading> = emptyList(),
        rawExcerpt: String? = null,
        detail: String? = null,
    ) = NpuProbeResult(
        scheme, path, value, unit, scope, nowNanos, NpuProbeState.OK,
        perCore = perCore, readings = readings, rawExcerpt = rawExcerpt, detail = detail,
    )

    private fun stateResult(
        scheme: NpuProbeScheme,
        path: String?,
        state: NpuProbeState,
        nowNanos: Long,
        excerpt: String? = null,
        detail: String? = null,
        unit: String = "%",
        scope: String = scheme.label,
    ) = NpuProbeResult(
        scheme, path, null, unit, scope, nowNanos, state,
        rawExcerpt = excerpt, detail = detail,
    )

    private val usageSchemes = listOf(
        NpuProbeScheme.VENDOR_PERCENT,
        NpuProbeScheme.DEVFREQ,
        NpuProbeScheme.ROCKCHIP,
        NpuProbeScheme.SAMSUNG_RQ_LOAD,
    )
    private val autoOrder = listOf(
        NpuProbeScheme.ROCKCHIP,
        NpuProbeScheme.SAMSUNG_RQ_LOAD,
        NpuProbeScheme.VENDOR_PERCENT,
        NpuProbeScheme.DEVFREQ,
    )

    private companion object {
        const val DEV_FREQ = "/sys/class/devfreq"
        const val NPU_CLASS = "/sys/class/npu"
        const val MISC_CLASS = "/sys/class/misc"
        const val LOAD_SCOPE = "NPU 负载"
        const val QUALCOMM_SCOPE = "DSP 睡眠统计（非利用率）"

        val VENDOR_PERCENT_NODES = listOf(
            "/sys/class/misc/npu/device/load",
            "/sys/devices/platform/npu/load",
        )
        val DEVFREQ_LOAD_ATTRIBUTES = listOf("load", "npu_load")
        val ROCKCHIP_LOAD_NODES = listOf(
            "/sys/kernel/debug/rknpu/load",
            "/proc/rknpu/load",
        )
        val QUALCOMM_STATS_NODES = listOf(
            "/sys/kernel/debug/qcom_stats/cdsp",
            "/sys/kernel/debug/qcom_stats/cdsp1",
        )
    }
}

/** Whole-file percentage: optional surrounding whitespace and `%`, but no extra text. */
internal fun parseStrictPercent(raw: String): Float? {
    val match = STRICT_PERCENT.matchEntire(raw.trim()) ?: return null
    val value = match.groupValues[1].toFloatOrNull() ?: return null
    return value.takeIf { it in 0f..100f }
}

internal data class RockchipLoad(val coreIndexes: List<Int>, val perCore: List<Float>) {
    val average: Float get() = perCore.average().toFloat()
}

/** One parser shared by RKNPU debugfs and procfs. A missing number is zero, not the core index. */
internal fun parseRockchipLoad(raw: String): RockchipLoad? {
    if (!raw.contains("NPU load", ignoreCase = true)) return null
    val cores = mutableMapOf<Int, Float>()
    for (match in ROCKCHIP_CORE.findAll(raw)) {
        val index = match.groupValues[1].toIntOrNull() ?: return null
        val text = match.groupValues[2]
        val value = if (text.isEmpty()) 0f else text.toFloatOrNull() ?: return null
        if (value !in 0f..100f) return null
        cores[index] = value
    }
    if (cores.isEmpty()) return null
    val ordered = cores.toSortedMap()
    return RockchipLoad(ordered.keys.toList(), ordered.values.toList())
}

/** Samsung scheduler `rq_load`: raw 0–10000 in 0.01% units. */
internal fun parseSamsungRqLoad(raw: String): Float? {
    val value = raw.trim().toFloatOrNull() ?: return null
    return (value / 100f).takeIf { value in 0f..10000f }
}

internal fun parseRawNumber(raw: String): Float? =
    raw.trim().toFloatOrNull()?.takeIf { it.isFinite() }

internal fun excerpt(text: String?): String? =
    text?.trim()?.replace('\n', ' ')?.take(120)?.takeIf { it.isNotEmpty() }

internal fun formatNumber(value: Float): String = String.format(Locale.US, "%.1f", value)

private val STRICT_PERCENT = Regex("""(\d+(?:\.\d+)?)\s*%?""")
private val ROCKCHIP_CORE = Regex("""Core\s*(\d+)\s*:\s*(\d{1,3}(?:\.\d+)?)?\s*%""")
