package com.example.minimalapk.ui.screens

import com.example.minimalapk.plugins.ImagePoint
import com.example.minimalapk.plugins.ImageSource
import com.example.minimalapk.plugins.ImageSpace
import com.example.minimalapk.plugins.RawImageMapping
import com.example.minimalapk.plugins.ViewportMapping
import com.example.minimalapk.pose.poseInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CameraPreviewGeometryTest {
    @Test
    fun rawDetailAndPoseAlignmentDoNotDependOnModelInputResolution() {
        for (input in listOf(192, 640)) {
            val layout = poseInputLayout(1280, 960, 90, input)
            val source = ImageSource(1, 1, ImageSpace(input, input, layout.photoBounds), 90)
            val matrix = rawPreviewMatrix(1280, 960, source, 720, 1280)
            // 1280 source pixels fill the 1280-high viewport even for a 192-pixel model.
            assertPoint(ImagePoint(360f, 640f), matrix.map(640f, 480f))
            assertPoint(ImagePoint(640f, 100f), matrix.map(100f, 200f))
            assertEquals(1f, hypot(matrix[0], matrix[3]), 0.0001f)
            assertEquals(1f, hypot(matrix[1], matrix[4]), 0.0001f)

            // A pose keypoint in that model's coordinates lands on the same raw camera pixel.
            val keypoint = ImagePoint(input * .71875f, input * .078125f)
            val viewport = ViewportMapping(source.space, 720, 1280)
            assertPoint(viewport.toPreview(keypoint), matrix.map(100f, 200f))
        }
    }

    @Test
    fun fullPhotoCornersKeepTheirAspectAndTouchMappingThroughEveryCameraRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val layout = poseInputLayout(1280, 960, rotation, 640)
            val source = ImageSource(1, 1, ImageSpace(640, 640, layout.photoBounds), rotation)
            val matrix = rawPreviewMatrix(1280, 960, source, 640, 640)
            val corners = listOf(
                matrix.map(0f, 0f), matrix.map(1280f, 0f),
                matrix.map(0f, 960f), matrix.map(1280f, 960f),
            )
            assertEquals(layout.photoBounds.left, corners.minOf { it.x }, 0.001f)
            assertEquals(layout.photoBounds.right, corners.maxOf { it.x }, 0.001f)
            assertEquals(layout.photoBounds.top, corners.minOf { it.y }, 0.001f)
            assertEquals(layout.photoBounds.bottom, corners.maxOf { it.y }, 0.001f)
            assertEquals(.5f, hypot(matrix[0], matrix[3]), 0.0001f)
            assertEquals(.5f, hypot(matrix[1], matrix[4]), 0.0001f)
            assertEquals(0f, matrix[0] * matrix[1] + matrix[3] * matrix[4], 0.0001f)

            val viewport = ViewportMapping(source.space, 640, 640)
            val touch = matrix.map(200f, 300f)
            assertTrue(viewport.containsPhoto(touch))
            assertFalse(viewport.containsPhoto(ImagePoint(10f, 10f)))
            val imagePoint = checkNotNull(viewport.toImage(touch))
            assertPoint(ImagePoint(200f, 300f), RawImageMapping(1280, 960, source).toRaw(imagePoint))
        }
    }

    @Test
    fun oddCameraDimensionsUseTheOriginalRoundedLetterboxCenter() {
        val layout = poseInputLayout(1280, 721, 90, 640)
        val source = ImageSource(1, 1, ImageSpace(640, 640, layout.photoBounds), 90)
        val viewport = ViewportMapping(source.space, 400, 700)
        val matrix = rawPreviewMatrix(1280, 721, source, 400, 700)
        // Re-centering the raw photo in the viewport would disagree with poses/replacements by
        // half a model pixel here: the original rounded letterbox is centered at x=319.5.
        assertEquals(319.5f, layout.centerX, 0f)
        assertPoint(
            viewport.toPreview(ImagePoint(layout.centerX, layout.centerY)),
            matrix.map(640f, 360.5f),
        )
        val touch = matrix.map(201f, 351f)
        val imagePoint = checkNotNull(viewport.toImage(touch))
        assertPoint(ImagePoint(201f, 351f), RawImageMapping(1280, 721, source).toRaw(imagePoint))
    }

    private fun FloatArray.map(x: Float, y: Float) = ImagePoint(
        this[0] * x + this[1] * y + this[2],
        this[3] * x + this[4] * y + this[5],
    )

    private fun assertPoint(expected: ImagePoint, actual: ImagePoint) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
    }
}
