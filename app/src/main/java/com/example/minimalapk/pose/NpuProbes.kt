package com.example.minimalapk.pose

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
    TRUNCATED("truncated"),
    TIMEOUT("timeout"),
    CANCELLED("cancelled"),
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
    /** Complete bounded reads, including discovery/failures; never routed through message limits. */
    val evidence: List<ProbeEvidence> = emptyList(),
)

/** Pure, bounded NPU probes. Only documented vendor contracts produce percentages. Generic
 * load attributes remain raw diagnostics: their names, numeric range or `%` suffix do not prove
 * what the driver measures. Frequency/address/trans_stat are never treated as utilization.
 */
internal class NpuProbeReader(
    private val files: ProbeFileSystem = RealProbeFileSystem,
    private val clock: () -> Long = System::nanoTime,
) {
    fun probe(source: NpuSource, nowNanos: Long, stillNeeded: () -> Boolean = { true }): List<NpuProbeResult> =
        NpuProbeRun(ProbeRunFileSystem(files, clock, stillNeeded)).probe(source, nowNanos)

    fun probeAll(nowNanos: Long): List<NpuProbeResult> = probe(NpuSource.ALL, nowNanos)
}

private class NpuProbeRun(private val files: ProbeRunFileSystem) {
    fun probe(source: NpuSource, nowNanos: Long): List<NpuProbeResult> = when (source) {
        NpuSource.AUTO -> probeAuto(nowNanos)
        NpuSource.ALL -> probeAll(nowNanos)
        else -> probeScheme(checkNotNull(source.scheme), nowNanos)
    }

