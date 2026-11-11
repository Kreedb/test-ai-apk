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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.minimalapk.litert.ImageBackend
import com.example.minimalapk.litert.ImageTaskOutput
import com.example.minimalapk.litert.ImageTaskRequest
import com.example.minimalapk.litert.SingleImageEngine
import java.io.File

/** Port of pinned Zoo models/rfdetrseg/RfDetrSeg.kt and image/RfDetrSegImageEngine.kt. */
internal class RfDetrSegEngine(directory: File, backend: String) : SingleImageEngine {
    private val math = RfDetrSegMath
    // Load/validate all constants before allocating native graphs. These are learned fp32 assets,
    // not zero substitutes or graph constants: baking them back in triggers ML Drift corruption.
    private fun constant(directory: File, name: String, count: Int) =
        RfDetrSegMath.littleEndianFloats(File(directory, name).readBytes(), count, name)

    private val refpointEmbedding = constant(directory, "refpoint_embed.bin", 100 * 4)
    private val clspos = constant(directory, "clspos.bin", 384)
    private val pospatch = constant(directory, "pospatch.bin", 676 * 384)
    private val queryFeatures = constant(directory, "query_feat.bin", 100 * 256)
    private val loaded: ImageBackend<ImagingModels> = ImagingModels.compile(directory, backend, "ZooRFDETRSeg",
        ImagingGraphSpec("rfdetrseg_graphA_fp16.tflite", listOf(3 * 312 * 312, 384, 676 * 384),
            listOf(676 * 91, 676 * 4, 676 * 256), reorderable = true),
        ImagingGraphSpec("rfdetrseg_graphB_fp16.tflite", listOf(676 * 256, 100 * 4, 100 * 256),
            listOf(100 * 4, 100 * 91, 100 * 78 * 78), reorderable = true),
        initialize = { models ->
            // Initialization belongs inside compileImageBackend: a failed GPU constant write
            // must close BOTH graphs/environment before the common CPU retry.
            models.graphs[0].writeInput(math.slot(models.inputSizes[0], 384), clspos)
            models.graphs[0].writeInput(math.slot(models.inputSizes[0], 676 * 384), pospatch)
            models.graphs[1].writeInput(math.slot(models.inputSizes[1], 100 * 256), queryFeatures)
        })

    private fun inputSlot(graph: Int, count: Int) = math.slot(loaded.runner.inputSizes[graph], count)
    private fun outputSlot(graph: Int, count: Int) = math.slot(loaded.runner.outputSizes[graph], count)

    override fun run(request: ImageTaskRequest): ImageTaskOutput {
        loaded.runner.checkOpen()
        val started = System.nanoTime()
        val a = loaded.runner.graphs[0]
        val b = loaded.runner.graphs[1]
        a.writeInput(inputSlot(0, 3 * 312 * 312), packImagingRgb(resizedImagingPixels(request.bitmap, 312, 312),
            normalization = ImagingNormalization.IMAGENET))
        a.run()
        val encClass = a.readOutput(outputSlot(0, 676 * 91))
        val encDelta = a.readOutput(outputSlot(0, 676 * 4))
        val memory = math.restoreMemory(a.readOutput(outputSlot(0, 676 * 256)))
        val references = math.referencePoints(encClass, encDelta, refpointEmbedding)
        b.writeInput(inputSlot(1, 676 * 256), memory)
        b.writeInput(inputSlot(1, 100 * 4), references)
        b.run()
        val detections = math.decode(b.readOutput(outputSlot(1, 100 * 4)),
            b.readOutput(outputSlot(1, 100 * 91)), b.readOutput(outputSlot(1, 100 * 78 * 78)))
        return ImageTaskOutput(
            bitmap = renderRfDetrInstances(request.bitmap, detections),
            text = if (detections.isEmpty()) "No instances above 50% confidence" else
                detections.mapIndexed { i, d -> "${i + 1}. ${RfDetrSegLabels.name(d.cls)} ${(d.score * 100).toInt()}%" }.joinToString("\n"),
            inferenceMs = imagingElapsedMs(started), backend = loaded.backend, fallbackReason = loaded.fallbackReason,
            backendDetails = "Two graphs; fp32 host-fed embeddings; memory ×0.5 correction",
            metrics = mapOf("instances" to detections.size, "scoreThreshold" to math.SCORE_THRESHOLD,
                "iouThreshold" to math.IOU_THRESHOLD),
            details = "RF-DETR-Seg Nano: NCHW/ImageNet 312², 676 encoder proposals → top-100 queries → " +
                "91 COCO-ID logits and independent full-image 78² masks. Boxes and masks share source coordinates.",
        )
    }

    override fun close() = loaded.runner.close()
}

/** Whole-image normalized boxes and masks are both unsquashed along the same source axes. */
internal fun renderRfDetrInstances(source: Bitmap, detections: List<RfSegDetection>): Bitmap {
    val output = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
    try {
        val canvas = Canvas(output)
        val width = source.width.toFloat()
        val height = source.height.toFloat()
        val imageRect = RectF(0f, 0f, width, height)
        // Paint low-score masks first so the most confident instance remains visible in overlaps.
        for (i in detections.indices.reversed()) {
            val mask = Bitmap.createBitmap(RfDetrSegMath.sourceMaskPixels(detections[i].mask,
                RfDetrSegLabels.colors[i % RfDetrSegLabels.colors.size], source.width, source.height),
                source.width, source.height, Bitmap.Config.ARGB_8888)
            try { canvas.drawBitmap(mask, null, imageRect, null) } finally { mask.recycle() }
        }
        val unit = (width / 640f).coerceIn(.5f, 3f)
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * unit }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * unit; isFakeBoldText = true }
        val labelBackground = Paint()
        for ((i, d) in detections.withIndex()) {
            val color = RfDetrSegLabels.colors[i % RfDetrSegLabels.colors.size]
            box.color = color
            labelBackground.color = color
            val left = ((d.cx - d.width / 2) * width).coerceIn(0f, width)
            val top = ((d.cy - d.height / 2) * height).coerceIn(0f, height)
            val right = ((d.cx + d.width / 2) * width).coerceIn(0f, width)
            val bottom = ((d.cy + d.height / 2) * height).coerceIn(0f, height)
            canvas.drawRect(left, top, right, bottom, box)
            val label = "${i + 1}. ${RfDetrSegLabels.name(d.cls)} ${(d.score * 100).toInt()}%"
            val pad = 3f * unit
            val labelWidth = text.measureText(label) + 2 * pad
            val labelHeight = text.descent() - text.ascent() + 2 * pad
            val lx = left.coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
            val ly = (top - labelHeight).coerceIn(0f, (height - labelHeight).coerceAtLeast(0f))
            canvas.drawRect(lx, ly, minOf(width, lx + labelWidth), minOf(height, ly + labelHeight), labelBackground)
            canvas.drawText(label, lx + pad, ly + pad - text.ascent(), text)
        }
        return output
    } catch (failure: Throwable) {
        output.recycle()
        throw failure
    }
}
