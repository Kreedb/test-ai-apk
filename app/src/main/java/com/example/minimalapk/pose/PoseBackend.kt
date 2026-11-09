package com.example.minimalapk.pose

internal interface PoseBackend {
    val name: String
    fun analyze(pixels: IntArray): FloatArray
    fun close()
}
