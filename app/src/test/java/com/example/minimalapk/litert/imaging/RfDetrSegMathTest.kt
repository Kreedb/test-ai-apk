package com.example.minimalapk.litert.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

class RfDetrSegMathTest {
    @Test
    fun topkProposalGridAndQueryRankEmbeddingComposeCorrectly() {
        val logits = floatArrayOf(0f, 1f, 0f, 2f, 4f, 1f, -2f, -1f, 0f, 1f, 0f, 7f)
        val deltas = FloatArray(16).apply {
            this[12] = .2f; this[13] = -.4f; this[14] = ln(2f); this[15] = ln(4f)
        }
        val embedding = floatArrayOf(1f, -1f, ln(2f), ln(.5f), 0f, 0f, 0f, 0f)
        val actual = RfDetrSegMath.referencePoints(logits, deltas, embedding, grid = 2, classes = 3, queries = 2)
        assertArrayEquals(floatArrayOf(.86f, .53f, .2f, .1f, .75f, .25f, .05f, .05f), actual, 1e-6f)
    }

    @Test
    fun graphCopyWorkaroundHalvesMemoryBeforeDecoder() {
        assertArrayEquals(floatArrayOf(1f, -2f, 0f),
            RfDetrSegMath.restoreMemory(floatArrayOf(2f, -4f, 0f), expected = 3), 0f)
    }

    @Test
    fun decoderUsesSigmoidAndRetainsEachQueryMaskThroughClassAwareNms() {
        val boxes = floatArrayOf(.5f, .5f, .4f, .4f, .51f, .5f, .4f, .4f, .5f, .5f, .4f, .4f)
        val logits = floatArrayOf(-10f, 4f, 0f, -10f, 2f, 0f, -10f, 0f, 3f)
        val masks = floatArrayOf(-1f, 0f, .1f, 2f, 4f, 5f, 6f, 7f, 9f, 8f, -2f, -3f)
        val result = RfDetrSegMath.decode(boxes, logits, masks, queries = 3, classes = 3, maskSize = 2)
        assertEquals(listOf(0, 2), result.map { it.query })
        assertEquals(listOf(1, 2), result.map { it.cls })
        assertEquals(.98201376f, result[0].score, 1e-6f)
        assertArrayEquals(floatArrayOf(-1f, 0f, .1f, 2f), result[0].mask, 0f)
        assertArrayEquals(floatArrayOf(9f, 8f, -2f, -3f), result[1].mask, 0f)
        assertArrayEquals(intArrayOf(0, 0, 0x6e123456, 0x6e123456),
            RfDetrSegMath.maskPixels(result[0].mask, 0xff123456.toInt()))
    }

    @Test
    fun backgroundAndNegativeBoxesDoNotBecomeInstances() {
        val detections = RfDetrSegMath.decode(floatArrayOf(.5f, .5f, .4f, .4f, .5f, .5f, -1f, .4f),
            floatArrayOf(5f, 0f, -1f, 5f), floatArrayOf(1f, 1f), queries = 2, classes = 2, maskSize = 1)
        assertEquals(0, detections.size)
    }

    @Test
    fun nmsRetainsMaskIdentityAndNonOverlappingSameClassObjects() {
        val high = RfSegDetection(1, .9f, .2f, .2f, .2f, .2f, floatArrayOf(4f), 0)
        val low = high.copy(score = .6f, query = 1)
        val remote = high.copy(cx = .8f, score = .7f, query = 2)
        val otherClass = high.copy(cls = 2, score = .8f, query = 3)
        val result = RfDetrSegMath.nms(listOf(low, remote, otherClass, high))
        assertEquals(listOf(high, otherClass, remote), result)
        assertSame(high.mask, result[0].mask)
        assertEquals(1f, RfDetrSegMath.iou(high, low), 1e-6f)
        assertEquals(0f, RfDetrSegMath.iou(high, remote), 0f)
    }

    @Test
    fun runtimeConstantFilesAreLittleEndianAndExactLength() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1.25f).putFloat(-2.5f).array()
        assertArrayEquals(floatArrayOf(1.25f, -2.5f), RfDetrSegMath.littleEndianFloats(bytes, 2, "fixture"), 0f)
        assertThrows(IllegalArgumentException::class.java) { RfDetrSegMath.littleEndianFloats(bytes, 3, "fixture") }
    }

    @Test
    fun converterReorderedSlotsResolveButAmbiguousAndMissingShapesFail() {
        assertEquals(2, RfDetrSegMath.slot(listOf(676 * 256, 676 * 4, 676 * 91), 676 * 91))
        assertThrows(IllegalArgumentException::class.java) { RfDetrSegMath.slot(listOf(4, 4), 4) }
        assertThrows(IllegalArgumentException::class.java) { RfDetrSegMath.slot(listOf(4, 8), 3) }
    }

    @Test
    fun instanceMaskUpsamplesLogitsBeforeThresholdingAndPreservesSourceAxes() {
        val mask = floatArrayOf(-9f, 1f, 1f, -9f)
        val result = RfDetrSegMath.sourceMaskPixels(mask, 0xff123456.toInt(), 4, 2, maskSize = 2)
        // Thresholding the 2×2 mask before resize would incorrectly color two pixels per row.
        assertArrayEquals(intArrayOf(0, 0, 0, 0x6e123456, 0x6e123456, 0, 0, 0), result)
    }
}
