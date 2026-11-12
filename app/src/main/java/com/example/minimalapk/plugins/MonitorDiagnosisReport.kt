package com.example.minimalapk.plugins

import com.example.minimalapk.pose.NpuProbeScheme
import com.example.minimalapk.pose.ProbeEvidence

/** Separate from the bounded overlay: exact reads are kept until this explicit clipboard action.
 * Per-read/run budgets live at the filesystem boundary, with truncation/timeout explicitly recorded.
 */
internal fun monitorDiagnosisReport(
    device: MonitorDeviceInfo,
    output: MonitorOutput,
    settings: SystemMonitorSettings,
): String = buildString {
    appendLine("系统监控 NPU 诊断 v1")
    appendLine("机型: ${device.deviceModel}")
    appendLine("SoC: ${device.soc}")
    appendLine("系统: ${device.android}")
    appendLine("App: ${device.appVersion}")
    appendLine("来源: ${output.npuSource.id}; 刷新: ${settings.refreshMs} ms; FPS 窗口: ${settings.fpsWindowSeconds} s")
    appendLine("相机帧时间(ns, monotonic): ${output.timestampNanos}; 采样开始(ns): ${output.sampledAtNanos ?: "未知"}")
    appendLine("场景请注明：模型全关/空闲，或启用的模型与后端；两次报告数值变化由用户对照。")
    appendLine("CPU 为本应用占设备总 CPU 算力；帧率为实际处理的相机帧流，非传感器标称帧率。")
    appendLine("GPU/NPU 后端名称不证明硬件实际接管。以下为普通 App 只读结果，不上传、不提权。")
    output.samplingFailure?.let { appendLine("采样失败（未完成各来源探测）: $it") }
    output.metricLines.forEach { appendLine(it) }
    output.gpuDetail?.let { appendLine("GPU scope: $it") }
    output.gpuEvidence.forEach { read ->
        appendLine("GPU path: ${read.path}; state: ${read.state.label}; read(ns): ${read.timestampNanos}")
        read.detail?.let { appendLine("detail: $it") }
        read.text?.let { raw ->
            appendLine("raw_begin (${raw.toByteArray(Charsets.UTF_8).size} UTF-8 bytes)")
            append(raw)
            appendLine()
            appendLine("raw_end")
        }
    }
    appendLine()
    val exported = mutableMapOf<ProbeEvidence, Int>()
    for (scheme in NpuProbeScheme.entries) {
        val results = output.npuResults.filter { it.scheme == scheme }
        if (results.isEmpty()) continue
        appendLine("=== ${scheme.id} / ${scheme.label} ===")
        for (result in results) {
            appendLine("path: ${result.sourcePath ?: "无可证节点"}")
            appendLine("state: ${result.state.label}; value: ${result.value ?: "不可用"}; unit: ${result.unit}")
            appendLine("scope: ${result.scope}; sample(ns): ${result.timestampNanos}")
            appendLine("range: " + when {
                result.unit == "%" && scheme == NpuProbeScheme.SAMSUNG_RQ_LOAD -> "0–100%；原值 0–10000，0.01% 单位，请求队列忙碌率"
                result.unit == "%" && scheme == NpuProbeScheme.ROCKCHIP -> "每核 0–100%；value 为已报告核的算术平均"
                scheme == NpuProbeScheme.QUALCOMM_DIAGNOSTIC -> "硬件 ticks；DSP 睡眠累计值，非 NPU 利用率，不换算时钟"
                else -> "单位/范围尚无已验证驱动契约，不解释为百分比"
            })
            result.detail?.let { appendLine("detail: $it") }
            if (result.perCore.isNotEmpty()) appendLine("per_core: ${result.perCore}")
            result.readings.forEach { appendLine("attribute: ${it.label} = ${it.value} ${it.unit}; raw: ${it.raw}") }
        }
        val evidence = results.flatMap { it.evidence }
        if (evidence.isEmpty()) appendLine("未提供来源原文；不可将概要当作完整读取。")
        evidence.forEachIndexed { index, read ->
            appendLine("--- read ${index + 1}: ${read.path} ---")
            val previous = exported[read]
            if (previous != null) {
                appendLine("同一轮已读取，完整原文见 source #$previous（本轮缓存，不重复 I/O）")
                return@forEachIndexed
            }
            val source = exported.size + 1
            exported[read] = source
            appendLine("source #$source")
            appendLine("state: ${read.state.label}; read(ns, monotonic): ${read.timestampNanos}")
            read.detail?.let { appendLine("detail: $it") }
            read.text?.let { raw ->
                appendLine("raw_begin (${raw.toByteArray(Charsets.UTF_8).size} UTF-8 bytes)")
                append(raw)
                // This separator is outside the raw payload; its byte length above disambiguates
                // whether the driver's own text ended with a newline.
                appendLine()
                appendLine("raw_end")
            }
        }
        appendLine()
    }
    appendLine("限额：每文件 4096 字节、每目录 64 项、每轮 256 次操作/128 KiB 原文/200 ms（阻塞读取返回后停止后续 I/O）。")
    appendLine("truncated/timeout 明确表示未读取全部；保留的原文前缀不用于利用率解析。")
}
