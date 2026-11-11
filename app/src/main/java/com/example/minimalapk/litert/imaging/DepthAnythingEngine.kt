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
import com.example.minimalapk.examples.ImageFit
import com.example.minimalapk.examples.fittedImage
import com.example.minimalapk.litert.ImageTaskOutput
import com.example.minimalapk.litert.ImageTaskRequest
import com.example.minimalapk.litert.SingleImageEngine
import java.io.File

/** Pinned Zoo DA3-Small mono export: portrait 504×896, one depth head, no normal/metric head. */
internal class DepthAnythingEngine(directory: File, backend: String) : SingleImageEngine {
    private val loaded = ImagingModels.compile(directory, backend, "ZooDepthAnything3",
        ImagingGraphSpec("da3_small_gpu_fp16.tflite", listOf(3 * WIDTH * HEIGHT), listOf(WIDTH * HEIGHT)))

    override fun run(request: ImageTaskRequest): ImageTaskOutput {
        loaded.runner.checkOpen()
        val started = System.nanoTime()
        val source = request.bitmap
        // Reuse the app's whole-image fit, not a second rounded crop/mapping implementation.
        val fitted = fittedImage(source, WIDTH, HEIGHT)
        val input = try {
            packImagingRgb(imagingPixels(fitted.bitmap), normalization = ImagingNormalization.IMAGENET)
        } finally { fitted.bitmap.recycle() }
        val depth = loaded.runner.inferImage(input).single()
        require(depth.size == WIDTH * HEIGHT) { "DA3 depth shape mismatch" }
        val sourceDepth = sourceAlignedDepth(depth, fitted.fit)
        val colors = RelativeDepth.pixels(sourceDepth)
        return ImageTaskOutput(
            bitmap = Bitmap.createBitmap(colors, source.width, source.height, Bitmap.Config.ARGB_8888),
            text = "Relative depth · warm = nearer, cool = farther\nSingle-image prediction; not calibrated distance",
            inferenceMs = imagingElapsedMs(started), backend = loaded.backend, fallbackReason = loaded.fallbackReason,
            metrics = mapOf("validDepthFraction" to sourceDepth.count { it.isFinite() && it > 0f }.toFloat() / sourceDepth.size),
            details = "DA3-Small: NCHW/ImageNet 504×896 portrait model. Whole-photo black letterbox; " +
                "inverse fit removes padding before 2–98% disparity/Spectral visualization. " +
                "Other aspect ratios can reduce accuracy through attention to padding. " +
                "Colors are normalized per image, not metres or a cross-frame scale; this artifact outputs depth only.",
        )
    }

    override fun close() = loaded.runner.close()

    companion object {
        const val WIDTH = 504
        const val HEIGHT = 896
    }
}

/** Inverts the exact (possibly fractional) fit at pixel centers; no integer crop/shift. */
internal fun sourceAlignedDepth(depth: FloatArray, fit: ImageFit): FloatArray {
    require(depth.size == fit.width * fit.height)
    return FloatArray(Math.multiplyExact(fit.sourceWidth, fit.sourceHeight)) { i ->
        val x = fit.left + (i % fit.sourceWidth + .5f) * fit.scale - .5f
        val y = fit.top + (i / fit.sourceWidth + .5f) * fit.scale - .5f
        sampleImagingGrid(depth, fit.width, fit.height, x, y)
    }
}
