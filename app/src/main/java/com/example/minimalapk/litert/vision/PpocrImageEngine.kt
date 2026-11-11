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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.example.minimalapk.litert.ImageTaskOutput
import com.example.minimalapk.litert.ImageTaskRequest
import com.example.minimalapk.litert.SingleImageEngine
import com.example.minimalapk.litert.compileImageBackend
import java.io.File
import java.util.Locale

/** The upstream detect/crop/recognize pipeline, returning boxes in the original bitmap's space. */
internal class PpocrImageEngine(directory: File, backend: String) : SingleImageEngine {
    private val compiled = compileImageBackend(backend, "VisionPpocr") { accelerator ->
        val detector = PpocrDetector(File(directory, PpocrDetector.MODEL), accelerator)
        try {
            detector to PpocrRecognizer(File(directory, PpocrRecognizer.MODEL), File(directory, PpocrRecognizer.DICT), accelerator)
        } catch (failure: Throwable) {
            detector.close()
            throw failure
        }
    }

    private data class Line(val box: PpocrDetector.Box, val result: PpocrRecognizer.Recognition, val rotated: Boolean)

    override fun run(request: ImageTaskRequest): ImageTaskOutput {
        val started = System.nanoTime()
        val source = request.bitmap
        val geometry = VisionGeometry.Letterbox(source.width, source.height, PpocrDetector.SIZE)
        val image = Bitmap.createBitmap(PpocrDetector.SIZE, PpocrDetector.SIZE, Bitmap.Config.ARGB_8888)
        val lines: List<Line>
        try {
            Canvas(image).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, null, RectF(geometry.left.toFloat(), geometry.top.toFloat(),
                    (geometry.left + geometry.width).toFloat(), (geometry.top + geometry.height).toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG))
            }
            lines = compiled.runner.first.detect(image).mapNotNull { box ->
                val rotated = box.height >= box.width * 1.5f
                val recognized = compiled.runner.second.recognize(cropResize(image, box, rotated))
                if (recognized.text.isBlank()) null else Line(box, recognized, rotated)
            }
        } finally { image.recycle() }
        val output = source.visionCopy()
        val canvas = Canvas(output)
        val stroke = visionStroke(output.width, Color.RED)
        val textPaint = visionTextPaint(output.width)
        val metrics = lines.mapIndexed { index, line ->
            val box = line.box
            val left = geometry.sourceX(box.x0.toFloat())
            val top = geometry.sourceY(box.y0.toFloat())
            val right = geometry.sourceX((box.x1 + 1).toFloat())
            val bottom = geometry.sourceY((box.y1 + 1).toFloat())
            canvas.drawRect(left, top, right, bottom, stroke)
            canvas.drawText("${index + 1}", left, (top + textPaint.textSize).coerceAtMost(output.height.toFloat()), textPaint)
            mapOf("text" to line.result.text, "confidence" to line.result.confidence,
                "detectionConfidence" to box.score, "box" to listOf(left, top, right, bottom),
                "recognitionRotationDegrees" to if (line.rotated) -90 else 0)
        }
        return ImageTaskOutput(
            bitmap = output,
            text = if (lines.isEmpty()) "No readable text detected." else OcrReadingOrder.lines(lines.map { line ->
                val b = line.box
                OcrDisplayBox(b.x0, b.y0, b.x1 + 1, b.y1 + 1, line.result.text)
            }).joinToString("\n"),
            inferenceMs = (System.nanoTime() - started) / 1e6,
            backend = compiled.backend,
            fallbackReason = compiled.fallbackReason,
            backendDetails = "Detector and recognizer: ${compiled.backend}; connected components and CTC: host CPU.",
            metrics = mapOf("lines" to metrics, "boxCount" to lines.size, "coordinateSpace" to "source bitmap; exclusive right/bottom"),
            details = "PP-OCRv5 axis-aligned boxes; supply an upright image. Tall crops rotate 90° counterclockwise for recognition.\n" +
                lines.mapIndexed { i, line -> String.format(Locale.US, "%d. %s — %.1f%%", i + 1, line.result.text, line.result.confidence * 100) }
                    .joinToString("\n"),
        )
    }

    private fun cropResize(image: Bitmap, box: PpocrDetector.Box, rotate: Boolean): FloatArray {
        val crop = Bitmap.createBitmap(image, box.x0, box.y0, box.width, box.height)
        var oriented = crop
        var resized: Bitmap? = null
        try {
            if (rotate) oriented = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height,
                Matrix().apply { postRotate(-90f) }, true)
            val width = (PpocrRecognizer.H.toFloat() * oriented.width / oriented.height).toInt().coerceIn(1, PpocrRecognizer.W)
            resized = Bitmap.createScaledBitmap(oriented, width, PpocrRecognizer.H, true)
            val pixels = resized.visionPixels()
            // Upstream raw black padding becomes -1 after recognizer normalization.
            val rgb = FloatArray(PpocrRecognizer.W * PpocrRecognizer.H * 3)
            for (y in 0 until PpocrRecognizer.H) for (x in 0 until width) {
                val p = pixels[y * width + x]
                val base = (y * PpocrRecognizer.W + x) * 3
                rgb[base] = ((p ushr 16) and 255).toFloat()
                rgb[base + 1] = ((p ushr 8) and 255).toFloat()
                rgb[base + 2] = (p and 255).toFloat()
            }
            return rgb
        } finally {
            if (resized != null && resized !== oriented && resized !== image) resized.recycle()
            if (oriented !== crop && oriented !== image) oriented.recycle()
            if (crop !== image) crop.recycle()
        }
    }

    override fun close() {
        try { compiled.runner.second.close() } finally { compiled.runner.first.close() }
    }
}
