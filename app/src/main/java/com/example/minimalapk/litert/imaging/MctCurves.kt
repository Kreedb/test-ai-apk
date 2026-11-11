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

/**
 * Port of pinned Zoo models/dehaze/Dehazer.kt applyCurves(): the 72 channels are NOT RGB.
 * Layout is [3 output channels, 3 input channels, 8 intensity levels, 256, 256].
 * Sum the three trilinear grid_sample(bilinear, border, align_corners=true) lookups per channel,
 * then map [-1,1] to display RGB. Spatial coordinates belong to the full source image.
 */
internal fun applyMctCurves(
    source: IntArray,
    width: Int,
    height: Int,
    curves: FloatArray,
    gridSize: Int = 256,
    levels: Int = 8,
): IntArray {
    require(width > 0 && height > 0 && gridSize > 0 && levels > 1)
    require(source.size == Math.multiplyExact(width, height))
    val plane = Math.multiplyExact(gridSize, gridSize)
    requireImagingFloats(curves, Math.multiplyExact(9 * levels, plane), "DehazeFormer MCT curves")
    val xs = FloatArray(width) { if (width == 1) 0f else it.toFloat() / (width - 1) * (gridSize - 1) }
    val ys = FloatArray(height) { if (height == 1) 0f else it.toFloat() / (height - 1) * (gridSize - 1) }
    val result = IntArray(source.size)
    for (y in 0 until height) {
        val y0 = ys[y].toInt()
        val y1 = (y0 + 1).coerceAtMost(gridSize - 1)
        val fy = ys[y] - y0
        for (x in 0 until width) {
            val x0 = xs[x].toInt()
            val x1 = (x0 + 1).coerceAtMost(gridSize - 1)
            val fx = xs[x] - x0
            val i00 = y0 * gridSize + x0
            val i01 = y0 * gridSize + x1
            val i10 = y1 * gridSize + x0
            val i11 = y1 * gridSize + x1
            val w00 = (1f - fx) * (1f - fy)
            val w01 = fx * (1f - fy)
            val w10 = (1f - fx) * fy
            val w11 = fx * fy
            val p = source[y * width + x]
            var packed = p and (255 shl 24)
            for (outputChannel in 0..2) {
                var sum = 0f
                for (inputChannel in 0..2) {
                    val pixel = (p ushr (16 - inputChannel * 8)) and 255
                    val depth = pixel * (levels - 1f) / 255f
                    val d0 = depth.toInt().coerceAtMost(levels - 1)
                    val d1 = (d0 + 1).coerceAtMost(levels - 1)
                    val fd = depth - d0
                    val base = (outputChannel * 3 + inputChannel) * levels
                    val p0 = (base + d0) * plane
                    val p1 = (base + d1) * plane
                    val s0 = curves[p0 + i00] * w00 + curves[p0 + i01] * w01 +
                        curves[p0 + i10] * w10 + curves[p0 + i11] * w11
                    val s1 = curves[p1 + i00] * w00 + curves[p1 + i01] * w01 +
                        curves[p1 + i10] * w10 + curves[p1 + i11] * w11
                    sum += s0 * (1f - fd) + s1 * fd
                }
                require(sum.isFinite()) { "DehazeFormer curve interpolation overflow" }
                val value = ((sum.coerceIn(-1f, 1f) * .5f + .5f) * 255f + .5f).toInt().coerceIn(0, 255)
                packed = packed or (value shl (16 - outputChannel * 8))
            }
            result[y * width + x] = packed
        }
    }
    return result
}
