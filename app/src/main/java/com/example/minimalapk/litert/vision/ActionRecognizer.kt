/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.minimalapk.litert.vision

import android.graphics.Bitmap
import com.example.minimalapk.litert.CompiledModelRunner
import com.google.ai.edge.litert.Accelerator
import java.io.File

/**
 * Model Zoo's MoViNet-A0 stream: 28 temporal frames + 16 running pooling sums on the host.
 * CPU is intentional: the pinned Android graph's RELU_0_TO_1 fails GPU compilation.
 */
internal class ActionRecognizer(file: File) : AutoCloseable {
    private val runner = CompiledModelRunner.fromFile(file.absolutePath, Accelerator.CPU)
    private val state: MoViNetState

    init {
        try {
            check(runner.inputBuffers.size == 47 && runner.outputBuffers.size == 28) { "Unexpected MoViNet stream graph" }
            var input = 1
            val sizes = IntArray(MoViNetState.DEPTHS.size) { conv ->
                val size = runner.inputBuffers[input].readFloat().size
                repeat(MoViNetState.DEPTHS[conv]) {
                    check(runner.inputBuffers[input++].readFloat().size == size)
                }
                size
            }
            state = MoViNetState(sizes, IntArray(16) { runner.inputBuffers[29 + it].readFloat().size })
            runner.writeInput(46, floatArrayOf(1f)) // output decoupler: must remain one
        } catch (failure: Throwable) {
            runner.close()
            throw failure
        }
    }

    val windowFrame: Int get() = state.frameCount

    fun classify(bitmap: Bitmap): List<Action> {
        try {
            runner.writeInput(0, VisionMath.chw(resizedPixels(bitmap, INPUT_SIZE)))
            runner.writeInput(45, floatArrayOf(state.beginFrame())) // inverse count, not the count itself
            state.temporal.flatten().forEachIndexed { index, floats -> runner.writeInput(index + 1, floats) }
            state.poolSums.forEachIndexed { index, floats -> runner.writeInput(index + 29, floats) }
            runner.run()
            val logits = runner.readOutput(0)
            state.update(List(11) { runner.readOutput(1 + it) }, List(16) { runner.readOutput(12 + it) })
            check(logits.size == Kinetics600Labels.NAMES.size) { "MoViNet must return 600 action logits" }
            return VisionMath.topK(logits, 3).map { Action(it.index, Kinetics600Labels.NAMES[it.index], it.probability) }
        } catch (failure: Throwable) {
            // A failed frame must not advance the count or leave partially updated stream state.
            state.reset()
            throw failure
        }
    }

    override fun close() = runner.close()

    data class Action(val index: Int, val label: String, val score: Float)
    companion object { const val INPUT_SIZE = 172 }
}

/** Pure host recurrence, separated for fixtures that check shift order, sums and session reset. */
internal class MoViNetState(streamSizes: IntArray, poolSizes: IntArray, private val windowFrames: Int = 64) {
    init {
        require(streamSizes.size == DEPTHS.size && poolSizes.size == 16 && windowFrames > 0)
        require(streamSizes.all { it > 0 } && poolSizes.all { it > 0 })
    }
    val temporal = Array(DEPTHS.size) { c -> Array(DEPTHS[c]) { FloatArray(streamSizes[c]) } }
    val poolSums = Array(16) { FloatArray(poolSizes[it]) }
    var frameCount = 0
        private set

    fun beginFrame(): Float {
        if (frameCount == windowFrames) reset()
        frameCount++
        return 1f / frameCount
    }

    fun update(frames: List<FloatArray>, means: List<FloatArray>) {
        require(frames.size == temporal.size && means.size == poolSums.size)
        for (c in temporal.indices) {
            val history = temporal[c]
            require(frames[c].size == history[0].size)
            for (i in 0 until history.lastIndex) history[i] = history[i + 1]
            history[history.lastIndex] = frames[c] // readOutput returns an owned host copy
        }
        for (p in poolSums.indices) {
            require(means[p].size == poolSums[p].size)
            for (i in poolSums[p].indices) poolSums[p][i] += means[p][i]
        }
    }

    fun reset() {
        temporal.forEach { frames -> frames.forEach { it.fill(0f) } }
        poolSums.forEach { it.fill(0f) }
        frameCount = 0
    }

    companion object { val DEPTHS = intArrayOf(2, 2, 2, 4, 2, 2, 4, 2, 2, 2, 4) }
}
