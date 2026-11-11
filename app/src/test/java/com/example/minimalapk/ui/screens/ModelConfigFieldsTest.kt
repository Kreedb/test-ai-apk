package com.example.minimalapk.ui.screens

import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.GraphSignature
import com.example.minimalapk.pose.GraphTensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Properties

class ModelConfigFieldsTest {
    @Test
    fun editingOneFieldPreservesLabelsAndUnrelatedConfiguration() {
        val original = "version=1\nsignature=predict\ninput.0.std=255\noutput.0.labels=猫\\n狗\ncustom.pending=value\n"
        val updated = updateModelProperty(original, "input.0.std", "127.5")
        val properties = Properties().apply { load(updated.reader()) }
        assertEquals("127.5", properties.getProperty("input.0.std"))
        assertEquals("猫\n狗", properties.getProperty("output.0.labels"))
        assertEquals("value", properties.getProperty("custom.pending"))
        assertEquals("predict", properties.getProperty("signature"))
        val labels = updateModelProperty(updated, "output.0.labels", "一:号\n二=号\\尾")
        assertEquals("一:号\n二=号\\尾", Properties().apply { load(labels.reader()) }.getProperty("output.0.labels"))
    }

    @Test
    fun invalidIntermediateFieldKeepsAllInputsOfTheSelectedSignature() {
        val input = GraphTensor("x", listOf(1), listOf(1), 0)
        val output = input.copy(name = "result")
        val main = GraphSignature("", 0, listOf(input), listOf(output))
        val sum = GraphSignature("sum", 1, listOf(input, input.copy(name = "y")), listOf(output))
        val description = GraphDescription(listOf(main, sum), emptyList())
        val original = ModelConfig.infer(sum).encode()
        assertEquals("sum", ModelConfig.decode(original, description).signature)

        for ((key, invalid) in listOf("input.1.shape" to "", "input.1.std" to "-", "output.0.threshold" to "")) {
            val editing = updateModelProperty(original, key, invalid)
            assertThrows(IllegalArgumentException::class.java) { ModelConfig.decode(editing, description) }
            val selected = modelConfigSignature(editing, description)
            assertEquals(sum, selected)
            assertEquals(listOf("x", "y"), selected.inputs.map { it.name })

            val repaired = updateModelProperty(editing, key, "1")
            assertEquals(sum, modelConfigSignature(repaired, description))
            assertEquals(2, ModelConfig.decode(repaired, description).inputs.size)
        }
        assertEquals(main, modelConfigSignature(ModelConfig.infer(main).encode(), description))
    }
}
