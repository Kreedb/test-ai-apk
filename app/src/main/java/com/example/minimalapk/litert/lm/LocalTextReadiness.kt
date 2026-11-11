package com.example.minimalapk.litert.lm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process

internal data class LocalTextDevice(
    val api: Int,
    val abis: List<String>,
    val process64Bit: Boolean,
    val availableBytes: Long,
    val lowMemory: Boolean,
)

/** This is an allocation gate, not a claim that every supported ABI/backend has been tested. */
internal object LocalTextReadiness {
    fun device(context: Context): LocalTextDevice {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return LocalTextDevice(
            Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(), Process.is64Bit(),
            memory.availMem, memory.lowMemory,
        )
    }

    fun reasons(device: LocalTextDevice): List<String> = buildList {
        if (device.api < 24) add("LiteRT-LM 0.19.0 需要 API 24+，当前 API ${device.api}")
        if (!device.process64Bit || device.abis.none { it in setOf("arm64-v8a", "x86_64") }) {
            add("LiteRT-LM JNI 仅打包 arm64-v8a / x86_64，当前进程或 ABI 不支持")
        }
        if (device.lowMemory) add("系统处于低内存状态，请关闭其它模型后重试")
        if (device.availableBytes < LocalTextModels.MIN_AVAILABLE_BYTES) {
            add("此 Qwen 配方需要至少 2560 MiB 当前可用内存（主机 CPU 实测峰值向上取整加 512 MiB）；当前 ${device.availableBytes / (1024 * 1024)} MiB")
        }
    }

    fun requireReady(context: Context) {
        val reasons = reasons(device(context))
        check(reasons.isEmpty()) { reasons.joinToString("；") }
    }
}
