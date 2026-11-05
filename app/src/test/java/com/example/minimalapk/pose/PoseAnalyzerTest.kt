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
}
