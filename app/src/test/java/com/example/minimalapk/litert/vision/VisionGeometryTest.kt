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

import org.junit.Assert.*
import org.junit.Test

class VisionGeometryTest {
    @Test
    fun portraitLandscapeAndSquareFaceInputsNeverSilentlyUseWholeFrame() {
        assertEquals(PixelRegion(216, 536, 864, 1184), VisionGeometry.centeredFace(1080, 1720))
        assertEquals(PixelRegion(536, 216, 1184, 864), VisionGeometry.centeredFace(1720, 1080))
        assertEquals(PixelRegion(80, 80, 320, 320), VisionGeometry.centeredFace(400, 400))
    }

    @Test
    fun livenessFaceGuideAccountsForThePinnedTwoPointSevenContextScale() {
        val crop = PixelRegion(100, 200, 640, 740)
        val face = VisionGeometry.livenessGuide(crop)
        assertEquals(PixelRegion(270, 370, 470, 570), face)
        assertEquals(2.7f, crop.width.toFloat() / face.width, 0.0001f)
    }

    @Test
    fun letterboxedOcrEdgesMapBackToUprightSourceWithoutPaddingOrOffByOne() {
        val geometry = VisionGeometry.Letterbox(1280, 720, 640)
        assertEquals(0, geometry.left)
        assertEquals(140, geometry.top)
        assertEquals(0f, geometry.sourceY(140f), 0f)
        assertEquals(720f, geometry.sourceY(500f), 0f)
        assertEquals(1280f, geometry.sourceX(640f), 0f)
        assertEquals(0f, geometry.sourceY(0f), 0f)
        val box = PpocrDetector.Box(10, 150, 19, 159, 1f)
        assertEquals(20f, geometry.sourceX((box.x1 + 1).toFloat()) - geometry.sourceX(box.x0.toFloat()), 0f)
        assertEquals(20f, geometry.sourceY((box.y1 + 1).toFloat()) - geometry.sourceY(box.y0.toFloat()), 0f)
    }

    @Test
    fun oddLetterboxDimensionsUseTheSameIntegerExtentForForwardAndInverse() {
        val geometry = VisionGeometry.Letterbox(101, 57, 640)
        assertEquals(101f, geometry.sourceX((geometry.left + geometry.width).toFloat()), 0f)
        assertEquals(57f, geometry.sourceY((geometry.top + geometry.height).toFloat()), 0f)
    }
}
