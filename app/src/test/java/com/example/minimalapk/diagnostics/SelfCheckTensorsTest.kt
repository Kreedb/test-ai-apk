package com.example.minimalapk.diagnostics

import com.example.minimalapk.models.ImageLayout
import com.example.minimalapk.models.InputSource
import com.example.minimalapk.models.TensorInputConfig
import com.example.minimalapk.pose.GraphTensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

class SelfCheckTensorsTest {
    private fun tensor(type: Int = 0, shape: List<Int> = listOf(2), signature: List<Int> = shape) =
        GraphTensor("output", shape, signature, type)

    private fun floats(vararg values: Float) = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.nativeOrder()).apply { values.forEach { putFloat(it) }; rewind() }

    @Test
    fun allNumericOutputElementsAreCheckedForNonfiniteValuesAndExactLength() {
        assertTrue(checkOutputBuffer(tensor(), listOf(2), floats(0f, 4f)).contains("finite=true"))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                checkOutputBuffer(tensor(), listOf(2), floats(0f, invalid))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkOutputBuffer(tensor(), listOf(2), floats(0f))
        }
    }

    @Test
    fun fixedAndRuntimeSizedOutputsUseTheActualShapeContract() {
        assertThrows(IllegalArgumentException::class.java) { checkOutputShape(tensor(), listOf(3)) }
        assertThrows(IllegalArgumentException::class.java) { checkOutputShape(tensor(), listOf(-1)) }
        assertEquals(3, checkOutputShape(tensor(signature = listOf(-1)), listOf(3)))
        assertEquals(4, checkOutputShape(tensor(shape = emptyList()), listOf(1, 4)))
        assertEquals(1, checkOutputShape(tensor(shape = emptyList()), emptyList()))
        assertEquals(0, checkOutputShape(tensor(signature = listOf(-1)), listOf(0)))
    }

    @Test
    fun quantizedOutputsAreDequantizedAndUnsupportedTypesAreNotReportedAsPass() {
        val result = checkOutputBuffer(tensor(type = 9), listOf(2), ByteBuffer.wrap(byteArrayOf(-2, 2)), 0.5f, -2)
        assertTrue(result.contains("range=0.0..2.0"))
        assertThrows(CheckSkipped::class.java) { checkOutputBuffer(tensor(type = 1), listOf(2), ByteBuffer.allocate(4)) }
        assertThrows(CheckSkipped::class.java) { checkInputElements(listOf(Int.MAX_VALUE, Int.MAX_VALUE)) }
        assertThrows(CheckSkipped::class.java) { checkInputElements(listOf(1, -1)) }
    }

    @Test
    fun stringsValidateTheirWholeOffsetTableAndElementCount() {
        val buffer = ByteBuffer.allocate(19).order(ByteOrder.nativeOrder())
            .putInt(2).putInt(16).putInt(17).putInt(19).put("abc".toByteArray()).apply { rewind() }
        assertTrue(checkOutputBuffer(tensor(type = 5), listOf(2), buffer).contains("offsets=valid"))
        buffer.putInt(12, 100)
        assertThrows(IllegalArgumentException::class.java) { checkOutputBuffer(tensor(type = 5), listOf(2), buffer) }
    }

    @Test
    fun syntheticImageRespectsNchwAndQuantizationWhileAudioHasFiniteNonzeroSignal() {
        val imageTensor = tensor(type = 9, shape = listOf(1, 3, 2, 2)).copy(scale = 1f / 128, zeroPoint = -1,
            scales = listOf(1f / 128), zeroPoints = listOf(-1L))
        val image = syntheticInput(imageTensor, TensorInputConfig(imageTensor.shape, InputSource.IMAGE,
            ImageLayout.NCHW, mean = 127.5f, std = 127.5f))
        assertEquals(12, (image.value as ByteBuffer).remaining())
        assertEquals(4, image.pixels!!.size)
        val sound = syntheticInput(tensor(shape = listOf(160)), TensorInputConfig(listOf(160), InputSource.AUDIO))
        val samples = (sound.value as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val values = FloatArray(samples.remaining()).also { samples.get(it) }
        assertTrue(values.all { it.isFinite() })
        assertTrue(values.any { it != 0f })
    }

    @Test
    fun configuredNumericValuesAndStringShapesAreHonouredWithoutOpeningFiles() {
        val input = syntheticInput(tensor(type = 2), TensorInputConfig(listOf(2), InputSource.NUMBERS, values = "3,7"))
        val bytes = (input.value as ByteBuffer).order(ByteOrder.nativeOrder())
        assertEquals(3, bytes.int)
        assertEquals(7, bytes.int)
        val text = syntheticInput(tensor(type = 5, shape = listOf(1, 2)), TensorInputConfig(listOf(1, 2), InputSource.TEXT))
        assertTrue(text.value is Array<*>)
        val row = (text.value as Array<*>).single() as Array<*>
        assertEquals(listOf("self check", "self check"), row.toList())
    }

    @Test
    fun hashesStreamExactlyAndCancellationInterruptsHashing() {
        val digest = checkDigest(ByteArrayInputStream("abc".toByteArray()))
        assertEquals(3L, digest.bytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest.sha256)
        var calls = 0
        assertThrows(CancellationException::class.java) {
            checkDigest(ByteArrayInputStream(ByteArray(1024 * 1024))) {
                if (++calls == 2) throw CancellationException("cancel")
            }
        }
        assertEquals(2, calls)
        assertFalse(digest.sha256.isEmpty())
    }
}
