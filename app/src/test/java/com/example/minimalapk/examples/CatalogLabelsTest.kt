package com.example.minimalapk.examples

import com.example.minimalapk.models.InputSource
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.OutputRender
import com.example.minimalapk.models.TensorInputConfig
import com.example.minimalapk.models.TensorOutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogLabelsTest {
    @Test
    fun knownModelResourcesPinNamesAndCountsIncludingBackgroundAndCocoHoles() {
        assertEquals("labels.txt" to 1001, ExampleCatalog.classification.let { it.labelFile to it.labelCount })
        assertEquals("labelmap.txt" to 90, ExampleCatalog.detection.let { it.labelFile to it.labelCount })
        assertEquals("yamnet_label_list.txt" to 521, ExampleCatalog.audio.let { it.labelFile to it.labelCount })
        assertEquals("labelmap.txt" to 21, ExampleCatalog.segmentation.let { it.labelFile to it.labelCount })
    }

    @Test
    fun repairAddsOnlyMissingLabelsAndKeepsEditedPreprocessingAndRendering() {
        val config = ModelConfig(
            signature = "user-signature",
            inputs = listOf(TensorInputConfig(listOf(16000), InputSource.AUDIO, mean = 12f, std = 5f, sampleRate = 48000)),
            outputs = listOf(TensorOutputConfig(OutputRender.VALUES, scale = 2f, offset = 1f, threshold = 0.6f)),
        )
        val labels = listOf("Speech", "Child speech, kid speaking")
        val repaired = repairCatalogLabels(config, ExampleCatalog.audio, labels)
        assertEquals(config.copy(outputs = listOf(config.outputs.single().copy(labels = labels))), repaired)
        assertEquals(repaired, repairCatalogLabels(repaired, ExampleCatalog.audio, listOf("replacement")))
        assertSame(config, repairCatalogLabels(config, ExampleCatalog.moveNet, emptyList()))
    }

    @Test
    fun detectionRepairDoesNotLabelScoresOrCountsOrReplaceUserClasses() {
        val config = ModelConfig("", emptyList(), listOf(
            TensorOutputConfig(OutputRender.BOXES, labels = listOf("my class"), classesOutput = 2, scoresOutput = 1),
            TensorOutputConfig(), TensorOutputConfig(), TensorOutputConfig(),
        ))
        assertEquals(config, repairCatalogLabels(config, ExampleCatalog.detection, listOf("person", "bicycle")))
        val missing = config.copy(outputs = config.outputs.map { it.copy(labels = emptyList()) })
        val repaired = repairCatalogLabels(missing, ExampleCatalog.detection, listOf("person", "bicycle"))
        assertEquals(listOf("person", "bicycle"), repaired.outputs[0].labels)
        assertEquals(missing.outputs.drop(1), repaired.outputs.drop(1))
    }

    @Test
    fun audioScoresKeepExactLabelIndicesAndRefuseMismatchedFallbacks() {
        assertEquals(
            listOf(SoundLabel("Child speech, kid speaking", 0.9f), SoundLabel("Speech", 0.1f)),
            rankedSoundLabels(listOf("Speech", "Child speech, kid speaking"), doubleArrayOf(0.1, 0.9)),
        )
        assertThrows(IllegalArgumentException::class.java) { rankedSoundLabels(listOf("Speech"), doubleArrayOf(.2, .8)) }
        assertThrows(IllegalArgumentException::class.java) { rankedSoundLabels(listOf(""), doubleArrayOf(.2)) }
        assertThrows(IllegalArgumentException::class.java) { rankedSoundLabels(listOf("Speech"), doubleArrayOf(Double.NaN)) }
    }
}
