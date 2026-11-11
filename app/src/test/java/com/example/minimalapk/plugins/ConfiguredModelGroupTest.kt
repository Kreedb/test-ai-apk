package com.example.minimalapk.plugins

import android.graphics.Bitmap
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ModelRunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class ConfiguredModelGroupTest {
    private val image = object : PluginImage {
        override val width = 8
        override val height = 8
        override val bitmap: Bitmap get() = error("Fake runners do not access Android pixels")
    }

    private fun model(id: String, enabled: Boolean = true) = ConfiguredModel(id, id, File("$id.tflite"), enabled)

    private class Runner(val id: String, val calls: MutableList<String>, val fail: Boolean = false) : ConfiguredFrameRunner {
        var closed = 0
        override fun run(image: PluginImage): ModelRunResult {
            calls += id
            if (fail) error("bad inference $id")
            return ModelRunResult(id, emptyList())
        }
        override fun close() { closed++ }
    }

    @Test
    fun executesEveryEnabledModelInOrderOnEveryFrameAndClosesAllOfThem() {
        val models = listOf(model("a"), model("disabled", false), model("b"), model("c"))
        val calls = mutableListOf<String>()
        val opened = mutableListOf<Runner>()
        val outputs = mutableListOf<String>()
        ConfiguredModelGroup(models, { true }) { selected ->
            Runner(selected.id, calls).also { opened += it }
        }.use { group ->
            repeat(2) { group.run(image, { true }) { selected, result ->
                assertEquals(selected.id, result.getOrThrow().text)
                outputs += selected.id
            } }
        }
        assertEquals(listOf("a", "b", "c", "a", "b", "c"), calls)
        assertEquals(calls, outputs)
        assertTrue(opened.all { it.closed == 1 })
    }

    @Test
    fun loadingAndInferenceFailuresAreReportedButDoNotSkipHealthySiblings() {
        val calls = mutableListOf<String>()
        val opened = mutableMapOf<String, Runner>()
        ConfiguredModelGroup(listOf(model("load-error"), model("run-error"), model("healthy")), { true }) { selected ->
            if (selected.id == "load-error") error("missing runtime")
            Runner(selected.id, calls, fail = selected.id == "run-error").also { opened[selected.id] = it }
        }.use { group ->
            repeat(2) {
                val results = linkedMapOf<String, Boolean>()
                group.run(image, { true }) { selected, result -> results[selected.id] = result.isSuccess }
                assertEquals(mapOf("load-error" to false, "run-error" to false, "healthy" to true), results)
            }
            assertEquals(listOf("run-error", "healthy", "healthy"), calls)
            assertEquals(1, opened.getValue("run-error").closed)
            assertEquals(0, opened.getValue("healthy").closed)
        }
        assertTrue(opened.values.all { it.closed == 1 })
    }

    @Test
    fun changingGenerationDuringLoadClosesOpenedModelsAndNeverOpensTheRest() {
        val calls = mutableListOf<String>()
        val runner = Runner("a", calls)
        var current = true
        val opened = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            ConfiguredModelGroup(listOf(model("a"), model("b")), { current }) { selected ->
                opened += selected.id
                current = false
                runner
            }
        }
        assertEquals(listOf("a"), opened)
        assertEquals(1, runner.closed)
    }

    @Test
    fun disablingBetweenModelsStopsFurtherInferenceAndEmptySelectionStaysSilent() {
        val calls = mutableListOf<String>()
        var current = true
        ConfiguredModelGroup(listOf(model("a"), model("b")), { true }) { Runner(it.id, calls) }.use { group ->
            group.run(image, { current }) { _, _ -> current = false }
            assertEquals(listOf("a"), calls)
            assertFalse(current)
        }
        ConfiguredModelGroup(listOf(model("disabled", false)), { true }) { error("not enabled") }.use { group ->
            assertTrue(group.status.isEmpty())
            group.run(image, { true }) { _, _ -> error("no models") }
        }
    }

    @Test
    fun multilineResultsUseAvailableSlotsInsteadOfTruncatingAtFirstLongHeader() {
        val header = "tensor ".repeat(45)
        val result = modelOutputMessages("分类器", "$header\n猫 90%\n狗 10%")
        assertTrue(result.all { it.text.length <= PluginHost.MAX_MESSAGE_CHARS && it.text.startsWith("分类器：") })
        assertEquals(header, result.take(2).joinToString("") { it.text.removePrefix("分类器：") })
        assertTrue(result.any { it.text == "分类器：狗 10%" })
        assertEquals(result, boundedModelMessages(result))
        val many = boundedModelMessages(modelOutputMessages("模型", (1..20).joinToString("\n") { "结果 $it" }))
        assertEquals(PluginHost.MAX_MESSAGES, many.size)
        assertTrue(many.first().text.contains("结果较多"))
    }
}
