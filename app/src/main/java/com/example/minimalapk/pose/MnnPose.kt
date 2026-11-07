package com.example.minimalapk.pose

import android.content.res.AssetManager
import android.os.Build

internal object MnnPose {
    // Mirror the MNNForwardType enum from MNNForwardType.h.
    const val BACKEND_CPU = 0
    const val BACKEND_OPENCL = 3
    const val BACKEND_VULKAN = 7

    val available: Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } &&
            runCatching { System.loadLibrary("mnnpose") }.isSuccess

    external fun create(assets: AssetManager, modelAsset: String, backendType: Int): Long

    external fun analyze(handle: Long, pixels: IntArray): FloatArray

    external fun backendType(handle: Long): Int

    external fun close(handle: Long)
}
