package com.example.minimalapk.litert.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Opt-in replay of ACTUAL pinned-model CPU outputs through production Kotlin postprocessing.
 * Run prepare_fixtures.py, then JVM -Dimaging.fixtures=<output>. Standard offline unit runs skip
 * these tests; synthetic math tests do not need weights or Python. These checks complement the
 * Android end-to-end smoke: they verify model-dependent semantics, not just finite/shape checks.
 */
class ImagingReferenceReplayTest {
    private lateinit var root: File

    @Before
    fun requirePreparedFixtures() {
        val directory = System.getProperty("imaging.fixtures")
        assumeTrue("Set imaging.fixtures to prepared fixed-image CPU references", !directory.isNullOrBlank())
        root = File(directory!!)
    }

    @Test
    fun pidNetActualStreetHasRoadBuildingsCarsAndMatchesClassColoredGolden() {
        val dir = File(root, "semantic-segmentation")
        val image = image(File(dir, "input.png"))
        assertArrayEquals(floats(dir, "input.f32"), packImagingRgb(image.pixels,
            normalization = ImagingNormalization.IMAGENET), 1e-6f)
        val labels = imagingArgmax(floats(dir, "output-0.f32"), 19, 128 * 128)
        assertTrue(labels.count { it == 0 } > labels.size / 4)
        assertTrue(labels.count { it == 2 } > labels.size / 10)
        assertTrue(labels.count { it == 13 } > labels.size * .03)
        val rendered = IntArray(image.pixels.size) { i ->
            val label = labels[(i / image.width) * 128 / image.height * 128 + (i % image.width) * 128 / image.width]
            blend(image.pixels[i], CityscapesLabels.colors[label], 130)
        }
        compare(rendered, image(File(dir, "expected.png")).pixels, maxMae = 1.0)
    }

    @Test
    fun twinLiteActualRoadSeparatesDriveableAndLaneHeads() {
        val dir = File(root, "lane-detection")
        val image = image(File(dir, "input.png"))
        assertArrayEquals(floats(dir, "input.f32"), packImagingRgb(image.pixels), 0f)
        val road = imagingArgmax(floats(dir, "output-0.f32"), 2, 360 * 640)
        val lane = imagingArgmax(floats(dir, "output-1.f32"), 2, 360 * 640)
        assertTrue(road.count { it == 1 } > road.size * .1)
        assertTrue(lane.count { it == 1 } in (lane.size / 1000)..(lane.size / 20))
        val rendered = IntArray(image.pixels.size) { i ->
            when {
                lane[i] == 1 -> blend(image.pixels[i], 0xffff3030.toInt(), 217)
                road[i] == 1 -> blend(image.pixels[i], 0xff00d060.toInt(), 116)
                else -> image.pixels[i]
            }
        }
        compare(rendered, image(File(dir, "expected.png")).pixels, maxMae = 1.0)
    }

    @Test
    fun bothActualSrModelsUseCorrectInputLayoutAndProduceSameSourceFrame() {
        for (task in listOf("super-resolution", "super-resolution-real-esrgan")) {
            val dir = File(root, task)
            val image = image(File(dir, "input.png"))
            val tile = imagingTiles(image.width, image.height).single()
            val packed = packImagingRgb(imagingTilePixels(image.pixels, image.width, image.height, tile),
                layout = if (task.endsWith("real-esrgan")) ImagingLayout.NHWC else ImagingLayout.NCHW)
            assertArrayEquals(floats(dir, "input.f32"), packed, 0f)
            val output = imagingNchwPixels(floats(dir, "output-0.f32"), 512 * 512)
            val golden = image(File(dir, "expected.png"))
            assertEquals(image.width * 4, golden.width)
            assertEquals(image.height * 4, golden.height)
            val crop = IntArray(golden.pixels.size) { i -> output[(i / golden.width + 64) * 512 + i % golden.width + 64] }
            compare(crop, golden.pixels, maxMae = .01)
        }
    }

    @Test
    fun dehazeActualCurveTensorReconstructsTheGoldenNotAnRgbTensorPreview() {
        val dir = File(root, "image-dehazing")
        val image = image(File(dir, "input.png"))
        assertArrayEquals(floats(dir, "input.f32"), packImagingRgb(image.pixels,
            normalization = ImagingNormalization.SIGNED), 0f)
        val rendered = applyMctCurves(image.pixels, image.width, image.height, floats(dir, "output-0.f32"))
        compare(rendered, image(File(dir, "expected.png")).pixels, maxMae = .01)
        assertTrue(channelMae(rendered, image.pixels, intArrayOf(0, 8, 16)) > 3.0)
    }

