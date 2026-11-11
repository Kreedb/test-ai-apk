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
import com.example.minimalapk.litert.ImageTaskOutput
import com.example.minimalapk.litert.ImageTaskRequest
import com.example.minimalapk.litert.SingleImageEngine
import com.example.minimalapk.plugins.SUPER_RESOLUTION_MAX_INPUT_EDGE
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/**
 * Numerical contracts from pinned Zoo models/{edsr/Upscaler,realesrgan/RealEsrganUpscaler}.kt.
 * EDSR takes NCHW; Real-ESRGAN takes NHWC and requires GPU FP32. BOTH return NCHW 512² RGB/255.
 *
 * Full-photo tiling uses the existing #68 working-image bound, with 128² model-specific tiles and
 * real context around disjoint 96² cores. It does not stretch a whole photo into a single square
 * or overpaint tile seams. Publication/press/release/cancel tokens belong to the plugin host.
 */
internal class ZooSuperResolutionEngine(
    directory: File,
    backend: String,
    private val realEsrgan: Boolean,
) : SingleImageEngine {
    private val loaded = ImagingModels.compile(directory, backend, "ZooSuperResolution",
        ImagingGraphSpec(
            if (realEsrgan) "realesr_general_x4v3.tflite" else "edsr.tflite",
            listOf(3 * TILE * TILE), listOf(3 * OUTPUT_TILE * OUTPUT_TILE), gpuFp32 = realEsrgan,
        ))

    override fun run(request: ImageTaskRequest): ImageTaskOutput {
        loaded.runner.checkOpen()
        val started = System.nanoTime()
        val source = request.bitmap
        val scale = minOf(1.0, SUPER_RESOLUTION_MAX_INPUT_EDGE.toDouble() / maxOf(source.width, source.height))
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val pixels = resizedImagingPixels(source, width, height)
        val tiles = imagingTiles(width, height, TILE, HALO)
        val result = Bitmap.createBitmap(width * SCALE, height * SCALE, Bitmap.Config.ARGB_8888)
        try {
            for (tile in tiles) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("Super-resolution cancelled")
                val input = packImagingRgb(imagingTilePixels(pixels, width, height, tile, TILE, HALO),
                    layout = if (realEsrgan) ImagingLayout.NHWC else ImagingLayout.NCHW)
                val output = imagingNchwPixels(loaded.runner.inferImage(input).single(), OUTPUT_TILE * OUTPUT_TILE)
                // Discard halo outputs; every source pixel belongs to exactly one core.
                result.setPixels(output, HALO * SCALE * OUTPUT_TILE + HALO * SCALE, OUTPUT_TILE,
                    tile.left * SCALE, tile.top * SCALE, tile.width * SCALE, tile.height * SCALE)
            }
            return ImageTaskOutput(
                bitmap = result,
                text = "${if (realEsrgan) "Real-ESRGAN" else "EDSR"} ×4 · whole source frame\n" +
                    "${source.width}×${source.height} → working ${width}×${height} → ${result.width}×${result.height}",
                inferenceMs = imagingElapsedMs(started), backend = loaded.backend, fallbackReason = loaded.fallbackReason,
                backendDetails = if (realEsrgan && loaded.backend == "GPU") "GPU FP32 required" else "",
                metrics = mapOf("tiles" to tiles.size, "workingWidth" to width, "workingHeight" to height,
                    "sourceWidth" to source.width, "sourceHeight" to source.height),
                details = "On demand; whole-image working edge ≤$SUPER_RESOLUTION_MAX_INPUT_EDGE, " +
                    "128² input tiles, 16-pixel context halo, border replication, disjoint ×4 outputs. " +
                    "The result must be published only for the host's current source/press token.",
            )
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }

    override fun close() = loaded.runner.close()

    companion object {
        const val TILE = 128
        const val HALO = 16
        const val SCALE = 4
        const val OUTPUT_TILE = TILE * SCALE
    }
}
