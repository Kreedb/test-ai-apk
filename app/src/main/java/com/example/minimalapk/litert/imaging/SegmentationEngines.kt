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
import java.io.File
import java.util.Locale

// Adapted from pinned Zoo models/pidnet/Segmenter.kt and image/PIDNetImageEngine.kt.
internal class PidNetEngine(directory: File, backend: String) : SingleImageEngine {
    private val loaded = ImagingModels.compile(directory, backend, "ZooPIDNet",
        ImagingGraphSpec("pidnet_s.tflite", listOf(3 * 1024 * 1024), listOf(19 * 128 * 128)))

    override fun run(request: ImageTaskRequest): ImageTaskOutput {
        loaded.runner.checkOpen()
        val started = System.nanoTime()
        val input = packImagingRgb(resizedImagingPixels(request.bitmap, 1024, 1024),
            normalization = ImagingNormalization.IMAGENET)
        val classes = imagingArgmax(loaded.runner.inferImage(input).single(), 19, 128 * 128)
        val counts = IntArray(19)
        val colors = IntArray(classes.size) { i ->
            counts[classes[i]]++
            CityscapesLabels.colors[classes[i]]
        }
        val present = counts.indices.filter { counts[it] > 0 }.sortedByDescending { counts[it] }
        val text = present.joinToString("\n") { c ->
            val color = String.format(Locale.ROOT, "#%06X", CityscapesLabels.colors[c] and 0xffffff)
            "$color ${CityscapesLabels.names[c]} ${String.format(Locale.ROOT, "%.1f", 100f * counts[c] / classes.size)}%"
        }
        val result = imagingOverlay(request.bitmap, colors, 128, 128, alpha = 130)
        return ImageTaskOutput(
            bitmap = result, text = "Cityscapes (19 classes)\n$text", inferenceMs = imagingElapsedMs(started),
            backend = loaded.backend, fallbackReason = loaded.fallbackReason,
            metrics = mapOf("classCoverage" to present.associate { CityscapesLabels.names[it] to counts[it].toFloat() / classes.size }),
            details = "PIDNet-S: RGB/ImageNet NCHW 1024×1024 → 19×128×128 logits; " +
                "whole image resized, nearest-neighbor class overlay mapped back to source coordinates.",
        )
    }

    override fun close() = loaded.runner.close()
}

// Adapted from pinned Zoo models/twinlite/TwinLiteSegmenter.kt and image/RealtimeImageTasks.kt.
internal class TwinLiteEngine(directory: File, backend: String) : SingleImageEngine {
    private val loaded = ImagingModels.compile(directory, backend, "ZooTwinLite",
        ImagingGraphSpec("twinlite.tflite", listOf(3 * 360 * 640), listOf(2 * 360 * 640, 2 * 360 * 640)))

    override fun run(request: ImageTaskRequest): ImageTaskOutput {
        loaded.runner.checkOpen()
        val started = System.nanoTime()
        val outputs = loaded.runner.inferImage(packImagingRgb(resizedImagingPixels(request.bitmap, 640, 360)))
        // Pinned artifact output 0 = drivable area; output 1 = lane line (same shapes).
        val road = imagingArgmax(outputs[0], 2, 360 * 640)
        val lanes = imagingArgmax(outputs[1], 2, 360 * 640)
        val colors = IntArray(road.size) { i ->
            when {
                lanes[i] == 1 -> 0xd9ff3030.toInt()
                road[i] == 1 -> 0x7400d060
                else -> 0
            }
        }
        return ImageTaskOutput(
            bitmap = imagingOverlay(request.bitmap, colors, 640, 360),
            text = "Green: drivable area · Red: lane lines", inferenceMs = imagingElapsedMs(started),
            backend = loaded.backend, fallbackReason = loaded.fallbackReason,
            metrics = mapOf("drivableFraction" to road.count { it == 1 }.toFloat() / road.size,
                "laneFraction" to lanes.count { it == 1 }.toFloat() / lanes.size),
            details = "TwinLiteNet: NCHW RGB/255 640×360; two binary argmax heads; " +
                "source-aligned whole-image visualization, not a driving instruction.",
        )
    }

    override fun close() = loaded.runner.close()
}

// Adapted from pinned Zoo models/{ormbg/BgRemover,modnet/Matter}.kt and image/RestoreEngines.kt.
internal class MattingEngine(directory: File, backend: String, private val portrait: Boolean) : SingleImageEngine {
    private val size = if (portrait) 512 else 1024
    private val loaded = ImagingModels.compile(directory, backend, if (portrait) "ZooMODNet" else "ZooOrmbg",
        ImagingGraphSpec(if (portrait) "modnet.tflite" else "ormbg.tflite", listOf(3 * size * size), listOf(size * size)))

    override fun run(request: ImageTaskRequest): ImageTaskOutput {
        loaded.runner.checkOpen()
        val started = System.nanoTime()
        val source = request.bitmap
        val output = loaded.runner.inferImage(packImagingRgb(resizedImagingPixels(source, size, size),
            normalization = if (portrait) ImagingNormalization.SIGNED else ImagingNormalization.UNIT)).single()
        requireImagingFloats(output, size * size, "${if (portrait) "MODNet" else "ormbg"} alpha")
        // Retain full matte resolution; the sample's 256-square preview discarded hair detail.
        val alpha = if (portrait) output else normalizeOrmbgMatte(output)
        val result = imagingCutoutPixels(imagingPixels(source), source.width, source.height, alpha, size, size)
        return ImageTaskOutput(
            bitmap = Bitmap.createBitmap(result, source.width, source.height, Bitmap.Config.ARGB_8888),
            text = if (portrait) "Portrait cutout · soft transparent alpha" else "Foreground cutout · transparent alpha",
            inferenceMs = imagingElapsedMs(started), backend = loaded.backend, fallbackReason = loaded.fallbackReason,
            metrics = mapOf("meanAlpha" to result.sumOf { (it ushr 24).toDouble() } / (255.0 * result.size)),
            details = if (portrait) "MODNet: NCHW RGB [-1,1] 512×512; soft alpha resized to the source frame, no threshold." else
                "ormbg: NCHW RGB/255 1024×1024; per-frame min/max-normalized alpha resized to the source frame.",
        )
    }

    override fun close() = loaded.runner.close()
}
