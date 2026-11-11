package com.example.minimalapk.models

import com.example.minimalapk.pose.GraphTensor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TensorDataTest {
    @Test
    fun normalizesRgbAndPreservesNchwOrder() {
        val input = TensorInputConfig(listOf(1, 3, 1, 2), InputSource.IMAGE, ImageLayout.NCHW, std = 255f)
        val actual = imageValues(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), 3, input)
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), actual, 1e-8)
    }

    @Test
    fun quantizedInputRoundTripsAndClipsAtIntegerRange() {
        val tensor = GraphTensor("q", listOf(5), listOf(5), 9, .5f, -3)
        val bytes = encodeTensor(doubleArrayOf(-1000.0, -.5, 0.0, .5, 1000.0), tensor)
        val actual = ByteArray(5).also { bytes.duplicate().get(it) }
        assertArrayEquals(byteArrayOf(-128, -4, -3, -2, 127), actual)
        assertArrayEquals(doubleArrayOf(-62.5, -.5, 0.0, .5, 65.0), decodeTensor(bytes, 9, .5f, -3), 1e-8)
    }

    @Test
    fun unsignedPixelInputIsNotDividedBy255() {
        val tensor = GraphTensor("u8", listOf(3), listOf(3), 3)
        val bytes = encodeTensor(doubleArrayOf(0.0, 128.0, 255.0), tensor)
        assertArrayEquals(doubleArrayOf(0.0, 128.0, 255.0), decodeTensor(bytes, 3, 0f, 0), 0.0)
    }

    @Test
    fun grayscaleIntegerImagesRoundLuminanceWithoutRelaxingTokenValidation() {
        val input = TensorInputConfig(listOf(1, 1, 2, 1), InputSource.IMAGE)
        val tensor = GraphTensor("gray", input.shape, input.shape, 3)
        val gray = imageValues(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), 1, input)
        val encoded = encodeTensor(gray, tensor, InputSource.IMAGE)
        assertArrayEquals(doubleArrayOf(76.0, 150.0), decodeTensor(encoded, 3, 0f, 0), 0.0)
        assertThrows(IllegalArgumentException::class.java) { encodeTensor(gray, tensor, InputSource.NUMBERS) }
        val clipped = encodeTensor(doubleArrayOf(-10.2, 300.1), tensor, InputSource.IMAGE)
        assertArrayEquals(doubleArrayOf(0.0, 255.0), decodeTensor(clipped, 3, 0f, 0), 0.0)
    }

    @Test
    fun rejectsFractionalTokenIdsAndNonfiniteFloats() {
        val tensor = GraphTensor("ids", listOf(1), listOf(1), 2)
        assertThrows(IllegalArgumentException::class.java) { encodeTensor(doubleArrayOf(1.5), tensor) }
        assertThrows(IllegalArgumentException::class.java) { encodeTensor(doubleArrayOf(1e20), tensor) }
        assertThrows(IllegalArgumentException::class.java) { numericValues("NaN", 1) }
        assertThrows(IllegalArgumentException::class.java) { numericValues("1,2", 3) }
        assertArrayEquals(doubleArrayOf(7.0, 7.0, 7.0), numericValues("7", 3), 0.0)
    }

    @Test
    fun dynamicSignatureArrayKeepsTheConfiguredRankAndValues() {
        val tensor = GraphTensor("values", listOf(1, 2), listOf(-1, 2), 0)
        val buffer = encodeTensor(doubleArrayOf(1.0, 2.0, 3.0, 4.0), tensor)
        val array = shapedArray(buffer, 0, listOf(2, 2)) as Array<*>
        assertArrayEquals(floatArrayOf(1f, 2f), array[0] as FloatArray, 0f)
        assertArrayEquals(floatArrayOf(3f, 4f), array[1] as FloatArray, 0f)
    }

    @Test
    fun wavResamplesStereoAndZeroPads() {
        val bytes = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(44); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(2); putInt(8000); putInt(32000); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(8)
            putShort(0); putShort(0); putShort(16384); putShort(16384)
        }.array()
        assertArrayEquals(doubleArrayOf(0.0, .25, .5, .5, 0.0), wavSamples(bytes, 16000, 5), 1e-8)
        assertThrows(IllegalArgumentException::class.java) { wavSamples(bytes.copyOf(51), 16000, 5) }
        val wrongContainerSize = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 36) }
        assertThrows(IllegalArgumentException::class.java) { wavSamples(wrongContainerSize, 16000, 5) }
        val wrongByteRate = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1) }
        assertThrows(IllegalArgumentException::class.java) { wavSamples(wrongByteRate, 16000, 5) }
        assertThrows(IllegalArgumentException::class.java) { wavSamples(bytes, 0, 5) }
    }
}
