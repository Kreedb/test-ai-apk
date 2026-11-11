package com.example.minimalapk.litert.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagingMathTest {
    @Test
    fun rgbPackingDistinguishesRealEsrganNhwcFromOtherNchwInputs() {
        val pixels = intArrayOf(0xffff0000.toInt(), 0xff0000ff.toInt())
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f), packImagingRgb(pixels), 0f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f),
            packImagingRgb(pixels, ImagingLayout.NHWC), 0f)
        // Asymmetric second pixel makes a transposed input observably different.
        val asymmetric = intArrayOf(0xff804020.toInt(), 0xff102040.toInt())
        assertArrayEquals(floatArrayOf(128f, 16f, 64f, 32f, 32f, 64f).map { it / 255 }.toFloatArray(),
            packImagingRgb(asymmetric), 0f)
        assertArrayEquals(floatArrayOf(128f, 64f, 32f, 16f, 32f, 64f).map { it / 255 }.toFloatArray(),
            packImagingRgb(asymmetric, ImagingLayout.NHWC), 0f)
    }

    @Test
    fun signedAndImageNetNormalizationUseThePublishedRgbValues() {
        assertArrayEquals(floatArrayOf(-1f, 1f, -1f),
            packImagingRgb(intArrayOf(0xff00ff00.toInt()), normalization = ImagingNormalization.SIGNED), 0f)
        assertArrayEquals(floatArrayOf((1f - .485f) / .229f, -.456f / .224f, -.406f / .225f),
            packImagingRgb(intArrayOf(0xffff0000.toInt()), normalization = ImagingNormalization.IMAGENET), 1e-6f)
    }

    @Test
    fun superResolutionDecodesPlanarOutputAndClampsOvershoot() {
        val planar = floatArrayOf(1.2f, -.1f, .5f, 1f, 0f, .25f)
        assertArrayEquals(intArrayOf(0xffff7f00.toInt(), 0xff00ff3f.toInt()), imagingNchwPixels(planar, 2))
        assertThrows(IllegalArgumentException::class.java) { imagingNchwPixels(FloatArray(5), 2) }
        assertThrows(IllegalArgumentException::class.java) { imagingNchwPixels(floatArrayOf(Float.NaN, 0f, 0f), 1) }
    }

    @Test
    fun semanticAndBinaryHeadsUseClassMajorArgmaxWithStableTies() {
        assertArrayEquals(intArrayOf(2, 1, 0), imagingArgmax(floatArrayOf(
            -2f, 1f, 5f, -1f, 7f, 5f, 9f, 6f, -4f,
        ), classes = 3, pixels = 3))
        assertArrayEquals(intArrayOf(0, 1, 0), imagingArgmax(floatArrayOf(1f, 0f, 3f, 1f, 2f, -3f), 2, 3))
        assertThrows(IllegalArgumentException::class.java) { imagingArgmax(FloatArray(6), 19, 3) }
    }

    @Test
    fun ormbgNormalizesPerFrameWhileConstantFieldsBecomeTransparent() {
        val values = normalizeOrmbgMatte(floatArrayOf(-2f, 0f, 2f))
        assertArrayEquals(floatArrayOf(0f, .5f, 1f), values, 1e-6f)
        assertArrayEquals(FloatArray(3), normalizeOrmbgMatte(floatArrayOf(4f, 4f, 4f)), 0f)
        assertThrows(IllegalArgumentException::class.java) { normalizeOrmbgMatte(floatArrayOf(Float.POSITIVE_INFINITY)) }
    }

    @Test
    fun cutoutBilinearAlphaPreservesSourceColorAndExistingTransparency() {
        val source = intArrayOf(0xff123456.toInt(), 0x80123456.toInt(), 0x00123456)
        val output = imagingCutoutPixels(source, 3, 1, floatArrayOf(0f, 1f), 2, 1)
        assertArrayEquals(intArrayOf(0x00123456, 0x40123456, 0x00123456), output)
        assertArrayEquals(intArrayOf(0xff123456.toInt(), 0x80123456.toInt(), 0x00123456), source)
    }

    @Test
    fun nonSquareMatteMappingDoesNotTransposeOrCropTheImage() {
        val source = IntArray(6) { 0xffaabbcc.toInt() }
        val result = imagingCutoutPixels(source, 3, 2, floatArrayOf(0f, 1f, 1f, 0f), 2, 2)
        assertArrayEquals(intArrayOf(0, 128, 255, 255, 128, 0), result.map { it ushr 24 }.toIntArray())
        assertThrows(IllegalArgumentException::class.java) {
            imagingCutoutPixels(source, 3, 2, floatArrayOf(1f), 2, 2)
        }
    }

    @Test
    fun superResolutionCoresCoverEveryPixelExactlyOnceIncludingPartialEdges() {
        for ((width, height) in listOf(1 to 1, 97 to 101, 511 to 257)) {
            val coverage = IntArray(width * height)
            for (tile in imagingTiles(width, height)) {
                for (y in tile.top until tile.top + tile.height) for (x in tile.left until tile.left + tile.width) {
                    coverage[y * width + x]++
                }
            }
            assertTrue("$width × $height", coverage.all { it == 1 })
        }
    }

    @Test
    fun tileHalosUseRealNeighborsAndReplicateOnlyBeyondTheSourceBoundary() {
        val source = IntArray(97 * 2) { it }
        val tiles = imagingTiles(97, 2)
        val right = imagingTilePixels(source, 97, 2, tiles[1])
        assertEquals(80, right[16 * 128])
        assertEquals(96, right[16 * 128 + 16])
        assertEquals(96, right[16 * 128 + 127])
        assertEquals(193, right[127 * 128 + 127])
        val tiny = imagingTilePixels(intArrayOf(42), 1, 1, imagingTiles(1, 1).single())
        assertTrue(tiny.all { it == 42 })
    }

    @Test
    fun labelsKeepCityscapesTrainIdsAndSparseCocoCategoryIds() {
        assertEquals(19, CityscapesLabels.names.size)
        assertEquals("road", CityscapesLabels.names[0])
        assertEquals("person", CityscapesLabels.names[11])
        assertEquals(0xffdc143c.toInt(), CityscapesLabels.colors[11])
        assertEquals(91, RfDetrSegLabels.names.size)
        assertEquals(80, RfDetrSegLabels.names.count { it.isNotBlank() })
        assertEquals("", RfDetrSegLabels.names[12])
        assertEquals("stop sign", RfDetrSegLabels.names[13])
        assertEquals("toothbrush", RfDetrSegLabels.names[90])
    }
}
