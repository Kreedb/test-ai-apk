/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

// Adapted from the five typed_decisions embedding readers in google-ai-edge/litert-samples@
// f9f0e000630e9cdd6d263c4cd80495e4231a2dc8. Changes: consolidated mmap/bulk lookup, strict size/id
// checks, reusable complete-window writes and releasing mapping references on close. See ATTRIBUTION.md.
package com.example.minimalapk.litert.text

import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/**
 * Consolidates the five upstream host table readers. Only requested rows are widened on the heap;
 * the 39–393 MB file stays read-only mmap-backed. Padding looks up a real PAD row, never zeros.
 * Calls overwrite the entire reused result, even after a shorter request. Worker-confined.
 */
internal class MappedEmbeddings(
    file: File,
    val rows: Int,
    val width: Int,
) : AutoCloseable {
    private var values: ShortBuffer?
    private val row: ShortArray
    private var output = FloatArray(0)

    init {
        require(rows > 0 && width > 0 && rows.toLong() * width * 2 <= Int.MAX_VALUE) {
            "Unsupported embedding table shape [$rows, $width]."
        }
        val bytes = rows.toLong() * width * 2
        require(file.isFile && file.length() == bytes) {
            "${file.name}: expected a little-endian float16 [$rows,$width] table ($bytes bytes), " +
                "found ${file.length()} bytes."
        }
        values = FileInputStream(file).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
        row = ShortArray(width)
    }

    fun gather(ids: IntArray, window: Int = ids.size, padId: Int = 0): FloatArray {
        val mapped = checkNotNull(values) { "Embedding table is closed." }
        require(window in 1..512 && ids.size <= window) {
            "${ids.size} tokens do not fit embedding window $window (maximum 512)."
        }
        require(padId in 0 until rows) { "PAD id $padId is outside the embedding table." }
        ids.forEach { require(it in 0 until rows) { "Token id $it is outside the $rows-row table." } }
        if (output.size != window * width) output = FloatArray(window * width)
        for (position in 0 until window) {
            mapped.position((if (position < ids.size) ids[position] else padId) * width)
            mapped.get(row, 0, width)
            val offset = position * width
            for (column in row.indices) output[offset + column] = HALF[row[column].toInt() and 0xffff]
        }
        return output
    }

    /** The VM owns unmapping. Drop both mapping and scratch references when closing. */
    override fun close() {
        values = null
        output = FloatArray(0)
    }

    companion object {
        /** Exact IEEE binary16 expansion, with NumPy's quieting of signaling NaNs. */
        fun halfToFloat(bits: Int): Float {
            val sign = (bits and 0x8000) shl 16
            val exponent = (bits ushr 10) and 31
            val fraction = bits and 1023
            val expanded = when (exponent) {
                0 -> if (fraction == 0) sign else {
                    val shift = Integer.numberOfLeadingZeros(fraction) - 21
                    sign or ((113 - shift) shl 23) or (((fraction shl shift) and 1023) shl 13)
                }
                31 -> sign or 0x7f800000 or (fraction shl 13) or
                    (if (fraction == 0) 0 else 0x400000)
                else -> sign or ((exponent + 112) shl 23) or (fraction shl 13)
            }
            return Float.fromBits(expanded)
        }

        private val HALF = FloatArray(1 shl 16) { halfToFloat(it) }
    }
}
