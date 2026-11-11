package com.example.minimalapk.litert.imaging

import android.graphics.Bitmap
import com.example.minimalapk.examples.ImageFit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Always-run fixed fixtures for Android bitmap ownership, categorical scaling and alpha. */
class ImagingRenderingTest {
    @Test
    fun sameSizedInputIsNotRecycledAndCategoricalOverlayHasNoBlendedClassColors() {
        val source = Bitmap.createBitmap(IntArray(8) { 0xff000000.toInt() }, 4, 2, Bitmap.Config.ARGB_8888)
        try {
            resizedImagingPixels(source, 4, 2)
            assertFalse(source.isRecycled)
            val result = imagingOverlay(source, intArrayOf(0xffff0000.toInt(), 0xff0000ff.toInt()), 2, 1)
            try {
                assertNotSame(source, result)
                assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xffff0000.toInt(), 0xff0000ff.toInt(), 0xff0000ff.toInt(),
                    0xffff0000.toInt(), 0xffff0000.toInt(), 0xff0000ff.toInt(), 0xff0000ff.toInt()), imagingPixels(result))
                assertTrue(imagingPixels(source).all { it == 0xff000000.toInt() })
            } finally { result.recycle() }
        } finally { source.recycle() }
    }

    @Test
    fun sourceAlignedDepthRemovesLetterboxWithoutStretchingOrIntegerCropShift() {
        val fit = ImageFit(sourceWidth = 4, sourceHeight = 2, width = 4, height = 6)
        // Two padding rows above and below content must never enter the depth histogram.
        val depth = FloatArray(24) { 999f }
        for (i in 0 until 8) depth[8 + i] = i + 1f
        assertArrayEquals(FloatArray(8) { it + 1f }, sourceAlignedDepth(depth, fit), 0f)
    }

    @Test
    fun fractionalLetterboxIsSampledAtPixelCentersRatherThanTruncatedToIntegers() {
        val fit = ImageFit(sourceWidth = 5, sourceHeight = 3, width = 4, height = 6)
        val depth = FloatArray(24) { i -> (i % 4 + 10 * (i / 4)).toFloat() }
        assertArrayEquals(floatArrayOf(
            17f, 17.7f, 18.5f, 19.3f, 20f,
            25f, 25.7f, 26.5f, 27.3f, 28f,
            33f, 33.7f, 34.5f, 35.3f, 36f,
        ), sourceAlignedDepth(depth, fit), 1e-5f)
    }

    @Test
    fun transparentCutoutSurvivesBitmapPremultiplicationAndKeepsOriginalUnchanged() {
        val source = Bitmap.createBitmap(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt()),
            3, 1, Bitmap.Config.ARGB_8888)
        try {
            val before = imagingPixels(source)
            val cutout = imagingCutoutPixels(before, 3, 1, floatArrayOf(0f, .5f, 1f), 3, 1)
            val output = Bitmap.createBitmap(cutout, 3, 1, Bitmap.Config.ARGB_8888)
            try {
                assertEquals(0, output.getPixel(0, 0) ushr 24)
                assertEquals(128, output.getPixel(1, 0) ushr 24)
                assertEquals(255, output.getPixel(2, 0) ushr 24)
                assertEquals(255, (output.getPixel(1, 0) ushr 8) and 255)
                assertArrayEquals(before, imagingPixels(source))
            } finally { output.recycle() }
        } finally { source.recycle() }
    }
}
