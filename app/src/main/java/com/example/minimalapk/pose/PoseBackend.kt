package com.example.minimalapk.pose

import android.content.res.AssetManager

internal interface PoseBackend {
    val name: String
    fun analyze(pixels: IntArray): FloatArray
    fun close()
}

internal class NcnnBackend private constructor(private val handle: Long) : PoseBackend {
    override val name: String =
        if (NcnnPose.isGpuEnabled(handle)) "ncnn (Vulkan)" else "ncnn (CPU)"

    override fun analyze(pixels: IntArray): FloatArray = NcnnPose.analyze(handle, pixels)

    override fun close() = NcnnPose.close(handle)

    companion object {
        fun open(assets: AssetManager, useGpu: Boolean): NcnnBackend? {
            val handle = NcnnPose.create(
                assets,
                PoseAnalyzer.PARAM_ASSET,
                PoseAnalyzer.MODEL_ASSET,
                useGpu,
            )
            return if (handle != 0L) NcnnBackend(handle) else null
        }
    }
}

internal class MnnBackend private constructor(
    private val handle: Long,
    override val name: String,
) : PoseBackend {
    override fun analyze(pixels: IntArray): FloatArray = MnnPose.analyze(handle, pixels)

    override fun close() = MnnPose.close(handle)

    companion object {
        fun open(assets: AssetManager, useGpu: Boolean): MnnBackend? {
            if (!MnnPose.available) return null
            val backends = if (useGpu) {
                listOf(
                    MnnPose.BACKEND_OPENCL to "MNN (OpenCL)",
                    MnnPose.BACKEND_VULKAN to "MNN (Vulkan)",
                )
            } else {
                listOf(MnnPose.BACKEND_CPU to "MNN (CPU)")
            }
            for ((type, requested) in backends) {
                // MNN falls back to CPU internally when a requested GPU backend is unavailable,
                // so check the session's real primary backend before accepting it.
                val handle = runCatching {
                    MnnPose.create(assets, PoseAnalyzer.MNN_MODEL_ASSET, type)
                }.getOrDefault(0L)
                if (handle == 0L) continue
                val actual = runCatching { MnnPose.backendType(handle) }.getOrDefault(-1)
                if (useGpu && actual != type) {
                    MnnPose.close(handle)
                    continue
                }
                return MnnBackend(handle, backendName(actual, requested))
            }
            return null
        }

        private fun backendName(type: Int, requested: String): String = when (type) {
            MnnPose.BACKEND_OPENCL -> "MNN (OpenCL)"
            MnnPose.BACKEND_VULKAN -> "MNN (Vulkan)"
            MnnPose.BACKEND_CPU -> "MNN (CPU)"
            else -> requested
        }
    }
}
