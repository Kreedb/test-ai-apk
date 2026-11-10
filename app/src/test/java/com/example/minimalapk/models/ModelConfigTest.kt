package com.example.minimalapk.models

import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.GraphSignature
import com.example.minimalapk.pose.GraphTensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelConfigTest {
    private val tensor = GraphTensor("pixels", listOf(1, 3, 32, 48), listOf(1, 3, -1, 48), 9, .5f, -3)
    private val graph = GraphSignature("predict", 1, listOf(tensor), listOf(tensor))
    private val description = GraphDescription(listOf(graph), emptyList())

    @Test
    fun roundTripsNchwDynamicConfigurationAndUnicodeLabels() {
        val config = ModelConfig.infer(graph).copy(
            inputs = listOf(TensorInputConfig(listOf(1, 3, 64, 48), InputSource.IMAGE, ImageLayout.NCHW, 127.5f, 127.5f)),
            outputs = listOf(TensorOutputConfig(labels = listOf("猫", "犬=dog"))),
        )
        assertEquals(config, ModelConfig.decode(config.encode(), description))
        assertEquals(ImageLayout.NCHW, ModelConfig.infer(graph).inputs.single().layout)
    }

    @Test
    fun refusesChangedStaticDimensionsMisspelledKeysAndNonfiniteNormalization() {
        val config = ModelConfig.infer(graph).encode()
        for (invalid in listOf(
            config.replace("1,3,32,48", "1,3,32,49"),
            config + "\ninput.0.stdd=1\n",
            config.replace("input.0.std=1.0", "input.0.std=NaN"),
            config.replace("input.0.std=1.0", "input.0.std=0"),
            config.replace("signature=predict", "signature=missing"),
        )) assertThrows(Exception::class.java) { ModelConfig.decode(invalid, description) }
    }

    @Test
    fun capsTensorAllocationBeforeMultiplicationCanOverflow() {
        assertEquals(1, tensorElements(emptyList()))
        assertThrows(IllegalArgumentException::class.java) { tensorElements(listOf(Int.MAX_VALUE, Int.MAX_VALUE)) }
        assertThrows(IllegalArgumentException::class.java) { tensorElements(listOf(1, -1)) }
    }

    @Test
    fun rejectsTextForTokenIdInputsWithActionableHint() {
        val config = ModelConfig.infer(graph).copy(inputs = listOf(TensorInputConfig(tensor.shape, InputSource.TEXT)))
        val failure = assertThrows(IllegalArgumentException::class.java) { ModelConfig.decode(config.encode(), description) }
        org.junit.Assert.assertTrue(failure.message!!.contains("token"))
    }
}
