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

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class TemporalAndFaceTest {
    private fun state(window: Int = 64) = MoViNetState(IntArray(11) { 1 }, IntArray(16) { 1 }, window)
    private fun update(state: MoViNetState, value: Float) = state.update(List(11) { floatArrayOf(value) }, List(16) { floatArrayOf(value * 10) })

    @Test
    fun movinetShiftsOldestFirstAndAccumulatesPerFramePoolingMeans() {
        val state = state()
        assertEquals(1f, state.beginFrame(), 0f)
        update(state, 1f)
        assertEquals(0.5f, state.beginFrame(), 0f)
        update(state, 2f)
        assertArrayEquals(floatArrayOf(1f, 2f), state.temporal[0].map { it[0] }.toFloatArray(), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 2f), state.temporal[3].map { it[0] }.toFloatArray(), 0f)
        assertEquals(30f, state.poolSums[0][0], 0f)
        assertEquals(1f / 3, state.beginFrame(), 0f)
        update(state, 3f)
        assertArrayEquals(floatArrayOf(2f, 3f), state.temporal[0].map { it[0] }.toFloatArray(), 0f)
        assertEquals(60f, state.poolSums[0][0], 0f)
    }

    @Test
    fun movinetWindowAndNewSessionResetBothKindsOfState() {
        val state = state(window = 2)
        repeat(2) { state.beginFrame(); update(state, 3f) }
        assertEquals(1f, state.beginFrame(), 0f)
        assertTrue(state.temporal.all { frames -> frames.all { it.all { v -> v == 0f } } })
        assertTrue(state.poolSums.all { it.all { v -> v == 0f } })
        val nextSession = state()
        assertEquals(0, nextSession.frameCount)
        assertEquals(1f, nextSession.beginFrame(), 0f)
    }

    @Test
    fun actionLabelsExactlyMatchThePinnedSixHundredClassSequence() {
        assertEquals(600, Kinetics600Labels.NAMES.size)
        val hash = MessageDigest.getInstance("SHA-256").digest(Kinetics600Labels.NAMES.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("5d9a560f98fcfed08f6129dd498f97e043edf729efb3c02dd96b482d2970f9c0", hash)
    }

    @Test
    fun livenessUsesTheLiveClassArgmaxNotAnInventedHalfProbabilityThreshold() {
        val result = LivenessDetector.decode(floatArrayOf(0.3f, 0.4f, 0.3f))
        assertTrue(result.isLive)
        assertEquals(0.4f, result.liveScore, 0f)
        assertFalse(LivenessDetector.decode(floatArrayOf(0.6f, 0.3f, 0.1f)).isLive)
    }

    @Test
    fun sixDimensionalRotationKeepsAxisConventionsAndRejectsDegeneratePose() {
        val identity = HeadPoseEstimator.decode(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f))
        assertEquals(0f, identity.yaw, 1e-5f)
        assertEquals(0f, identity.pitch, 1e-5f)
        assertEquals(0f, identity.roll, 1e-5f)
        val roll = HeadPoseEstimator.decode(floatArrayOf(0f, 3f, 0f, -4f, 0f, 0f))
        assertEquals(90f, roll.roll, 1e-5f)
        val yaw = HeadPoseEstimator.decode(floatArrayOf(0.8660254f, 0f, -0.5f, 0f, 1f, 0f))
        assertEquals(30f, yaw.yaw, 1e-4f)
        assertThrows(IllegalArgumentException::class.java) { HeadPoseEstimator.decode(FloatArray(6)) }
    }
}
