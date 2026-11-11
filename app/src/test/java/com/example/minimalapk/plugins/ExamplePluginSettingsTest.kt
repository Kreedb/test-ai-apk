package com.example.minimalapk.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamplePluginSettingsTest {
    @Test
    fun newExamplePluginsAreDisabledByDefaultWithValidConfigs() {
        val defaults = restorePluginSettings(emptyMap<String, Any>())
        listOf(
            PluginIds.MOVENET_SCENE, PluginIds.IMAGE_CLASSIFICATION_SCENE,
            PluginIds.OBJECT_DETECTION_SCENE, PluginIds.IMAGE_SEGMENTATION_SCENE,
            PluginIds.AUDIO_CLASSIFICATION, PluginIds.TTS,
        ).forEach { id ->
            assertFalse("$id must be opt-in", defaults.getValue(id).enabled)
        }
        assertEquals(0.3f, SceneThresholdSettings.from(defaults.getValue(PluginIds.MOVENET_SCENE)).threshold!!, 0.0001f)
        assertEquals(0.3f, SceneThresholdSettings.from(defaults.getValue(PluginIds.OBJECT_DETECTION_SCENE)).threshold!!, 0.0001f)
        assertEquals(0.3f, SceneThresholdSettings.from(defaults.getValue(PluginIds.IMAGE_SEGMENTATION_SCENE)).threshold!!, 0.0001f)
        assertEquals(3, AudioClassificationSettings.from(defaults.getValue(PluginIds.AUDIO_CLASSIFICATION)).topK!!)
        assertTrue(defaults.getValue(PluginIds.TTS).config.getValue(TTS_TEXT_KEY).isNotBlank())
    }

    @Test
    fun thresholdsAndTopKRejectOutOfRangeValuesWithoutLosingTheStoredText() {
        assertNull(SceneThresholdSettings("0.01").threshold)
        assertNull(SceneThresholdSettings("0.96").threshold)
        assertNull(SceneThresholdSettings("NaN").threshold)
        assertEquals(0.5f, SceneThresholdSettings("0.5").threshold!!, 0.0001f)

        assertNull(AudioClassificationSettings("0").topK)
        assertNull(AudioClassificationSettings("6").topK)
        assertNull(AudioClassificationSettings("x").topK)
        assertEquals(5, AudioClassificationSettings("5").topK!!)

        val restored = restorePluginSettings(mapOf("plugin.movenet_scene.config.threshold" to "0.45"))
            .getValue(PluginIds.MOVENET_SCENE)
        assertEquals("0.45", SceneThresholdSettings.from(restored).thresholdText)
    }

    @Test
    fun speechTextIsBoundedBeforeItReachesTheSystemEngine() {
        assertEquals("abc", clampTtsText("abc", 3))
        assertEquals("", clampTtsText("abc", 0))
        assertEquals("语音", clampTtsText("语音朗读", 2))
        assertEquals("", clampTtsText("", 10))
    }
}
