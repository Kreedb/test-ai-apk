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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.example.minimalapk.litert.ImageBackend
import com.example.minimalapk.litert.ImageTaskOutput
import com.example.minimalapk.litert.ImageTaskRequest
import com.example.minimalapk.litert.SingleImageEngine
import com.example.minimalapk.litert.compileImageBackend
import java.io.File
import java.util.Locale

/**
 * Algorithm-only adapters for #71's twelve pinned Model Zoo tasks, adapted from
 * google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8.
 *
 * [directory] in [create] contains the verified files for exactly one catalog task. The host owns
 * scheduling and catches run/compile failures. Confine create/run/close to one serial dispatcher.
 * Keep MoViNet alive across sequential frames; create a new engine for each camera/session change.
 * Inputs are upright software bitmaps, owned by the caller; returned bitmaps are caller-owned.
 * For matching, bitmap is reference and secondaryBitmap is current. For face tasks, secondaryBitmap
 * optionally contains an explicitly selected crop (MiniFASNet: include 2.7× face context). Otherwise
 * the adapter uses, draws and reports a centered square 60% of the shorter frame dimension.
 */
internal object VisionTasks {
    val ids: Set<String> = setOf(
        "object-detection", "video-action-recognition", "face-liveness-anti-spoofing",
        "head-pose-estimation", "crowd-counting", "dense-feature-visualization", "image-matching",
        "image-tagging", "image-quality", "image-classification", "fine-grained-classification", "ocr",
    )

