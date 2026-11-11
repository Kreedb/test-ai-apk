package com.example.minimalapk.plugins

import com.example.minimalapk.pose.poseInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SuperResolutionGeometryTest {
    private fun source(rawWidth: Int, rawHeight: Int, rotation: Int, input: Int = 640): ImageSource {
        val layout = poseInputLayout(rawWidth, rawHeight, rotation, input)
        return ImageSource(1, 1, ImageSpace(input, input, layout.photoBounds), rotation)
    }

    @Test
    fun squareRoiMapsThroughEveryRotationWithoutStretching() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val source = source(640, 480, rotation)
            val roi = superResolutionRoi(source, 640, 480)
            assertEquals("raw left @$rotation", 200, roi.raw.left)
            assertEquals("raw top @$rotation", 120, roi.raw.top)
            assertEquals("raw side @$rotation", 240, roi.raw.width)
            assertEquals("raw side @$rotation", 240, roi.raw.height)
            assertEquals("source side @$rotation", 240, roi.source.width)
            assertEquals("source side @$rotation", 240, roi.source.height)
            assertEquals("source left @$rotation", 200, roi.source.left)
            assertEquals("source top @$rotation", 200, roi.source.top)
            assertEquals(roi.source, roi.composite)
            assertEquals(PixelRect(0, 0, 50, 50), roi.input)

            val center = ImagePoint(
                roi.source.left + roi.source.width / 2f,
                roi.source.top + roi.source.height / 2f,
            )
            val back = RawImageMapping(640, 480, source).toRaw(center)
            assertEquals(320f, back.x, 0.001f)
            assertEquals(240f, back.y, 0.001f)
            assertInside(roi.source, source)
        }
    }

    @Test
    fun nonSquareFrameUsesItsSmallerSideAndKeepsTheSameRegionUpright() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val source = source(1280, 720, rotation)
            val roi = superResolutionRoi(source, 1280, 720)
            assertEquals("raw left @$rotation", 460, roi.raw.left)
            assertEquals("raw top @$rotation", 180, roi.raw.top)
            assertEquals("raw side @$rotation", 360, roi.raw.width)
            assertEquals("source side @$rotation", 180, roi.source.width)
            assertEquals("source side @$rotation", 180, roi.source.height)
            assertEquals("source center @$rotation", 320f, roi.source.left + roi.source.width / 2f, 0.001f)
            assertEquals("source center @$rotation", 320f, roi.source.top + roi.source.height / 2f, 0.001f)
            assertInside(roi.source, source)

            val back = RawImageMapping(1280, 720, source).toRaw(
                ImagePoint(
                    roi.source.left + roi.source.width / 2f,
                    roi.source.top + roi.source.height / 2f,
                ),
            )
            assertEquals(640f, back.x, 0.001f)
            assertEquals(360f, back.y, 0.001f)
        }
    }

    @Test
    fun fractionScalesTheRoiButNeverTheFixedModelInput() {
        val source = source(640, 480, 90)
        val small = superResolutionRoi(source, 640, 480, fraction = 0.25f)
        val full = superResolutionRoi(source, 640, 480, fraction = 1f)
        assertEquals(120, small.raw.width)
        assertEquals(120, small.source.width)
        assertEquals(480, full.raw.width)
        // A full-frame dense model call is possible mathematically, but the plugin always uses the
        // centered 50% default and reports the ROI instead of claiming whole-frame SR.
        assertTrue(small.source.width < full.source.width)
        assertEquals(PixelRect(0, 0, 50, 50), small.input)
    }

    private fun assertInside(rect: PixelRect, source: ImageSource) {
        val bounds = source.space.photoBounds
        assertTrue(rect.left >= bounds.left)
        assertTrue(rect.top >= bounds.top)
        assertTrue(rect.right <= bounds.right)
        assertTrue(rect.bottom <= bounds.bottom)
        assertTrue(abs(rect.width - rect.height) <= 1)
    }
}
