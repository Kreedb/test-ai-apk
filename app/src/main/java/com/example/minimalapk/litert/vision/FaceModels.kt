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
import kotlin.math.atan2
import kotlin.math.sqrt

/** Model Zoo MiniFASNetV2: an indicated face ROI -> BGR/255 NCHW 80² -> three probabilities. */
internal class LivenessDetector(file: File, accelerator: Accelerator) : AutoCloseable {
    private val runner = CompiledModelRunner.fromFile(file.absolutePath, accelerator)

    fun detect(faceCrop: Bitmap): Result {
        runner.writeInput(0, VisionMath.chw(resizedPixels(faceCrop, 80), bgr = true))
        runner.run()
        return decode(runner.readOutput(0))
    }

    data class Result(val isLive: Boolean, val liveScore: Float, val probabilities: List<Float>)

    override fun close() = runner.close()

    companion object {
        fun decode(probabilities: FloatArray): Result {
            require(probabilities.size == 3 && probabilities.all { it.isFinite() && it in 0f..1f }) {
                "Invalid MiniFASNetV2 probability output"
            }
            return Result(probabilities[1] >= probabilities[0] && probabilities[1] >= probabilities[2],
                probabilities[1], probabilities.toList())
        }
    }
}

internal data class HeadPose(val yaw: Float, val pitch: Float, val roll: Float)

/** Model Zoo 6DRepNet: RGB/ImageNet NCHW 224², six-dimensional rotation + host Gram-Schmidt. */
internal class HeadPoseEstimator(file: File, accelerator: Accelerator) : AutoCloseable {
    private val runner = CompiledModelRunner.fromFile(file.absolutePath, accelerator)

    fun estimate(faceCrop: Bitmap): HeadPose {
        runner.writeInput(0, VisionMath.imageNetChw(resizedPixels(faceCrop, 224)))
        runner.run()
        return decode(runner.readOutput(0))
    }

    override fun close() = runner.close()

    companion object {
        fun decode(v: FloatArray): HeadPose {
            require(v.size == 6 && v.all { it.isFinite() }) { "Invalid 6DRepNet rotation" }
            // x = norm(v0..2); z = norm(cross(x, v3..5)); y = cross(z, x); R = [x|y|z].
            val x = normalize(floatArrayOf(v[0], v[1], v[2]))
            val z = normalize(cross(x, floatArrayOf(v[3], v[4], v[5])))
            val y = cross(z, x)
            val sy = sqrt(x[0] * x[0] + x[1] * x[1])
            val pitch = Math.toDegrees(atan2(y[2], z[2]).toDouble()).toFloat()
            val yaw = Math.toDegrees(atan2(-x[2], sy).toDouble()).toFloat()
            val roll = Math.toDegrees(atan2(x[1], x[0]).toDouble()).toFloat()
            return HeadPose(yaw, pitch, roll)
        }

        private fun normalize(v: FloatArray): FloatArray {
            val length = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
            require(length > 1e-8f) { "Degenerate 6DRepNet rotation; no reliable pose" }
            return FloatArray(3) { v[it] / length }
        }

        private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
        )
    }
}
