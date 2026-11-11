package com.example.minimalapk.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReadinessTest {
    private val device = DeviceProfile(
        sdkInt = 34,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
        gpuAvailable = true,
        npuAvailable = true,
    )

    @Test
    fun cpuSetRunsOnSupportedDevice() {
        assertTrue(checkRuntimeCompatibility(set(), device).ready)
    }

    @Test
    fun oldApiAndUnknownAbiAreReported() {
        val result = checkRuntimeCompatibility(
            set(),
            device.copy(sdkInt = 23, abis = listOf("armeabi-v7a")),
        )

        assertFalse(result.ready)
        assertEquals(2, result.reasons.size)
        assertTrue(result.reasons.any { "API 24" in it })
        assertTrue(result.reasons.any { "ABI" in it })
    }

    @Test
    fun declaredGpuBackendRequiresAvailableGpuDelegate() {
        val result = checkRuntimeCompatibility(set(backend = "gpu"), device.copy(gpuAvailable = false))

        assertFalse(result.ready)
        assertTrue(result.reasons.single().contains("GPU"))
    }

    @Test
    fun mixedBackendFallsBackToCpu() {
        assertTrue(checkRuntimeCompatibility(set(backend = "mixed"), device.copy(gpuAvailable = false)).ready)
    }

    @Test
    fun declaredNpuBackendReportsSdkAbiAndRuntimeGaps() {
        val result = checkRuntimeCompatibility(
            set(backend = "npu"),
            device.copy(sdkInt = 30, abis = listOf("x86_64"), npuAvailable = false),
        )

        assertFalse(result.ready)
        assertTrue(result.reasons.any { "API 31" in it })
        assertTrue(result.reasons.any { "arm64-v8a" in it })
        assertTrue(result.reasons.any { "Qualcomm" in it })
    }

    @Test
    fun litertlmNeedsARuntimeThatIsNotBundled() {
        val result = checkRuntimeCompatibility(
            set(files = listOf(AssetFile("model.litertlm", "https://example.com/m", 10, "a".repeat(64)))),
            device,
        )

        assertFalse(result.ready)
        assertTrue(result.reasons.single().contains("litertlm-android"))
    }

    private fun set(
        backend: String = "cpu",
        files: List<AssetFile> = listOf(
            AssetFile("model.tflite", "https://example.com/model.tflite", 10, "b".repeat(64)),
        ),
    ): AssetSet = AssetSet(
        id = "test-set",
        name = "Test",
        group = "Vision",
        backend = backend,
        licenseName = "Apache-2.0",
        licenseUrl = "https://example.com/LICENSE",
        upstreamUrl = "https://example.com/upstream",
        modelCardUrl = "https://example.com/card",
        files = files,
    )
}