    fun probeAll(nowNanos: Long): List<NpuProbeResult> = buildList {
        // Known contracts first so raw discovery cannot consume the budget before real values.
        autoOrder.forEach { addAll(probeScheme(it, nowNanos)) }
        addAll(probeScheme(NpuProbeScheme.QUALCOMM_DIAGNOSTIC, nowNanos))
        addAll(probeScheme(NpuProbeScheme.COUNTER_DELTA, nowNanos))
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

    private fun probeScheme(scheme: NpuProbeScheme, nowNanos: Long): List<NpuProbeResult> {
        val start = files.evidence.size
        val results = when (scheme) {
            NpuProbeScheme.VENDOR_PERCENT -> probeVendor(nowNanos)
            NpuProbeScheme.DEVFREQ -> probeDevfreq(nowNanos)
            NpuProbeScheme.ROCKCHIP -> probeRockchip(nowNanos)
            NpuProbeScheme.SAMSUNG_RQ_LOAD -> probeSamsung(nowNanos)
            NpuProbeScheme.QUALCOMM_DIAGNOSTIC -> probeQualcomm(nowNanos)
            NpuProbeScheme.COUNTER_DELTA -> listOf(probeCounterDelta(nowNanos))
        }
        // The small panel may collapse missing candidates, but the copy action retains EVERY
        // attempted path, directory failure and exact bounded read, not only the winning node.
        val evidence = files.evidence.drop(start)
        return results.mapIndexed { index, result ->
            // Direct node results own their read; the first also owns discovery/auxiliary reads.
            if (index != 0) result.copy(evidence = evidence.filter { it.path == result.sourcePath }) else {
                val discoveryFailure = evidence.firstOrNull {
                    it.state in setOf(NpuProbeState.PERMISSION_DENIED, NpuProbeState.TIMEOUT, NpuProbeState.TRUNCATED)
                }.takeIf { result.state == NpuProbeState.UNSUPPORTED && result.rawExcerpt == null }
                if (discoveryFailure == null) result.copy(evidence = evidence)
                else result.copy(state = discoveryFailure.state, sourcePath = discoveryFailure.path,
                    detail = discoveryFailure.detail ?: "设备发现失败：${discoveryFailure.state.label}", evidence = evidence)
            }
        }
    }

    /** Keep the persisted scheme ID, but neither legacy path has a verified percentage contract. */
    private fun probeVendor(nowNanos: Long): List<NpuProbeResult> =
        probeUnverifiedLoads(NpuProbeScheme.VENDOR_PERCENT, VENDOR_LOAD_NODES, nowNanos)

    /** Discover candidate load attributes without assuming their units. `load` is not a standard
     * devfreq percentage ABI; e.g. Samsung's scheduler load depends on load_policy and uses 0.01%
     * units. Only its separate rq_load scheme below implements the documented queue-load contract.
     * `busy`, `total`, `trans_stat`, `cur_freq` and address nodes are deliberately not read.
     */
    private fun probeDevfreq(nowNanos: Long): List<NpuProbeResult> {
        val devices = files.listDirectories(DEV_FREQ).filter(::isNpuIdentity)
        if (devices.isEmpty()) {
            return listOf(stateResult(
                NpuProbeScheme.DEVFREQ, DEV_FREQ, NpuProbeState.UNSUPPORTED, nowNanos,
                detail = "未发现名称含 npu/nna 的 devfreq 设备", unit = "raw",
            ))
        }
        return probeUnverifiedLoads(NpuProbeScheme.DEVFREQ, devices.flatMap { device ->
            DEVFREQ_LOAD_ATTRIBUTES.map { "$device/$it" }
        }, nowNanos)
    }

    /** No new driver contracts are inferred here. Keep readable raw evidence and read failures;
     * collapse missing candidates only, so one unsupported attribute cannot hide another source.
     */
    private fun probeUnverifiedLoads(
        scheme: NpuProbeScheme,
        paths: List<String>,
        nowNanos: Long,
    ): List<NpuProbeResult> {
        val results = paths.map { path ->
            val attribute = path.substringAfterLast('/')
            fun diagnostic(state: NpuProbeState, raw: String? = null, detail: String? = null) = stateResult(
                scheme, path, state, nowNanos, excerpt = excerpt(raw), detail = detail,
                unit = "raw", scope = "$attribute（单位/作用域未确认）",
            )
            when (val read = files.read(path)) {
                is ProbeRead.Content -> {
                    val raw = read.text.trim()
                    when {
                        raw.isEmpty() -> diagnostic(NpuProbeState.PARSE_ERROR, raw, "$attribute 节点内容为空")
                        raw.toDoubleOrNull()?.isFinite() == false -> diagnostic(
                            NpuProbeState.PARSE_ERROR, raw, "$attribute 返回非有限数值，无法解释负载",
                        )
                        else -> diagnostic(
                            NpuProbeState.UNSUPPORTED, raw,
                            "$attribute 单位/作用域未确认；缺少已验证的驱动契约，不能按数值范围或 % 后缀推定利用率",
                        )
                    }
                }
                ProbeRead.Missing -> diagnostic(NpuProbeState.MISSING)
                ProbeRead.PermissionDenied -> diagnostic(NpuProbeState.PERMISSION_DENIED)
                is ProbeRead.Failure -> diagnostic(read.state, detail = read.message)
            }
        }
        return results.filter { it.state != NpuProbeState.MISSING }.ifEmpty { results.take(1) }
    }

    /** Rockchip rknpu_debugger.c: `NPU load: Core0: 12%, Core1: ...`; `%2.d%%` may render a zero
     * as whitespace plus `%`. The core number is never the load. `rknpu_devfreq` busy/total is a
     * fixed 1/1 placeholder and is intentionally not read.
     * Contract: rockchip-linux/kernel@95a3ad83f7f90e0e672b9c99b0858b4964318667,
     * drivers/rknpu/rknpu_debugger.c:27–75 (issue #66).
     */
    private fun probeRockchip(nowNanos: Long): List<NpuProbeResult> {
        val results = ROCKCHIP_LOAD_NODES.map { path ->
            when (val read = files.read(path)) {
                is ProbeRead.Content -> {
                    val parsed = parseRockchipLoad(read.text)
                    if (parsed != null) result(
                        NpuProbeScheme.ROCKCHIP, path, parsed.average, "%", LOAD_SCOPE, nowNanos,
                        perCore = parsed.perCore,
                        detail = parsed.coreIndexes.zip(parsed.perCore)
                            .joinToString(" ") { (core, load) -> "Core$core=${formatNumber(load)}%" },
                        rawExcerpt = excerpt(read.text),
                    ) else stateResult(
                        NpuProbeScheme.ROCKCHIP, path, NpuProbeState.PARSE_ERROR, nowNanos,
                        excerpt = excerpt(read.text), detail = "不是完整的 `NPU load: x%` / `NPU load: CoreN: x%` 格式",
                    )
                }
                ProbeRead.Missing -> stateResult(NpuProbeScheme.ROCKCHIP, path, NpuProbeState.MISSING, nowNanos)
                ProbeRead.PermissionDenied ->
                    stateResult(NpuProbeScheme.ROCKCHIP, path, NpuProbeState.PERMISSION_DENIED, nowNanos)
                is ProbeRead.Failure -> stateResult(
                    NpuProbeScheme.ROCKCHIP, path, read.state, nowNanos, detail = read.message,
                )
            }
        }
        // Keep both locations even when one disappears: the sampler's last-good cache is keyed
        // by actual source path, so collapsing a missing procfs path would lose its stale value.
        return results
    }

    /** Samsung Exynos npu-scheduler.c registers `rq_load` on the NPU device. Raw 0–10000 is
     * 0.01% units and means scheduler request-queue busy, not hardware MAC utilization. `load`
     * is policy-dependent and `fps_load` is execution time vs target frame time; both stay
     * separate additional readings.
     * Contract: ExtremeXT/android_kernel_samsung_exynos990@69515fbb7a4395898c05a8624f76a12afbac11c5,
     * drivers/vision/npu/core/npu-scheduler.c:728–746,1104–1125 (issue #66).
     */
    private fun probeSamsung(nowNanos: Long): List<NpuProbeResult> {
        val devices = npuDeviceCandidates()
        if (devices.isEmpty()) {
            return listOf(stateResult(
                NpuProbeScheme.SAMSUNG_RQ_LOAD, NPU_CLASS, NpuProbeState.UNSUPPORTED, nowNanos,
                detail = "未发现名称含 npu/nna 的 NPU 设备",
            ))
        }
        val results = mutableListOf<NpuProbeResult>()
        for (device in devices) {
            val path = "$device/rq_load"
            when (val read = files.read(path)) {
                is ProbeRead.Content -> {
                    if (!isSamsungIdentity(device)) {
                        results += stateResult(NpuProbeScheme.SAMSUNG_RQ_LOAD, path, NpuProbeState.UNSUPPORTED, nowNanos,
                            excerpt = excerpt(read.text), unit = "raw",
                            detail = "发现 rq_load，但未确认 Samsung exynos-npu 驱动身份，不推定 0.01% 单位")
                        continue
                    }
                    val percent = parseSamsungRqLoad(read.text)
                    if (percent == null) {
                        results += stateResult(
                            NpuProbeScheme.SAMSUNG_RQ_LOAD, path, NpuProbeState.PARSE_ERROR, nowNanos,
                            excerpt = excerpt(read.text), detail = "rq_load 必须是 0–10000 的原始整数值",
                        )
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
                    val policyRaw = readText(device, "load_policy")
                    val policy = policyRaw?.let(::parseSamsungLoadPolicy)
                    // These are read-only scheduler diagnostics, never requests to enable it.
                    val enabled = readText(device, "scheduler_enable")
                    readText(device, "timestamp")
                    val reading = result(
                        NpuProbeScheme.SAMSUNG_RQ_LOAD, path, percent, "%",
                        "NPU 请求队列忙碌率", nowNanos, readings = readings,
                        rawExcerpt = excerpt(read.text),
                        detail = "load_policy=${policy?.let { "${it.id} ${it.label}" } ?: "未知"}；load 受策略影响，fps_load 为执行时间/目标帧时间，均不并入 rq_load",
                    )
                    results += if (enabled == "0") reading.copy(state = NpuProbeState.STALE,
                        detail = "scheduler_enable=0，调度器停止更新；${reading.detail}") else reading
                }
                ProbeRead.Missing -> {
                    results += stateResult(
                        NpuProbeScheme.SAMSUNG_RQ_LOAD, path, NpuProbeState.MISSING, nowNanos,
                    )
                }
                ProbeRead.PermissionDenied -> results +=
                    stateResult(NpuProbeScheme.SAMSUNG_RQ_LOAD, path, NpuProbeState.PERMISSION_DENIED, nowNanos)
                is ProbeRead.Failure -> {
                    results += stateResult(
                        NpuProbeScheme.SAMSUNG_RQ_LOAD, path, read.state, nowNanos,
                        detail = read.message,
                    )
                }
            }
        }
        return results.filter { it.state != NpuProbeState.MISSING }.ifEmpty { results.take(1) }
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
                NpuProbeScheme.QUALCOMM_DIAGNOSTIC, path, read.state, nowNanos,
                detail = read.message, unit = "ticks", scope = QUALCOMM_SCOPE,
            )
        }
    }