    @Suppress("UNUSED_PARAMETER") // Common factory boundary; downloaded-file runners need no Context.
    fun create(taskId: String, context: Context, directory: File, backend: String): SingleImageEngine {
        require(taskId in ids) { "Unknown vision task: $taskId" }
        require(backend in setOf("cpu", "gpu", "mixed")) { "Vision Zoo backend must be cpu, gpu or mixed: $backend" }
        // Check before attempting any compilation: missing assets are not GPU fallback failures.
        files.getValue(taskId).forEach { name ->
            check(File(directory, name).isFile) { "Download $taskId first: missing $name" }
        }
        val ordinaryBackend = if (backend == "mixed") "gpu" else backend
        val engine = when (taskId) {
            "object-detection" -> wrap(compileImageBackend(ordinaryBackend, "VisionRfDetr") { RfDetr(directory, it) }) { runner, request ->
                val detections = runner.detect(request.bitmap)
                val output = request.bitmap.visionCopy()
                val canvas = Canvas(output)
                val stroke = visionStroke(output.width, Color.GREEN)
                val paint = visionTextPaint(output.width)
                val boxes = detections.map { box ->
                    val left = (box.cx - box.w / 2).coerceIn(0f, 1f) * output.width
                    val top = (box.cy - box.h / 2).coerceIn(0f, 1f) * output.height
                    val right = (box.cx + box.w / 2).coerceIn(0f, 1f) * output.width
                    val bottom = (box.cy + box.h / 2).coerceIn(0f, 1f) * output.height
                    val label = COCO_LABELS[box.cls]
                    canvas.drawRect(left, top, right, bottom, stroke)
                    canvas.drawText("$label ${percent(box.score)}", left, maxOf(paint.textSize, top), paint)
                    mapOf("classId" to box.cls, "label" to label, "score" to box.score, "box" to listOf(left, top, right, bottom))
                }
                Rendered(bitmap = output,
                    text = if (detections.isEmpty()) "No objects above ${percent(RfDetr.SCORE_THRESH)} confidence."
                        else detections.joinToString("\n") { "${COCO_LABELS[it.cls]} — ${percent(it.score)}" },
                    metrics = mapOf("detections" to boxes, "scoreThreshold" to RfDetr.SCORE_THRESH,
                        "coordinateSpace" to "source bitmap; exclusive right/bottom"),
                    details = "RF-DETR Nano: graph A → top-300 proposals on host → graph B → sigmoid and class-aware NMS (IoU 0.6).")
            }
            "video-action-recognition" -> {
                // Honor the pinned CPU-only constraint instead of probing a known broken GPU op.
                val reason = if (backend == "cpu") null else "Pinned MoViNet graph is CPU-only on LiteRT 2.2.0: unsupported GPU RELU_0_TO_1."
                var frames = 0L
                wrap(ImageBackend(ActionRecognizer(File(directory, "movinet_a0_stream.tflite")), "CPU", reason)) { runner, request ->
                    val actions = runner.classify(request.bitmap)
                    frames++
                    Rendered(text = actions.joinToString("\n") { "${it.label} — ${percent(it.score)}" },
                        metrics = mapOf("topActions" to actions.map { mapOf("index" to it.index, "label" to it.label, "score" to it.score) },
                            "streamFrames" to frames, "windowFrame" to runner.windowFrame, "windowFrames" to 64),
                        backendDetails = "CPU graph; temporal shift registers and pooling sums retained across frames, renewed every 64 frames.",
                        details = "MoViNet Kinetics-600 running action probabilities. A new engine starts with zero state; use sequential frames from one session.")
                }
            }
            "face-liveness-anti-spoofing" -> LivenessImageEngine(directory, ordinaryBackend)
            "head-pose-estimation" -> HeadPoseImageEngine(directory, ordinaryBackend)
            "crowd-counting" -> wrap(compileImageBackend(ordinaryBackend, "VisionCrowd") {
                CrowdCounter(File(directory, "dmcount.tflite"), it)
            }) { runner, request ->
                val result = runner.count(request.bitmap)
                Rendered(bitmap = densityOverlay(request.bitmap, result.density),
                    text = String.format(Locale.US, "Estimated people: %.2f\nOrange/red intensity shows relative crowd density.", result.count),
                    metrics = mapOf("count" to result.count, "density" to result.density.toList(), "densityShape" to listOf(64, 64),
                        "inputShape" to listOf(1, 3, 512, 512), "countScale" to 1f),
                    details = "Count = sum of the raw 64×64 density map before display normalization. Input is stretched to 512×512; " +
                        "the displayed heatmap is resized back to the source with no count multiplier.")
            }
            "dense-feature-visualization" -> wrap(compileImageBackend(ordinaryBackend, "VisionDino") {
                Dinov2Features(File(directory, "dinov2_s_fp16.tflite"), it)
            }) { runner, request ->
                val features = runner.featureMap(request.bitmap)
                val output = try {
                    Bitmap.createScaledBitmap(features, request.bitmap.width, request.bitmap.height, false)
                } catch (failure: Throwable) {
                    features.recycle()
                    throw failure
                }
                if (features !== output) features.recycle()
                Rendered(bitmap = output, text = "DINOv2 patch features → PCA RGB. Similar colors suggest similar regions within this image.",
                    metrics = mapOf("patchGrid" to listOf(32, 32), "featureDimension" to 384),
                    details = "RGB encodes the first three principal components, independently min/max scaled. " +
                        "Colors are not semantic labels and are not comparable across separate images.")
            }
            "image-matching" -> wrap(compileImageBackend(ordinaryBackend, "VisionXfeat") {
                XFeatMatcher(File(directory, "xfeat_fp16.tflite"), it)
            }) { runner, request ->
                val current = requireNotNull(request.secondaryBitmap) { "Choose a reference and a current image for XFeat matching." }
                val a = runner.extract(request.bitmap)
                val b = runner.extract(current)
                val matches = XFeatMatcher.match(a, b)
                Rendered(bitmap = matchOverlay(request.bitmap, current, matches),
                    text = "${matches.size} mutual feature matches · reference left, current right. Greener lines have higher descriptor similarity.",
                    metrics = mapOf("matchCount" to matches.size, "keypointsReference" to a.xs.size, "keypointsCurrent" to b.xs.size,
                        "matches" to matches.map { mapOf("referenceX" to it.x0 * request.bitmap.width / XFeatMatcher.W,
                            "referenceY" to it.y0 * request.bitmap.height / XFeatMatcher.H,
                            "currentX" to it.x1 * current.width / XFeatMatcher.W,
                            "currentY" to it.y1 * current.height / XFeatMatcher.H, "similarity" to it.sim) }),
                    details = "XFeat grayscale instance norm, 5×5 keypoint NMS, top-800 descriptors and mutual-nearest neighbors ≥0.82 cosine. " +
                        "Matched points are not a geometrically verified alignment.")
            }
            "image-tagging" -> {
                val loaded = compileImageBackend(if (backend == "cpu") "cpu" else "mixed", "VisionRam") { RamTagger(directory, it) }
                wrap(loaded) { runner, request ->
                    val tags = runner.tag(request.bitmap)
                    Rendered(text = if (tags.isEmpty()) "No RAM++ tags above their per-class thresholds."
                        else "${tags.size} tags above model thresholds:\n" + tags.joinToString("\n") { "${it.name} — ${percent(it.probability)}" },
                        metrics = mapOf("tags" to tags.map { mapOf("index" to it.index, "label" to it.name,
                            "score" to it.probability, "threshold" to it.threshold) }),
                        backendDetails = if (loaded.backend == "CPU") "All four graphs: CPU."
                            else "Swin stages 0–2 and tag head: GPU; stage 3 and reweight: CPU.",
                        details = "RAM++ runs all four graphs and the pinned 4585-tag list/threshold table; displays at most 40 tags.")
                }
            }
            "image-quality" -> wrap(compileImageBackend(ordinaryBackend, "VisionNima") {
                NimaScorer(File(directory, "nima_aesthetic_fp16.tflite"), it)
            }) { runner, request ->
                val distribution = runner.score(request.bitmap)
                val mean = NimaScorer.meanScore(distribution)
                Rendered(text = String.format(Locale.US, "Aesthetic score: %.2f / 10\n", mean) +
                    distribution.mapIndexed { i, value -> "${i + 1}: ${percent(value)}" }.joinToString(" · "),
                    metrics = mapOf("meanScore" to mean, "distribution" to distribution.toList()),
                    details = "NIMA aesthetic (AVA) predicts human aesthetic ratings. Mean = Σ rating × probability for bins 1–10; " +
                        "this is not a sharpness or technical-quality ground truth.")
            }
            "image-classification" -> wrap(compileImageBackend(ordinaryBackend, "VisionRwkv") {
                VrwkvClassifier(File(directory, "vrwkv_s_fp16.tflite"), File(directory, "imagenet_classes.txt"), it)
            }) { runner, request -> classification(runner.classify(request.bitmap), "Vision-RWKV-S · ImageNet-1K; short edge 256, center crop 224.") }
            "fine-grained-classification" -> wrap(compileImageBackend(ordinaryBackend, "VisionPlant") {
                PlantClassifier(File(directory, "plantnet.tflite"), File(directory, "plantnet300K_species_id_2_name.json"), it)
            }) { runner, request -> classification(runner.classify(request.bitmap), "PlantNet-300K · 1081 species; centered square crop. Predictions are limited to the training species.") }
            "ocr" -> PpocrImageEngine(directory, ordinaryBackend)
            else -> error("Unknown vision task: $taskId")
        }
        return object : SingleImageEngine {
            private var closed = false
            override fun run(request: ImageTaskRequest): ImageTaskOutput {
                check(!closed) { "$taskId engine is closed" }
                check(!request.bitmap.isRecycled && request.secondaryBitmap?.isRecycled != true) { "Input bitmap has been recycled" }
                return engine.run(request)
            }
            override fun close() {
                if (!closed) {
                    closed = true
                    engine.close()
                }
            }
        }
    }

