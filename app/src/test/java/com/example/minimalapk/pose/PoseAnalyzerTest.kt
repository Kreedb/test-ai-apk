package com.example.minimalapk.pose

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class PoseAnalyzerTest {
    @Test
    fun triesNnapiFromApi27ThenWebGpuAndXnnpack() {
        assertEquals(
            listOf("NNAPI", "WebGPU", "XNNPACK"),
            PoseAnalyzer.backendNames(Build.VERSION_CODES.O_MR1),
        )
        assertEquals(
            listOf("WebGPU", "XNNPACK"),
            PoseAnalyzer.backendNames(Build.VERSION_CODES.O),
        )
    }

    @Test
    fun manualBackendRunsOnlyTheSelectedProvider() {
        assertEquals(
            listOf(InferenceBackend.WEBGPU),
            PoseAnalyzer.backendPlan(InferenceBackend.WEBGPU, Build.VERSION_CODES.O),
        )
        assertEquals(
            listOf(InferenceBackend.CPU),
            PoseAnalyzer.backendPlan(InferenceBackend.CPU, Build.VERSION_CODES.O),
        )
        assertEquals(
            emptyList<InferenceBackend>(),
            PoseAnalyzer.backendPlan(InferenceBackend.NCNN, Build.VERSION_CODES.O_MR1),
        )
        assertEquals(
            listOf(
                InferenceBackend.NNAPI,
                InferenceBackend.WEBGPU,
                InferenceBackend.XNNPACK,
                InferenceBackend.CPU,
            ),
            PoseAnalyzer.backendPlan(InferenceBackend.AUTO, Build.VERSION_CODES.O_MR1),
        )
    }
}