    /** No documented busy/total node has been shown to update and be exported to a normal app;
     * Rockchip rknpu_devfreq initializes busy_time/total_time to 1/1, so it is explicitly blocked.
     */
    private fun probeCounterDelta(nowNanos: Long): NpuProbeResult = stateResult(
        NpuProbeScheme.COUNTER_DELTA, null, NpuProbeState.UNSUPPORTED, nowNanos,
        unit = "raw",
        detail = "未找到可证明会更新且对普通 App 导出的 busy/total 计数；不使用 Rockchip devfreq 固定 1/1 占位；已核查 MSM NPU sysfs/debugfs 仅有能力、功耗状态、控制和寄存器调试节点",
    )

    private fun isNpuIdentity(path: String): Boolean {
        val base = path.substringAfterLast('/').lowercase(Locale.US)
        if (NPU_IDENTITY.containsMatchIn(base)) return true
        val name = (files.read("$path/name") as? ProbeRead.Content)?.text?.lowercase(Locale.US).orEmpty()
        if (NPU_IDENTITY.containsMatchIn(name)) return true
        // Numeric devfreq names do occur. Device-tree identity is evidence; the address is not.
        return listOf("device/of_node/compatible", "of_node/compatible").any { attribute ->
            val identity = (files.read("$path/$attribute") as? ProbeRead.Content)?.text.orEmpty()
            NPU_IDENTITY.containsMatchIn(identity.lowercase(Locale.US))
        }
    }

