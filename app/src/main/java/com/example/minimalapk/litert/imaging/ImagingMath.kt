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

import kotlin.math.floor
import kotlin.math.roundToInt

// Numerical adapters from the pinned Zoo models/{pidnet,twinlite,ormbg,modnet,edsr,realesrgan}.
// Pure Kotlin so channel order, alpha and spatial conventions can be tested without Android.
internal enum class ImagingLayout { NCHW, NHWC }
internal enum class ImagingNormalization { UNIT, SIGNED, IMAGENET }

internal fun packImagingRgb(
    pixels: IntArray,
    layout: ImagingLayout = ImagingLayout.NCHW,
    normalization: ImagingNormalization = ImagingNormalization.UNIT,
): FloatArray {
    require(pixels.isNotEmpty())
    val mean = floatArrayOf(.485f, .456f, .406f)
    val std = floatArrayOf(.229f, .224f, .225f)
    return FloatArray(Math.multiplyExact(pixels.size, 3)).also { output ->
        for (i in pixels.indices) for (c in 0..2) {
            val value = ((pixels[i] ushr (16 - c * 8)) and 255) / 255f
            val normalized = when (normalization) {
                ImagingNormalization.UNIT -> value
                ImagingNormalization.SIGNED -> (value - .5f) / .5f
                ImagingNormalization.IMAGENET -> (value - mean[c]) / std[c]
            }
            output[if (layout == ImagingLayout.NCHW) c * pixels.size + i else i * 3 + c] = normalized
        }
    }
}

internal fun requireImagingFloats(values: FloatArray, size: Int, name: String) {
    require(values.size == size) { "$name: expected $size floats, got ${values.size}" }
    require(values.all { it.isFinite() }) { "$name contains NaN/Infinity; reject this inference" }
}

/** Logits are class-major (NCHW), with the lowest class winning ties. */
internal fun imagingArgmax(logits: FloatArray, classes: Int, pixels: Int): IntArray {
    require(classes > 0 && pixels > 0)
    requireImagingFloats(logits, Math.multiplyExact(classes, pixels), "Segmentation logits")
    return IntArray(pixels) { i ->
        var best = 0
        for (c in 1 until classes) if (logits[c * pixels + i] > logits[best * pixels + i]) best = c
        best
    }
}

/** Both SR artifacts return NCHW, even though Real-ESRGAN takes NHWC. */
internal fun imagingNchwPixels(rgb: FloatArray, pixels: Int): IntArray {
    requireImagingFloats(rgb, Math.multiplyExact(pixels, 3), "Super-resolution RGB")
    fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).toInt()
    return IntArray(pixels) { i ->
        (255 shl 24) or (channel(rgb[i]) shl 16) or
            (channel(rgb[pixels + i]) shl 8) or channel(rgb[2 * pixels + i])
    }
}

/** The ormbg artifact requires per-image min/max normalization; MODNet does not. */
internal fun normalizeOrmbgMatte(matte: FloatArray): FloatArray {
    require(matte.isNotEmpty())
    requireImagingFloats(matte, matte.size, "ormbg alpha")
    val low = matte.minOrNull()!!
    val high = matte.maxOrNull()!!
    val scale = 1f / (high - low + 1e-6f)
    return FloatArray(matte.size) { ((matte[it] - low) * scale).coerceIn(0f, 1f) }
}

/** Pixel-center bilinear sampling with border clamping; callers validate the grid once. */
internal fun sampleImagingGrid(grid: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
    val sx = x.coerceIn(0f, (width - 1).toFloat())
    val sy = y.coerceIn(0f, (height - 1).toFloat())
    val x0 = floor(sx).toInt()
    val y0 = floor(sy).toInt()
    val x1 = (x0 + 1).coerceAtMost(width - 1)
    val y1 = (y0 + 1).coerceAtMost(height - 1)
    val fx = sx - x0
    val fy = sy - y0
    val top = grid[y0 * width + x0] * (1f - fx) + grid[y0 * width + x1] * fx
    val bottom = grid[y1 * width + x0] * (1f - fx) + grid[y1 * width + x1] * fx
    return top * (1f - fy) + bottom * fy
}

/** Retains foreground RGB and multiplies existing source alpha, rather than painting a background. */
internal fun imagingCutoutPixels(
    source: IntArray,
    width: Int,
    height: Int,
    matte: FloatArray,
    matteWidth: Int,
    matteHeight: Int,
): IntArray {
    require(width > 0 && height > 0 && matteWidth > 0 && matteHeight > 0)
    require(source.size == Math.multiplyExact(width, height))
    requireImagingFloats(matte, Math.multiplyExact(matteWidth, matteHeight), "Alpha matte")
    return IntArray(source.size) { i ->
        val x = ((i % width + .5f) * matteWidth / width) - .5f
        val y = ((i / width + .5f) * matteHeight / height) - .5f
        val alpha = sampleImagingGrid(matte, matteWidth, matteHeight, x, y).coerceIn(0f, 1f)
        val sourceAlpha = source[i] ushr 24
        ((alpha * sourceAlpha).roundToInt() shl 24) or (source[i] and 0x00ffffff)
    }
}

/** Real context around disjoint useful cores; unlike the sample's overpainted overlapping tiles. */
internal data class ImagingTile(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun imagingTiles(width: Int, height: Int, tileSize: Int = 128, halo: Int = 16): List<ImagingTile> {
    require(width > 0 && height > 0 && halo >= 0 && tileSize > 2 * halo)
    val core = tileSize - 2 * halo
    return buildList {
        for (y in 0 until height step core) for (x in 0 until width step core) {
            add(ImagingTile(x, y, minOf(core, width - x), minOf(core, height - y)))
        }
    }
}

internal fun imagingTilePixels(
    source: IntArray,
    width: Int,
    height: Int,
    tile: ImagingTile,
    tileSize: Int = 128,
    halo: Int = 16,
): IntArray {
    require(width > 0 && height > 0 && source.size == Math.multiplyExact(width, height))
    require(halo >= 0 && tileSize > 2 * halo && tile.width in 1..(tileSize - 2 * halo) &&
        tile.height in 1..(tileSize - 2 * halo))
    require(tile.left >= 0 && tile.top >= 0 && tile.left + tile.width <= width && tile.top + tile.height <= height)
    return IntArray(tileSize * tileSize) { i ->
        val x = (tile.left - halo + i % tileSize).coerceIn(0, width - 1)
        val y = (tile.top - halo + i / tileSize).coerceIn(0, height - 1)
        source[y * width + x]
    }
}
