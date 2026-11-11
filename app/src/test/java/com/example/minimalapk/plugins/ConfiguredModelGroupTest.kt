package com.example.minimalapk.plugins

import android.graphics.Bitmap
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredPcmContract
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
    fun manualModelsOnlyReportReadinessAndAudioIsNotFedCameraPixels() {
        val calls = mutableListOf<String>()
        val closed = mutableListOf<String>()
        ConfiguredModelGroup(listOf(model("image"), model("manual"), model("audio")), { true }) { selected ->
            object : ConfiguredFrameRunner {
                override val acceptsFrames = selected.id == "image"
                override val pcm = if (selected.id == "audio") ConfiguredPcmContract(4, 8000) else null
                override val status = if (selected.id == "manual") "按需运行：请填写值或选择文件" else "已就绪"
                override fun run(image: PluginImage): ModelRunResult {
                    assertEquals("image", selected.id)
                    calls += "frame"
                    return ModelRunResult("image", emptyList())
                }
                override fun runPcm(samples: FloatArray): ModelRunResult {
                    assertEquals("audio", selected.id)
                    assertEquals(4, samples.size)
                    calls += "pcm"
                    return ModelRunResult("audio", emptyList())
                }
                override fun close() { closed += selected.id }
            }
        }.use { group ->
            assertTrue(group.modelStatus.getValue("manual").text.contains("按需运行"))
            assertEquals(mapOf("audio" to ConfiguredPcmContract(4, 8000)), group.pcmContracts)
            group.run(image, { true }) { selected, _ -> assertEquals("image", selected.id) }
            group.runPcm(mapOf("audio" to FloatArray(4)), { true }) { selected, result ->
                assertEquals("audio", selected.id)
                assertTrue(result.isSuccess)
            }
        }
        assertEquals(listOf("frame", "pcm"), calls)
        assertEquals(listOf("image", "manual", "audio"), closed)
    }

    @Test
    fun audioInferenceFailureIsLatchedAndHealthySiblingStillRuns() {
        val calls = mutableListOf<String>()
        val closes = mutableListOf<String>()
        ConfiguredModelGroup(listOf(model("broken-audio"), model("healthy-audio")), { true }) { selected ->
            object : ConfiguredFrameRunner {
                override val acceptsFrames = false
                override val pcm = ConfiguredPcmContract(2, 16000)
                override fun run(image: PluginImage): ModelRunResult = error("not image")
                override fun runPcm(samples: FloatArray): ModelRunResult {
                    calls += selected.id
                    if (selected.id == "broken-audio") error("unsupported operator")
                    return ModelRunResult("ok", emptyList())
                }
                override fun close() { closes += selected.id }
            }
        }.use { group ->
            repeat(2) {
                val results = linkedMapOf<String, Boolean>()
                group.runPcm(mapOf("broken-audio" to FloatArray(2), "healthy-audio" to FloatArray(2)), { true }) { selected, result ->
                    results[selected.id] = result.isSuccess
                }
                assertEquals(mapOf("broken-audio" to false, "healthy-audio" to true), results)
            }
            assertEquals(setOf("healthy-audio"), group.pcmContracts.keys)
        }
        assertEquals(listOf("broken-audio", "healthy-audio", "healthy-audio"), calls)
        assertEquals(listOf("broken-audio", "healthy-audio"), closes)
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
