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
 * DA3's depth→disparity→2/98-percentile→inverted Spectral rendering, adapted from the pinned
 * Zoo models/da3/DA3Predictor.kt. Inputs are already resampled to source pixels (padding excluded).
 * No metric scale, camera intrinsics, normals or point cloud are invented from this depth-only
 * artifact. Invalid/nonpositive depths render black and do not bias the percentile range.
 */
internal object RelativeDepth {
    fun pixels(depth: FloatArray): IntArray {
        require(depth.isNotEmpty())
        val disparity = FloatArray(depth.size) { i ->
            val d = depth[i]
            if (d.isFinite() && d > 0f) (1f / d).takeIf { it.isFinite() } ?: 0f else 0f
        }
        // Avoid boxing a full-resolution image into millions of Float objects.
        val sorted = FloatArray(disparity.size)
        var validCount = 0
        for (value in disparity) if (value > 0f) sorted[validCount++] = value
        require(validCount > 0) { "Depth Anything returned no valid positive depth" }
        java.util.Arrays.sort(sorted, 0, validCount)
        val low = sorted[(.02f * (validCount - 1)).toInt()]
        val high = sorted[(.98f * (validCount - 1)).toInt()]
        return IntArray(depth.size) { i ->
            if (disparity[i] <= 0f) 0xff000000.toInt() else {
                // A constant field carries no near/far information; show the neutral midpoint.
                val value = if (high > low) 1f - ((disparity[i] - low) / (high - low)).coerceIn(0f, 1f) else .5f
                spectral[(value * 255f).toInt().coerceIn(0, 255)]
            }
        }
    }

    // matplotlib Spectral, 256 RGB entries, as embedded in the pinned Google AI Edge wrapper.
    private const val LUT =
        "9e0142a00343a20643a40844a70b44a90d45ab0f45ad1246" +
        "af1446b11747b41947b61b48b81e48ba2049bc2249be254a" +
        "c1274ac32a4bc52c4bc72e4cc9314ccb334dcd364dd0384e" +
        "d23a4ed43d4fd63f4fd7414ed8434ed9444dda464ddc484c" +
        "dd4a4cde4c4bdf4e4be1504be2514ae3534ae45549e55749" +
        "e75948e85b48e95c47ea5e47eb6046ed6246ee6445ef6645" +
        "f06744f26944f36b43f46d43f47044f57245f57547f57748" +
        "f67a49f67c4af67f4bf7814cf7844ef8864ff88950f88c51" +
        "f98e52f99153f99355fa9656fa9857fa9b58fb9d59fba05b" +
        "fba35cfca55dfca85efcaa5ffdad60fdaf62fdb163fdb365" +
        "fdb567fdb768fdb96afdbb6cfdbd6dfdbf6ffdc171fdc372" +
        "fdc574fdc776fec877feca79fecc7bfece7cfed07efed27f" +
        "fed481fed683fed884feda86fedc88fede89fee08bfee18d" +
        "fee28ffee491fee593fee695fee797fee999feea9bfeeb9d" +
        "feec9ffeeda1feefa3fff0a6fff1a8fff2aafff3acfff5ae" +
        "fff6b0fff7b2fff8b4fffab6fffbb8fffcbafffdbcfffebe" +
        "ffffbefefebdfdfebbfcfebafbfdb8fafdb7f9fcb5f8fcb4" +
        "f7fcb2f6fbb0f5fbaff4faadf3faacf2faaaf1f9a9f0f9a7" +
        "eff9a6eef8a4edf8a3ecf7a1ebf7a0eaf79ee9f69de8f69b" +
        "e7f59ae6f598e4f498e1f399dff299ddf19adaf09ad8ef9b" +
        "d6ee9bd3ed9cd1ed9ccfec9dcdeb9dcaea9ec8e99ec6e89f" +
        "c3e79fc1e6a0bfe5a0bce4a0bae3a1b8e2a1b5e1a2b3e0a2" +
        "b1dfa3aedea3acdda4aadca4a7dba4a4daa4a2d9a49fd8a4" +
        "9cd7a499d6a497d5a494d4a491d3a48fd2a48cd1a489d0a4" +
        "86cfa584cea581cda57ecca57ccaa579c9a576c8a574c7a5" +
        "71c6a56ec5a56bc4a569c3a566c2a564c0a662bda760bba8" +
        "5eb9a95cb7aa5ab4ab58b2ac56b0ad54aead52abae50a9af" +
        "4ea7b04ba4b149a2b247a0b3459eb4439bb54199b63f97b7" +
        "3d95b83b92b93990ba378ebb358bbc3389bd3387bc3585bb" +
        "3682ba3880b93a7eb83b7cb73d79b63f77b54175b44273b3" +
        "4471b2466eb1486cb0496aaf4b68ae4d65ad4e63ac5061aa" +
        "525fa9545ca8555aa75758a65956a55b53a45c51a35e4fa2"
    private val spectral: IntArray = run {
        check(LUT.length == 256 * 6)
        IntArray(256) { (255 shl 24) or LUT.substring(it * 6, it * 6 + 6).toInt(16) }
    }
}
