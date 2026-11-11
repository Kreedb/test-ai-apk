package com.example.minimalapk.models

import android.os.Build
import com.example.minimalapk.litert.lm.LocalTextModels

/** Facts the caller supplies so the JVM tests never touch the Android runtime. */
internal data class DeviceProfile(
    val sdkInt: Int,
    val abis: List<String>,
    val gpuAvailable: Boolean,
    val npuAvailable: Boolean,
)

internal data class RuntimeCompatibility(val reasons: List<String>) {
    val ready: Boolean get() = reasons.isEmpty()
}

private val apkAbis = setOf("arm64-v8a", "x86_64")

/**
 * Download success is not execution proof. This gate reports catalog/device mismatches before a
 * caller tries to compile a model: API floor, packaged ABI, declared backend availability and
 * runtimes the APK does not bundle. It does not inspect operators or run the model.
 */
internal fun checkRuntimeCompatibility(
    set: AssetSet,
    device: DeviceProfile,
): RuntimeCompatibility {
    val reasons = mutableListOf<String>()
    if (device.sdkInt < 24) {
        reasons += "需要 Android 7.0 (API 24) 或更高版本，当前 API ${device.sdkInt}"
    }
    if (device.abis.none { it in apkAbis }) {
        reasons += "当前 APK 只包含 arm64-v8a / x86_64，设备 ABI 不受支持：${device.abis.joinToString()}"
    }
    if (set.backend == "gpu" && !device.gpuAvailable) {
        reasons += "此模型声明需要 GPU，但当前设备没有可用的 LiteRT GPU 后端"
    }
    if (set.backend == "npu") {
        if (device.sdkInt < 31) {
            reasons += "此模型声明需要 NPU：QNN HTP 需要 Android 12+ (API 31)，当前 API ${device.sdkInt}"
        }
        if ("arm64-v8a" !in device.abis) {
            reasons += "此模型声明需要 NPU：QNN HTP 仅支持 arm64-v8a"
        }
        if (!device.npuAvailable) {
            reasons += "此模型声明需要 NPU，但当前设备没有可用的 Qualcomm HTP 运行时"
        }
    }
    if (set.files.any { it.name.endsWith(".litertlm", ignoreCase = true) } && !LocalTextModels.supports(set)) {
        reasons += "已集成 litertlm-android ${LocalTextModels.RUNTIME_VERSION}，但此 .litertlm 资源/后端未验证；" +
            "当前仅支持固定修订的 Qwen2.5-Coder-1.5B CPU 配方"
    }
    return RuntimeCompatibility(reasons)
}

/** Device facts for production callers; GPU/NPU probe results are supplied by the existing backend code. */
internal fun currentDeviceProfile(gpuAvailable: Boolean, npuAvailable: Boolean): DeviceProfile =
    DeviceProfile(
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        gpuAvailable = gpuAvailable,
        npuAvailable = npuAvailable,
    )
