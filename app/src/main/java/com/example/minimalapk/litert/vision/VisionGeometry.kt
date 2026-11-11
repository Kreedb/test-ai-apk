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

import kotlin.math.roundToInt

/** Pixel-edge coordinates: right/bottom are exclusive. All geometry is in the supplied bitmap. */
internal data class PixelRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun asList(): List<Int> = listOf(left, top, right, bottom)
}

internal object VisionGeometry {
    /** The face adapters crop exactly this indicated guide, never the whole camera frame. */
    fun centeredFace(width: Int, height: Int): PixelRegion {
        require(width >= 2 && height >= 2) { "Face input must be at least 2 × 2 pixels" }
        val side = (minOf(width, height) * 0.6f).toInt().coerceAtLeast(1)
        val left = (width - side) / 2
        val top = (height - side) / 2
        return PixelRegion(left, top, left + side, top + side)
    }

    /** MiniFASNetV2's selected model crop must include approximately 2.7× the face bounds. */
    fun livenessGuide(crop: PixelRegion): PixelRegion {
        val side = (minOf(crop.width, crop.height) / 2.7f).toInt().coerceAtLeast(1)
        val left = crop.left + (crop.width - side) / 2
        val top = crop.top + (crop.height - side) / 2
        return PixelRegion(left, top, left + side, top + side)
    }

    /** Integer letterbox geometry is shared by the draw and the inverse coordinate mapping. */
    data class Letterbox(val sourceWidth: Int, val sourceHeight: Int, val size: Int) {
        init {
            require(sourceWidth > 0 && sourceHeight > 0 && size > 0)
        }
        private val scale = minOf(size.toDouble() / sourceWidth, size.toDouble() / sourceHeight)
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, size)
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, size)
        val left = (size - width) / 2
        val top = (size - height) / 2
        fun sourceX(x: Float): Float = ((x - left) * sourceWidth / width).coerceIn(0f, sourceWidth.toFloat())
        fun sourceY(y: Float): Float = ((y - top) * sourceHeight / height).coerceIn(0f, sourceHeight.toFloat())
    }
}
