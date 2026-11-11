package com.example.minimalapk.plugins

import com.example.minimalapk.pose.PhotoBounds
import com.example.minimalapk.pose.poseInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionGeometryTest {
    @Test
    fun lateTilesCannotPublishAfterReleaseNewPressConfigurationOrSessionChange() {
        val source = ImageSource(7, 42, ImageSpace(640, 640, PhotoBounds(0f, 80f, 640f, 560f)), 0)
        val press = PressToken(7, 1, 0, 100, 42)
        assertTrue(superResolutionRequestIsCurrent(true, press, press, press, source))
        assertFalse(superResolutionRequestIsCurrent(true, press, null, null, source))
        assertFalse(superResolutionRequestIsCurrent(true, press, press.copy(sequence = 2), press.copy(sequence = 2), source))
        assertFalse(superResolutionRequestIsCurrent(false, press, press, press, source))
        assertFalse(superResolutionRequestIsCurrent(true, press, press, press, source.copy(session = 8)))
        assertFalse(superResolutionRequestIsCurrent(true, press, press, press, source.copy(frameNumber = 41)))
    }

    @Test
    fun allPhotoPixelsIncludingEdgesAreEnhancedExactlyOnce() {
        listOf(640 to 480, 1280 to 720, 720 to 1280, 4032 to 3024, 17 to 29, 1 to 20000).forEach { (w, h) ->
            val plan = superResolutionPlan(w, h)
            val writes = IntArray(plan.width * plan.height)
            plan.tiles.forEach { tile ->
                for (y in tile.core.top until tile.core.bottom) for (x in tile.core.left until tile.core.right) {
                    writes[y * plan.width + x]++
                }
                assertTrue(tile.outputCrop.right <= 200)
                assertTrue(tile.outputCrop.bottom <= 200)
                assertEquals(tile.core.left - 5, tile.sampleLeft)
                assertEquals(tile.core.top - 5, tile.sampleTop)
            }
            assertTrue("Gaps/overlap in $w x $h", writes.all { it == 1 })
            assertTrue(plan.tiles.size <= SUPER_RESOLUTION_MAX_TILES)
            assertTrue(plan.outputWidth <= SUPER_RESOLUTION_MAX_OUTPUT_EDGE)
            assertTrue(plan.outputHeight <= SUPER_RESOLUTION_MAX_OUTPUT_EDGE)
            assertTrue(plan.width <= w && plan.height <= h)
            // Only integer pixel rounding, not square-cropping/stretching of the camera photo.
            assertTrue(kotlin.math.abs(plan.width.toDouble() / w - plan.height.toDouble() / h) <= 1.0 / minOf(w, h))
        }
    }

    @Test
    fun sourceCompositePreservesExactHostAspectAndLetterboxAtAllRotations() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val layout = poseInputLayout(1280, 720, rotation, 640)
            val space = ImageSpace(640, 640, layout.photoBounds)
            val plan = if (rotation % 180 == 0) superResolutionPlan(1280, 720) else superResolutionPlan(720, 1280)
            val (w, h) = superResolutionCompositeSize(space, plan)
            assertEquals(w.toLong() * space.height, h.toLong() * space.width)
            assertTrue(w <= 2048 && h <= 2048)
            assertTrue((layout.photoBounds.right - layout.photoBounds.left) * w / space.width <= plan.outputWidth + 1)
            assertTrue((layout.photoBounds.bottom - layout.photoBounds.top) * h / space.height <= plan.outputHeight + 1)
        }
        val space = ImageSpace(1280, 720, PhotoBounds(0f, 0f, 1280f, 720f))
        val (w, h) = superResolutionCompositeSize(space, superResolutionPlan(1280, 720))
        assertEquals(w.toLong() * 720, h.toLong() * 1280)
        assertTrue(w <= 2048 && h <= 2048)
    }
}
