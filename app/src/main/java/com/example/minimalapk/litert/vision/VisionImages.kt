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
import android.graphics.Paint
import android.graphics.RectF

internal fun Bitmap.visionPixels(): IntArray = IntArray(width * height).also {
    getPixels(it, 0, width, 0, 0, width, height)
}

/** createScaledBitmap may return the caller's bitmap. Only recycle owned intermediates. */
internal fun resizedPixels(bitmap: Bitmap, width: Int, height: Int = width): IntArray {
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    return try { scaled.visionPixels() } finally { if (scaled !== bitmap) scaled.recycle() }
}

internal fun Bitmap.visionCopy(): Bitmap = checkNotNull(copy(Bitmap.Config.ARGB_8888, true))

internal fun visionTextPaint(width: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = maxOf(12f, width / 32f)
    setShadowLayer(3f, 0f, 0f, Color.BLACK)
}

internal fun visionStroke(width: Int, colorValue: Int = Color.YELLOW): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorValue
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, width / 240f)
    }

internal fun Canvas.drawRegion(region: PixelRegion, paint: Paint) {
    drawRect(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat(), paint)
}

internal fun densityOverlay(source: Bitmap, density: FloatArray): Bitmap {
    require(density.size == CrowdCounter.OUT * CrowdCounter.OUT)
    val max = (density.maxOrNull() ?: 0f).coerceAtLeast(1e-5f)
    val pixels = IntArray(density.size) { i ->
        val v = (density[i] / max).coerceIn(0f, 1f)
        Color.argb((v * 220).toInt(), 255, ((1 - v) * 160).toInt(), 0)
    }
    val heat = Bitmap.createBitmap(pixels, CrowdCounter.OUT, CrowdCounter.OUT, Bitmap.Config.ARGB_8888)
    try {
        return source.visionCopy().also {
            Canvas(it).drawBitmap(heat, null, RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG))
        }
    } finally { heat.recycle() }
}

internal fun matchOverlay(a: Bitmap, b: Bitmap, matches: List<XFeatMatcher.Match>): Bitmap {
    // Fit each source in a bounded panel without changing its aspect ratio.
    val panelWidth = XFeatMatcher.W
    val panelHeight = XFeatMatcher.H
    val output = Bitmap.createBitmap(panelWidth * 2, panelHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(Color.BLACK)
    fun panel(bitmap: Bitmap, offset: Int): RectF {
        val scale = minOf(panelWidth.toFloat() / bitmap.width, panelHeight.toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = offset + (panelWidth - width) / 2
        val top = (panelHeight - height) / 2
        return RectF(left, top, left + width, top + height).also {
            canvas.drawBitmap(bitmap, null, it, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
    val left = panel(a, 0)
    val right = panel(b, panelWidth)
    val paint = visionStroke(output.width)
    for (match in matches) {
        val t = ((match.sim - XFeatMatcher.MIN_COSSIM) / (1 - XFeatMatcher.MIN_COSSIM)).coerceIn(0f, 1f)
        paint.color = Color.argb(200, (255 * (1 - t)).toInt(), 220, 40)
        val x0 = left.left + match.x0 / XFeatMatcher.W * left.width()
        val y0 = left.top + match.y0 / XFeatMatcher.H * left.height()
        val x1 = right.left + match.x1 / XFeatMatcher.W * right.width()
        val y1 = right.top + match.y1 / XFeatMatcher.H * right.height()
        canvas.drawLine(x0, y0, x1, y1, paint)
        canvas.drawCircle(x0, y0, 3f, paint)
        canvas.drawCircle(x1, y1, 3f, paint)
    }
    return output
}