    @Test
    fun realPortraitAlphaHasSubjectAndBackgroundAndMatchesTheFullResolutionGolden() {
        for (task in listOf("background-removal", "portrait-matting")) {
            val dir = File(root, task)
            val image = image(File(dir, "input.png"))
            assertArrayEquals(floats(dir, "input.f32"), packImagingRgb(image.pixels,
                normalization = if (task == "portrait-matting") ImagingNormalization.SIGNED else ImagingNormalization.UNIT), 0f)
            val raw = floats(dir, "output-0.f32")
            val matte = if (task == "background-removal") normalizeOrmbgMatte(raw) else raw
            val rendered = imagingCutoutPixels(image.pixels, image.width, image.height, matte, image.width, image.height)
            val golden = image(File(dir, "expected.png"))
            assertTrue(channelMae(rendered, golden.pixels, intArrayOf(24)) < .01)
            assertTrue((rendered[0] ushr 24) < 77)
            assertTrue((rendered[image.height / 2 * image.width + image.width / 2] ushr 24) > 178)
            assertTrue(rendered.any { (it ushr 24) in 5..250 })
        }
    }

    @Test
    fun rfDetrActualTwoGraphHostStepAndPerQueryMasksMatchRealPhotoReference() {
        val dir = File(root, "instance-segmentation")
        val image = image(File(dir, "input.png"))
        assertArrayEquals(floats(dir, "input.f32"), packImagingRgb(image.pixels,
            normalization = ImagingNormalization.IMAGENET), 1e-6f)
        val references = RfDetrSegMath.referencePoints(floats(dir, "a-output-61516.f32"),
            floats(dir, "a-output-2704.f32"), floats(dir, "refpoint-embedding.f32"))
        assertArrayEquals(floats(dir, "reference-points.f32"), references, 2e-6f)
        assertArrayEquals(floats(dir, "memory.f32"),
            RfDetrSegMath.restoreMemory(floats(dir, "a-output-173056.f32")), 0f)
        val detections = RfDetrSegMath.decode(floats(dir, "b-output-400.f32"),
            floats(dir, "b-output-9100.f32"), floats(dir, "b-output-608400.f32"))
        assertEquals(listOf("person", "tie"), detections.map { RfDetrSegLabels.name(it.cls) })
        assertEquals(.9741064f, detections[0].score, .001f)
        assertEquals(.8485864f, detections[1].score, .001f)
        val rendered = image.pixels.copyOf()
        for (index in detections.indices.reversed()) {
            val mask = RfDetrSegMath.sourceMaskPixels(detections[index].mask, RfDetrSegLabels.colors[index], image.width, image.height)
            for (i in mask.indices) if (mask[i] != 0) rendered[i] = blend(rendered[i], mask[i], 110)
        }
        compare(rendered, image(File(dir, "expected.png")).pixels, maxMae = 1.0)
    }

    @Test
    fun actualRelativeDepthMatchesPublishedSpectralRenderingAndHasNoMetricScale() {
        val dir = File(root, "monocular-geometry-estimation")
        val image = image(File(dir, "input.png"))
        assertArrayEquals(floats(dir, "input.f32"), packImagingRgb(image.pixels,
            normalization = ImagingNormalization.IMAGENET), 1e-6f)
        val depth = floats(dir, "output-0.f32")
        val rendered = RelativeDepth.pixels(depth)
        compare(rendered, image(File(dir, "expected.png")).pixels, maxMae = .01)
        assertTrue(rendered.toSet().size > 200)
        assertArrayEquals(rendered, RelativeDepth.pixels(depth.map { it * 2f }.toFloatArray()))
    }

    private data class Pixels(val width: Int, val height: Int, val pixels: IntArray)

    private fun image(file: File): Pixels {
        val image = checkNotNull(ImageIO.read(file))
        return Pixels(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    }

    private fun floats(directory: File, name: String): FloatArray {
        val bytes = File(directory, name).readBytes()
        require(bytes.size % 4 == 0)
        return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    private fun blend(source: Int, color: Int, alpha: Int): Int {
        var pixel = 255 shl 24
        for (shift in intArrayOf(0, 8, 16)) {
            val value = (((source ushr shift) and 255) * (1f - alpha / 255f) + ((color ushr shift) and 255) * alpha / 255f)
            pixel = pixel or (value.roundToInt().coerceIn(0, 255) shl shift)
        }
        return pixel
    }

    private fun compare(actual: IntArray, expected: IntArray, maxMae: Double) {
        assertEquals(expected.size, actual.size)
        val mae = channelMae(actual, expected, intArrayOf(0, 8, 16))
        assertTrue("Rendered MAE $mae must be below $maxMae", mae < maxMae)
    }

    private fun channelMae(a: IntArray, b: IntArray, shifts: IntArray): Double {
        var difference = 0.0
        for (i in a.indices) for (shift in shifts) difference += abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255))
        return difference / (a.size * shifts.size)
    }
}
