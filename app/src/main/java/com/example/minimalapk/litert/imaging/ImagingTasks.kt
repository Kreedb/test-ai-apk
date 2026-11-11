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

import android.content.Context
import com.example.minimalapk.litert.SingleImageEngine
import java.io.File

/**
 * Adapted from litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android/.../image/SingleImageTasks.kt and the nine model wrappers.
 * Model filenames refer to the revision/hash-pinned sets in models/litert-models.tsv.
 *
 * Create, run and close on the host's serialized background worker. Requests are borrowed;
 * outputs are independently owned, whole-source-frame bitmaps. The host owns publication tokens,
 * source-frame identity, cancellation and result recycling (including late SR results).
 */
internal object ImagingTasks {
    val ids: Set<String> = linkedSetOf(
        "semantic-segmentation",
        "lane-detection",
        "super-resolution",
        "image-dehazing",
        "instance-segmentation",
        "background-removal",
        "portrait-matting",
        "super-resolution-real-esrgan",
        "monocular-geometry-estimation",
    )

    @Suppress("UNUSED_PARAMETER") // Kept compatible with the common task factory.
    fun create(taskId: String, context: Context, directory: File, backend: String): SingleImageEngine {
        require(taskId in ids) { "Unknown imaging task: $taskId" }
        require(backend in setOf("cpu", "gpu", "mixed")) {
            "Zoo imaging requires CPU or GPU; NPU needs a verified SoC-specific compiler/dispatch " +
                "package and accelerator residency check (requested: $backend)"
        }
        return when (taskId) {
            "semantic-segmentation" -> PidNetEngine(directory, backend)
            "lane-detection" -> TwinLiteEngine(directory, backend)
            "super-resolution" -> ZooSuperResolutionEngine(directory, backend, realEsrgan = false)
            "image-dehazing" -> DehazeEngine(directory, backend)
            "instance-segmentation" -> RfDetrSegEngine(directory, backend)
            "background-removal" -> MattingEngine(directory, backend, portrait = false)
            "portrait-matting" -> MattingEngine(directory, backend, portrait = true)
            "super-resolution-real-esrgan" -> ZooSuperResolutionEngine(directory, backend, realEsrgan = true)
            "monocular-geometry-estimation" -> DepthAnythingEngine(directory, backend)
            else -> error("Unknown imaging task: $taskId")
        }
    }
}
