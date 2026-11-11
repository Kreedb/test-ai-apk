package com.example.minimalapk.plugins

import com.example.minimalapk.pose.PhotoBounds
import com.example.minimalapk.pose.poseInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGeometryTest {
    @Test
    fun cropMappingMatchesSquareImageAndReversesPreviewCoordinates() {
        val space = ImageSpace(640, 640, PhotoBounds(80f, 0f, 560f, 640f))
        val viewport = ViewportMapping(space, 300, 600)
        assertEquals(.9375f, viewport.scale, 0f)
        assertEquals(-150f, viewport.left, 0f)
        assertEquals(ImagePoint(150f, 300f), viewport.toPreview(ImagePoint(320f, 320f)))
        assertEquals(ImagePoint(320f, 320f), viewport.toImage(ImagePoint(150f, 300f)))
        assertTrue(viewport.containsPhoto(ImagePoint(0f, 300f)))
        assertFalse(viewport.containsPhoto(ImagePoint(-1f, 300f)))
        assertFalse(viewport.containsPhoto(ImagePoint(300f, 300f)))
    }

    @Test
    fun letterboxAndZeroViewportCannotStartPresses() {
        val space = ImageSpace(640, 640, PhotoBounds(0f, 80f, 640f, 560f))
        val viewport = ViewportMapping(space, 640, 640)
        assertFalse(viewport.containsPhoto(ImagePoint(320f, 79f)))
        assertTrue(viewport.containsPhoto(ImagePoint(320f, 80f)))
        assertFalse(viewport.containsPhoto(ImagePoint(320f, 560f)))
        assertFalse(ViewportMapping(space, 0, 0).containsPhoto(ImagePoint(0f, 0f)))
    }

    @Test
    fun rotationRetainsRealPhotoBoundsAndSourceCompatibilityIsStrict() {
        val portrait = poseInputLayout(640, 480, 90, 640)
        val source = ImageSource(1, 2, ImageSpace(640, 640, portrait.photoBounds), 90)
        assertEquals(PhotoBounds(80f, 0f, 560f, 640f), source.space.photoBounds)
        assertNotEquals(source, source.copy(frameNumber = 3))
        assertNotEquals(source, source.copy(session = 2))
        assertNotEquals(source, source.copy(rotationDegrees = 270))
        assertNotEquals(source, source.copy(space = source.space.copy(photoBounds = PhotoBounds(100f, 0f, 540f, 640f))))
    }

    @Test
    fun rawImageMappingMatchesAllCameraRotationsAndRoundTripsRoiCoordinates() {
        val point = ImagePoint(100f, 200f)
        val expected = listOf(ImagePoint(100f, 280f), ImagePoint(360f, 100f), ImagePoint(540f, 360f), ImagePoint(280f, 540f))
        listOf(0, 90, 180, 270).forEachIndexed { index, rotation ->
            val layout = poseInputLayout(640, 480, rotation, 640)
            val source = ImageSource(1, 1, ImageSpace(640, 640, layout.photoBounds), rotation)
            val mapping = RawImageMapping(640, 480, source)
            assertEquals(expected[index], mapping.toImage(point))
            assertEquals(point, mapping.toRaw(expected[index]))
        }
    }

    @Test
    fun originalResolutionMapsWithoutDiscardingLetterboxRounding() {
        val layout = poseInputLayout(1280, 721, 90, 640)
        val source = ImageSource(1, 1, ImageSpace(640, 640, layout.photoBounds), 90)
        val mapping = RawImageMapping(1280, 721, source)
        val center = ImagePoint(640f, 360.5f)
        assertEquals(ImagePoint(layout.centerX, layout.centerY), mapping.toImage(center))
        val point = ImagePoint(201f, 351f)
        val restored = mapping.toRaw(mapping.toImage(point))
        assertEquals(point.x, restored.x, 0.0001f)
        assertEquals(point.y, restored.y, 0.0001f)
    }
}
