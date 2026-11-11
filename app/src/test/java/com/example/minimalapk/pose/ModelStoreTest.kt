package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelStoreTest {
    @Test
    fun roundTripsSpecsThroughStorage() {
        val specs = ModelStore.builtIn.mapIndexed { index, spec ->
            spec.copy(
                enabled = index == 0,
                scoreThreshold = 0.4f,
                keypointThreshold = 0.6f,
                debounceFrames = 3,
                boxFormat = BoxFormat.CENTER,
                outputLayout = OutputLayout.ANCHOR_MAJOR,
            )
        }

        assertEquals(specs, ModelStore.decode(ModelStore.encode(specs)))
    }

    @Test
    fun dropsMalformedEntries() {
        val valid = ModelStore.encode(listOf(ModelStore.builtIn.first()))

        assertEquals(1, ModelStore.decode("$valid\nnot-a-spec").size)
    }

    @Test
    fun readsEntriesSavedBeforeTheOutputLayoutField() {
        val legacy = ModelStore.encode(listOf(ModelStore.builtIn.first()))
            .substringBeforeLast('\t')

        val spec = ModelStore.decode(legacy).single()

        assertEquals(OutputLayout.VALUE_MAJOR, spec.outputLayout)
    }

    @Test
    fun bundledAndImportedDecoderEditsSurviveRestoreWithoutLosingTheirSwitches() {
        val bundled = ModelStore.builtIn.single().copy(
            enabled = false, anchors = 10, keypoints = 26, outputLayout = OutputLayout.ANCHOR_MAJOR,
            scoreThreshold = .6f, keypointThreshold = .7f, debounceFrames = 5, boxFormat = BoxFormat.CENTER,
        )
        val imported = bundled.copy(id = "imported", source = "file:imported.tflite", enabled = true)
        val restored = ModelStore.restore(ModelStore.decode(ModelStore.encode(listOf(bundled, imported)))) { true }
        assertEquals(listOf(bundled, imported), restored)
    }

    @Test
    fun reimportKeepsEditedOptionsAndDoesNotResetAnEnabledModel() {
        val model = ModelStore.builtIn.single().copy(id = "download", source = "file:download.tflite", scoreThreshold = .8f)
        val existing = listOf(ModelStore.builtIn.single(), model)
        assertEquals(existing, ModelStore.withImported(existing, model.copy(enabled = false, scoreThreshold = .35f)))
        val newModel = model.copy(id = "another", enabled = false)
        assertEquals(existing + newModel, ModelStore.withImported(existing, newModel))
    }

    @Test
    fun poseCapacityRejectsExcessInsteadOfSilentlyDisablingOtherModels() {
        val specs = List(3) { ModelStore.builtIn.single().copy(id = "model$it") }
        ModelStore.requireEnabledCapacity(specs.take(2))
        assertThrows(IllegalArgumentException::class.java) { ModelStore.requireEnabledCapacity(specs) }
        assertEquals(3, specs.count { it.enabled })
    }

    @Test
    fun decoderConfigurationMustMatchActualAxesNotJustElementCountForBothSources() {
        listOf("asset:models/yolo26n_pose.tflite", "file:yolo11.tflite").forEach { source ->
            val spec = ModelStore.builtIn.single().copy(source = source)
            val input = GraphTensor("input", listOf(1, 640, 640, 3), listOf(1, 640, 640, 3), 0)
            val output = GraphTensor("output", listOf(1, 56, 8400), listOf(1, 56, 8400), 0)
            val graph = GraphDescription(listOf(GraphSignature("", 0, listOf(input), listOf(output))), emptyList())
            validatePoseModelConfig(spec, graph)
            validatePoseModelConfig(spec.copy(boxFormat = BoxFormat.CENTER), graph)
            assertThrows(IllegalArgumentException::class.java) {
                validatePoseModelConfig(spec.copy(outputLayout = OutputLayout.ANCHOR_MAJOR), graph)
            }
            assertThrows(IllegalArgumentException::class.java) { validatePoseModelConfig(spec.copy(anchors = 840), graph) }
            assertThrows(IllegalArgumentException::class.java) { validatePoseModelConfig(spec.copy(keypoints = 26), graph) }
            assertThrows(IllegalArgumentException::class.java) { validatePoseModelConfig(spec.copy(scoreThreshold = Float.NaN), graph) }
            val transposed = graph.copy(signatures = listOf(graph.signatures.single().copy(
                outputs = listOf(output.copy(shape = listOf(1, 8400, 56))),
            )))
            validatePoseModelConfig(spec.copy(outputLayout = OutputLayout.ANCHOR_MAJOR), transposed)
        }
    }
}
