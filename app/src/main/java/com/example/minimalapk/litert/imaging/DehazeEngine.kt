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

/** Full-resolution MCT rendering, adapted from pinned Zoo models/dehaze/Dehazer.kt. */
internal class DehazeEngine(directory: File, backend: String) : SingleImageEngine {
    private val loaded = ImagingModels.compile(directory, backend, "ZooDehazeFormer",
        ImagingGraphSpec("dehazeformer_base.tflite", listOf(3 * 256 * 256), listOf(72 * 256 * 256)))

    override fun run(request: ImageTaskRequest): ImageTaskOutput {
        loaded.runner.checkOpen()
        val started = System.nanoTime()
        val source = request.bitmap
        val curves = loaded.runner.inferImage(packImagingRgb(resizedImagingPixels(source, 256, 256),
            normalization = ImagingNormalization.SIGNED)).single()
        val result = applyMctCurves(imagingPixels(source), source.width, source.height, curves)
        return ImageTaskOutput(
            bitmap = Bitmap.createBitmap(result, source.width, source.height, Bitmap.Config.ARGB_8888),
            text = "DehazeFormer · source-resolution dehazed image",
            inferenceMs = imagingElapsedMs(started), backend = loaded.backend, fallbackReason = loaded.fallbackReason,
            details = "On demand; NCHW RGB [-1,1] 256² → 72-channel MCT curves. " +
                "Host trilinear lookup uses the original pixels and align_corners=true; " +
                "source dimensions, coordinates and alpha are preserved.",
        )
    }

    override fun close() = loaded.runner.close()
}
