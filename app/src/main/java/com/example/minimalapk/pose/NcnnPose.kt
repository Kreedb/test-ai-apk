package com.example.minimalapk.pose

import android.content.res.AssetManager

internal object NcnnPose {
    init {
        System.loadLibrary("ncnnpose")
    }

    external fun create(
        assets: AssetManager,
        paramAsset: String,
        modelAsset: String,
        useGpu: Boolean,
    ): Long

    external fun analyze(handle: Long, pixels: IntArray): FloatArray

    external fun isGpuEnabled(handle: Long): Boolean

    external fun close(handle: Long)
}
