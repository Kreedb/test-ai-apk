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

package com.example.minimalapk.litert.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.example.minimalapk.litert.CompiledModelRunner
import com.example.minimalapk.litert.ImageBackend
import com.example.minimalapk.litert.compileImageBackend
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File

/** Float capacities are checked before use; RF-DETR slots are matched by distinct capacities. */
internal data class ImagingGraphSpec(
    val file: String,
    val inputSizes: List<Int>,
    val outputSizes: List<Int>,
    val reorderable: Boolean = false,
    val gpuFp32: Boolean = false,
)

/**
 * Owns an environment for the entire imaging pipeline, including RF-DETR's two graphs. This keeps
 * the upstream shared-environment fix and all-or-nothing construction without duplicating the
 * common CompiledModelRunner. All graphs close before the environment, also on failed GPU loads.
 */
internal class ImagingModels private constructor(
    private val environment: Environment,
    val graphs: List<CompiledModelRunner>,
    val inputSizes: List<List<Int>>,
    val outputSizes: List<List<Int>>,
) : AutoCloseable {
    private var closed = false

    fun checkOpen() = check(!closed) { "Imaging engine is closed" }

    override fun close() {
        if (closed) return
        closed = true
        graphs.asReversed().forEach { runCatching { it.close() } }
        environment.close()
    }

    companion object {
        fun compile(
            directory: File,
            backend: String,
            tag: String,
            vararg specs: ImagingGraphSpec,
            initialize: (ImagingModels) -> Unit = {},
        ): ImageBackend<ImagingModels> =
            compileImageBackend(if (backend == "mixed") "gpu" else backend, tag) { accelerator ->
                // Check the whole resource set before creating native handles. Hash verification is
                // the catalog/store's job; the engine never changes or downloads model files.
                val files = specs.map { spec ->
                    File(directory, spec.file).also { require(it.isFile && it.length() > 0) { "Missing model: ${spec.file}" } }
                }
                val environment = Environment.create()
                val opened = mutableListOf<CompiledModelRunner>()
                try {
                    val inputs = mutableListOf<List<Int>>()
                    val outputs = mutableListOf<List<Int>>()
                    specs.forEachIndexed { index, spec ->
                        val options = CompiledModel.Options(accelerator)
                        if (accelerator == Accelerator.GPU && spec.gpuFp32) {
                            // Do not swallow precision configuration errors: fallback must be CPU,
                            // not a successful-looking FP16 image from this FP32-required path.
                            options.gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
                        }
                        val runner = CompiledModelRunner.fromFile(files[index].absolutePath, options, environment)
                        opened += runner
                        val ins = runner.inputBuffers.map { it.readFloat().size }
                        val outs = runner.outputBuffers.map { it.readFloat().size }
                        fun compatible(actual: List<Int>, expected: List<Int>) =
                            if (spec.reorderable) actual.sorted() == expected.sorted() else actual == expected
                        require(compatible(ins, spec.inputSizes) && compatible(outs, spec.outputSizes)) {
                            "${spec.file} tensor contract mismatch: inputs=$ins outputs=$outs; " +
                                "expected ${spec.inputSizes} / ${spec.outputSizes}"
                        }
                        inputs += ins
                        outputs += outs
                    }
                    ImagingModels(environment, opened.toList(), inputs, outputs).also(initialize)
                } catch (failure: Throwable) {
                    opened.asReversed().forEach { runCatching { it.close() } }
                    runCatching { environment.close() }
                    throw failure
                }
            }
    }
}

internal fun ImagingModels.inferImage(input: FloatArray): List<FloatArray> {
    checkOpen()
    val runner = graphs.single()
    require(input.size == inputSizes.single().single()) { "Image input tensor size mismatch" }
    runner.writeInput(0, input)
    runner.run()
    // Include readback in timings: run() alone only enqueues GPU work.
    return runner.outputBuffers.map { it.readFloat() }
}

internal fun imagingPixels(bitmap: Bitmap): IntArray {
    require(!bitmap.isRecycled) { "Source image was recycled" }
    return IntArray(Math.multiplyExact(bitmap.width, bitmap.height)).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}

/** Full-frame resize, never center-crop. createScaledBitmap can alias the borrowed source. */
internal fun resizedImagingPixels(source: Bitmap, width: Int, height: Int): IntArray {
    val scaled = Bitmap.createScaledBitmap(source, width, height, true)
    return try { imagingPixels(scaled) } finally { if (scaled !== source) scaled.recycle() }
}

/** Categorical grids use nearest-neighbor; interpolating palette colors invents classes. */
internal fun imagingOverlay(source: Bitmap, colors: IntArray, width: Int, height: Int, alpha: Int = 255): Bitmap {
    require(colors.size == Math.multiplyExact(width, height) && alpha in 0..255)
    val grid = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    var output: Bitmap? = null
    try {
        val result = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
        output = result
        Canvas(result).drawBitmap(grid, null, Rect(0, 0, source.width, source.height), Paint().apply {
            this.alpha = alpha
            // Paint's platform defaults can enable filtering even without FILTER_BITMAP_FLAG.
            isFilterBitmap = false
        })
        return result
    } catch (failure: Throwable) {
        output?.recycle()
        throw failure
    } finally { grid.recycle() }
}

internal fun imagingElapsedMs(started: Long): Double = (System.nanoTime() - started) / 1e6
