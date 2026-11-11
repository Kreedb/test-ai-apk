package com.example.minimalapk.ui.screens

import org.junit.Assert.assertEquals
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
}
