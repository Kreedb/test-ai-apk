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

/** Model Zoo DM-Count: ImageNet NCHW 512² -> 64² person density; count is its unscaled integral. */
internal class CrowdCounter(file: File, accelerator: Accelerator) : AutoCloseable {
    private val runner = CompiledModelRunner.fromFile(file.absolutePath, accelerator)

    fun count(bitmap: Bitmap): Result {
        runner.writeInput(0, VisionMath.imageNetChw(resizedPixels(bitmap, SIZE)))
        runner.run()
        val density = runner.readOutput(0)
        check(density.size == OUT * OUT)
        return Result(density, sumDensity(density))
    }

    data class Result(val density: FloatArray, val count: Float)
    override fun close() = runner.close()

    companion object {
        const val SIZE = 512
        const val OUT = 64
        fun sumDensity(density: FloatArray): Float {
            require(density.all { it.isFinite() }) { "Non-finite crowd density" }
            // Accumulate before any resizing/heatmap normalization; double avoids a long float sum.
            return density.sumOf { it.toDouble() }.toFloat()
        }
    }
}
