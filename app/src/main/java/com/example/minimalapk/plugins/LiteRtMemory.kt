package com.example.minimalapk.plugins

import android.app.ActivityManager
import android.content.Context

internal data class LiteRtMemorySnapshot(val availableBytes: Long, val lowMemory: Boolean)

/** Host CPU RAM++ peak: 3,090,028 KiB (vision-fixtures/image-tagging-pipeline.json).
 * Round UP to 3 GiB plus 512 MiB headroom. This is a conservative admission threshold, not
 * a measured Android/GPU requirement or a guarantee against native allocation failure.
 */
internal object LiteRtMemory {
    const val RAM_MEASURED_PEAK_BYTES = 3_090_028L * 1024
    const val RAM_REQUIRED_AVAILABLE_BYTES = 3L * 1024 * 1024 * 1024 + 512L * 1024 * 1024

    fun snapshot(context: Context): LiteRtMemorySnapshot? = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        LiteRtMemorySnapshot(info.availMem, info.lowMemory).takeIf { it.availableBytes > 0 }
    }.getOrNull()

    fun blockingReason(task: String, memory: LiteRtMemorySnapshot?): String? {
        val required = if (task == "image-tagging") RAM_REQUIRED_AVAILABLE_BYTES else null
        if (memory?.lowMemory == true) return "系统报告低内存；当前可用 ${liteRtSize(memory.availableBytes)}" +
            (required?.let { "；RAM++ 加载门槛 ${liteRtSize(it)}" } ?: "") + "，暂不加载模型"
        if (required == null) return null
        if (memory == null || memory.availableBytes <= 0) return "RAM++ 加载需至少 ${liteRtSize(required)} 当前可用内存；无法读取系统内存状态，暂不加载"
        if (memory.availableBytes < required) return "RAM++ 加载需至少 ${liteRtSize(required)} 当前可用内存；系统当前报告 ${liteRtSize(memory.availableBytes)}，暂不加载"
        return null
    }

    fun description(task: String, memory: LiteRtMemorySnapshot?): String? {
        if (task != "image-tagging") return blockingReason(task, memory)
        return (blockingReason(task, memory) ?: "系统当前报告可用 ${liteRtSize(checkNotNull(memory).availableBytes)}；满足加载门槛") +
            "。门槛依据 Linux CPU 实测峰值 ${liteRtSize(RAM_MEASURED_PEAK_BYTES)} 向上取整并预留 512 MiB；不是 Android/GPU 成功或剩余内存保证，实际分配仍可能失败。"
    }
}