    private fun npuDeviceCandidates(): List<String> {
        // npu-scheduler registers attributes on pdev->dev, not on the devfreq device. The
        // documented platform driver's bound-device directory discovers the real address.
        val bound = files.listDirectories(SAMSUNG_DRIVER).filter { it.substringAfterLast('/') != "module" }
        if (bound.isNotEmpty()) return bound
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

    private fun isSamsungIdentity(path: String): Boolean = path.startsWith("$SAMSUNG_DRIVER/") ||
        readText(path, "name")?.trim() == "exynos-npu" ||
        listOf("of_node/compatible", "device/of_node/compatible").any { name ->
            readText(path, name)?.split('\u0000')?.any { it == "samsung,exynos-npu" } == true
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
        const val SAMSUNG_DRIVER = "/sys/bus/platform/drivers/exynos-npu"
        const val LOAD_SCOPE = "NPU 负载"
        const val QUALCOMM_SCOPE = "DSP 睡眠统计（非利用率）"
        val NPU_IDENTITY = Regex("(?:^|[^a-z])(?:rknpu|npu|nna)(?:[0-9]|[^a-z]|$)")

        val VENDOR_LOAD_NODES = listOf(
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

internal data class RockchipLoad(val coreIndexes: List<Int>, val perCore: List<Float>) {
    val average: Float get() = perCore.average().toFloat()
}

/** One parser shared by RKNPU debugfs and procfs. A missing number is zero, not the core index. */
internal fun parseRockchipLoad(raw: String): RockchipLoad? {
    val body = ROCKCHIP_HEADER.matchEntire(raw.trim())?.groupValues?.get(1) ?: return null
    // The same fixed driver omits Core0 when num_irqs == 1, including its whitespace-zero form.
    ROCKCHIP_SINGLE.matchEntire(body)?.let { match ->
        val text = match.groupValues[1]
        val value = if (text.isEmpty()) 0f else text.toFloatOrNull() ?: return null
        return value.takeIf { it in 0f..100f }?.let { RockchipLoad(listOf(0), listOf(it)) }
    }
    val cores = mutableMapOf<Int, Float>()
    val parts = body.trim().removeSuffix(",").split(',')
    for (part in parts) {
        val match = ROCKCHIP_CORE.matchEntire(part.trim()) ?: return null
        val index = match.groupValues[1].toIntOrNull() ?: return null
        val text = match.groupValues[2]
        val value = if (text.isEmpty()) 0f else text.toFloatOrNull() ?: return null
        if (value !in 0f..100f) return null
        if (cores.put(index, value) != null) return null
    }
    if (cores.isEmpty()) return null
    val ordered = cores.toSortedMap()
    return RockchipLoad(ordered.keys.toList(), ordered.values.toList())
}

/** Samsung scheduler `rq_load`: raw 0–10000 in 0.01% units. */
internal fun parseSamsungRqLoad(raw: String): Float? {
    val value = raw.trim().toIntOrNull() ?: return null
    return (value / 100f).takeIf { value in 0..10000 }
}

internal enum class SamsungLoadPolicy(val id: Int, val label: String) {
    IDLE(0, "idle"), FPS(1, "fps"), RQ(2, "rq"), FPS_RQ(3, "fps-rq hybrid"),
}

/** Fixed npu-scheduler.c emits a menu, with only the selected entry prefixed by '*'. */
internal fun parseSamsungLoadPolicy(raw: String): SamsungLoadPolicy? {
    val scalar = raw.trim().toIntOrNull()
    if (scalar != null) return SamsungLoadPolicy.entries.firstOrNull { it.id == scalar }
    val selected = raw.lineSequence().map(String::trim).filter { it.startsWith('*') }.toList().singleOrNull() ?: return null
    val match = Regex("""\*(\d+)\s+(.+)""").matchEntire(selected) ?: return null
    return SamsungLoadPolicy.entries.firstOrNull {
        it.id == match.groupValues[1].toIntOrNull() && it.label == match.groupValues[2]
    }
}

internal fun parseRawNumber(raw: String): Float? =
    raw.trim().toFloatOrNull()?.takeIf { it.isFinite() }

internal fun excerpt(text: String?): String? =
    text?.trim()?.replace('\n', ' ')?.take(120)?.takeIf { it.isNotEmpty() }

internal fun formatNumber(value: Float): String = String.format(Locale.US, "%.1f", value)

private val ROCKCHIP_HEADER = Regex("""NPU load:\s*(.*)""", RegexOption.IGNORE_CASE)
private val ROCKCHIP_SINGLE = Regex("""(\d{1,3}(?:\.\d+)?)?\s*%""")
private val ROCKCHIP_CORE = Regex("""Core\s*(\d+)\s*:\s*(\d{1,3}(?:\.\d+)?)?\s*%""")
