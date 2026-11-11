package com.example.minimalapk.plugins

import com.example.minimalapk.litert.audio.AudioTasks
import com.example.minimalapk.litert.imaging.ImagingTasks
import com.example.minimalapk.litert.text.TextDecisionTasks
import com.example.minimalapk.litert.vision.VisionTasks
import com.example.minimalapk.models.AssetCatalog
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiteRtCatalogIntegrationTest {
    @Test fun everySelectedCatalogTaskHasExactlyOneSceneAndItsActualEngineFactory() {
        val file = listOf(File("src/main/assets/models/litert-models.tsv"), File("app/src/main/assets/models/litert-models.tsv"))
            .first { it.isFile }
        val catalog = AssetCatalog.parse(file.readText())
        val routes = LiteRtScenes.all.flatMap { it.tasks }
        val factories = VisionTasks.ids + ImagingTasks.ids + TextDecisionTasks.ids + AudioTasks.ids +
            setOf("speech-recognition", "text-to-speech")
        assertEquals(34, catalog.size)
        assertEquals(34, routes.size)
        assertEquals(routes.size, routes.distinct().size)
        assertEquals(catalog.map { it.id }.toSet(), routes.toSet())
        assertEquals(factories, routes.toSet())
        assertEquals(VisionTasks.ids, LiteRtScenes.vision.tasks.toSet())
        assertEquals(ImagingTasks.ids, LiteRtScenes.image.tasks.toSet())
        assertEquals(TextDecisionTasks.ids, LiteRtScenes.text.tasks.toSet())
        assertEquals(AudioTasks.ids, LiteRtScenes.audio.tasks.toSet())
        assertTrue(LiteRtScenes.all.all { pluginDefaults.getValue(it.id).enabled.not() })
    }

    @Test fun selectedFaceCropIsTheSameNormalizedRegionAsTheVisibleGuide() {
        val roi = LiteRtScenes.faceRoi
        val bounds = liteRtCropBounds(800, 600, roi)
        assertArrayEquals(intArrayOf(200, 108, 600, 492), bounds)
        assertTrue(bounds[0] > 0 && bounds[1] > 0 && bounds[2] < 800 && bounds[3] < 600)
        val inner = LiteRtScenes.livenessFaceRoi
        assertEquals((roi.right - roi.left) / 2.7f, inner.right - inner.left, 0.00001f)
        assertEquals((roi.bottom - roi.top) / 2.7f, inner.bottom - inner.top, 0.00001f)
    }

    @Test fun textDecisionValidationRejectsDuplicateKeysAndBoundsOptionsBeforeLoading() {
        val config = mapOf("input" to "Please help me", "question" to "What is the intent?", "choices" to "a: request\nb: question")
        val valid = liteRtTextRequest(PluginSettings(config = config))
        assertEquals(listOf("a", "b"), valid.options.map { it.first })
        assertTrue(runCatching { liteRtTextRequest(PluginSettings(config = config + ("choices" to "a: one\na: two"))) }.isFailure)
        assertTrue(runCatching { liteRtTextRequest(PluginSettings(config = config + ("choices" to "one"))) }.isFailure)
        assertTrue(runCatching { liteRtTextRequest(PluginSettings(config = config + ("input" to ""))) }.isFailure)
    }
}