    private fun percent(value: Float): String = String.format(Locale.US, "%.1f%%", value * 100)

    private fun classification(predictions: List<ClassPrediction>, details: String): Rendered = Rendered(
        text = predictions.joinToString("\n") { "${it.label} — ${percent(it.score)}" },
        metrics = mapOf("top5" to predictions.map { mapOf("index" to it.index, "label" to it.label, "score" to it.score) }),
        details = "$details Softmax is normalized over every class; low-confidence top-k entries are candidates, not confirmed identities.",
    )

    private data class Rendered(val bitmap: Bitmap? = null, val text: String,
        val metrics: Map<String, Any?> = emptyMap(), val backendDetails: String = "", val details: String = "")

    private fun <T : AutoCloseable> wrap(loaded: ImageBackend<T>, render: (T, ImageTaskRequest) -> Rendered): SingleImageEngine =
        object : SingleImageEngine {
            override fun run(request: ImageTaskRequest): ImageTaskOutput {
                val start = System.nanoTime()
                val result = render(loaded.runner, request)
                return ImageTaskOutput(bitmap = result.bitmap, text = result.text, inferenceMs = (System.nanoTime() - start) / 1e6,
                    backend = loaded.backend, fallbackReason = loaded.fallbackReason,
                    backendDetails = result.backendDetails, metrics = result.metrics, details = result.details)
            }
            override fun close() = loaded.runner.close()
        }

    private val files = mapOf(
        "object-detection" to listOf(RfDetr.MODEL_A, RfDetr.MODEL_B),
        "video-action-recognition" to listOf("movinet_a0_stream.tflite"),
        "face-liveness-anti-spoofing" to listOf("silentface.tflite"),
        "head-pose-estimation" to listOf("6drepnet.tflite"),
        "crowd-counting" to listOf("dmcount.tflite"),
        "dense-feature-visualization" to listOf("dinov2_s_fp16.tflite"),
        "image-matching" to listOf("xfeat_fp16.tflite"),
        "image-tagging" to listOf("ram_swin_s012_fp16.tflite", "ram_stage3_tail_fp16.tflite", "ram_reweight_fp16.tflite",
            "ram_taghead_fp16.tflite", "ram_tag_list.txt", "ram_tag_threshold.bin"),
        "image-quality" to listOf("nima_aesthetic_fp16.tflite"),
        "image-classification" to listOf("vrwkv_s_fp16.tflite", "imagenet_classes.txt"),
        "fine-grained-classification" to listOf("plantnet.tflite", "plantnet300K_species_id_2_name.json"),
        "ocr" to listOf(PpocrDetector.MODEL, PpocrRecognizer.MODEL, PpocrRecognizer.DICT),
    )
}
