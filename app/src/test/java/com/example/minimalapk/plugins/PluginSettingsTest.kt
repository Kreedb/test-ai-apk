package com.example.minimalapk.plugins

import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.PoseBackendPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsTest {
    private class MemoryPreferences : PluginPreferences {
        val values = mutableMapOf<String, Any?>()
        val listeners = mutableSetOf<() -> Unit>()
        override fun read(): Map<String, *> = values.toMap()
        override fun write(values: Map<String, Any?>) {
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            listeners.toList().forEach { it() }
        }
        override fun observe(listener: () -> Unit): AutoCloseable {
            listeners += listener
            return AutoCloseable { listeners -= listener }
        }
    }

    @Test
    fun defaultEnhancementIsEnabledButNewModelConsumersAreDisabled() {
        val restored = restorePluginSettings(emptyMap<String, Any>())
        assertTrue(restored.getValue(PluginIds.POSE_ENHANCEMENT).enabled)
        assertFalse(restored.getValue(PluginIds.CONFIGURED_MODEL).enabled)
        assertTrue(restored.getValue(PluginIds.CONFIGURED_MODEL).config.isEmpty())
        assertEquals(PoseEnhancementSettings(), PoseEnhancementSettings.from(restored.getValue(PluginIds.POSE_ENHANCEMENT)))
    }

    @Test
    fun switchAndConfigurationPersistIndependentlyAndNotifyOtherObservers() {
        val preferences = MemoryPreferences()
        preferences.values["pose_models"] = "legacy model list"
        preferences.values["theme_mode"] = "DARK"
        val writer = PluginSettingsStore(preferences)
        val observer = PluginSettingsStore(preferences)
        writer.setEnabled(PluginIds.POSE_ENHANCEMENT, false)
        val config = PoseEnhancementSettings("184", false, true)
        writer.configure(PluginIds.POSE_ENHANCEMENT, config.toConfig())
        val observed = observer.settings.value.getValue(PluginIds.POSE_ENHANCEMENT)
        assertFalse(observed.enabled)
        assertEquals(2L, observed.revision)
        assertEquals(config, PoseEnhancementSettings.from(observed))
        writer.close()
        observer.close()
        assertTrue(preferences.listeners.isEmpty())
        PluginSettingsStore(preferences).use { restored ->
            assertEquals(observed, restored.settings.value.getValue(PluginIds.POSE_ENHANCEMENT))
            restored.setEnabled(PluginIds.POSE_ENHANCEMENT, false)
            assertEquals(2L, restored.settings.value.getValue(PluginIds.POSE_ENHANCEMENT).revision)
        }
        assertEquals("legacy model list", preferences.values["pose_models"])
        assertEquals("DARK", preferences.values["theme_mode"])
    }

    @Test
    fun configurationDoesNotEnableDisabledPluginAndExternalWritesAreObserved() {
        val preferences = MemoryPreferences()
        PluginSettingsStore(preferences).use { store ->
            store.configure(PluginIds.CONFIGURED_MODEL, mapOf("model_id" to "saved-id"))
            assertFalse(store.settings.value.getValue(PluginIds.CONFIGURED_MODEL).enabled)
            preferences.write(mapOf("plugin.configured_model.enabled" to true))
            assertTrue(store.settings.value.getValue(PluginIds.CONFIGURED_MODEL).enabled)
            assertEquals("saved-id", store.settings.value.getValue(PluginIds.CONFIGURED_MODEL).config["model_id"])
        }
    }

    @Test
    fun legacyConfiguredModelSelectionRemainsReadableForMigration() {
        val preferences = MemoryPreferences()
        preferences.values["plugin.configured_model.config.model_id"] = "model-1"
        PluginSettingsStore(preferences).use { store ->
            assertEquals("model-1", preferences.values["plugin.configured_model.config.model_id"])
            assertEquals(
                "model-1",
                store.settings.value.getValue(PluginIds.CONFIGURED_MODEL).config[CONFIGURED_MODEL_ID_KEY],
            )
            // Migrating a selected model must not silently enable the master switch.
            assertFalse(store.settings.value.getValue(PluginIds.CONFIGURED_MODEL).enabled)
        }
    }

    @Test
    fun malformedStoredValuesFallBackWithoutLosingValidConfiguration() {
        val restored = restorePluginSettings(mapOf(
            "plugin.pose_enhancement.enabled" to "wrong type",
            "plugin.pose_enhancement.revision" to -10L,
            "plugin.pose_enhancement.config.height_cm" to "NaN",
            "plugin.pose_enhancement.config.height_line" to "wrong boolean",
        )).getValue(PluginIds.POSE_ENHANCEMENT)
        assertTrue(restored.enabled)
        assertEquals(0L, restored.revision)
        assertEquals(null, PoseEnhancementSettings.from(restored).heightCm)
        assertTrue(PoseEnhancementSettings.from(restored).showHeightLine)
    }

    @Test
    fun explicitRunRequestsAreOneShotRevisionBoundAndNeverRestoredFromPreferences() {
        val preferences = MemoryPreferences()
        PluginSettingsStore(preferences).use { store ->
            store.requestAction(PluginIds.LITERT_TEXT, "run")
            assertTrue(store.requests.value.isEmpty())
            store.setEnabled(PluginIds.LITERT_TEXT, true)
            store.configure(PluginIds.LITERT_TEXT, mapOf("input" to "first"))
            store.requestAction(PluginIds.LITERT_TEXT, "run")
            val first = store.requests.value.getValue(PluginIds.LITERT_TEXT)
            assertEquals(store.settings.value.getValue(PluginIds.LITERT_TEXT).revision, first.revision)
            store.requestAction(PluginIds.LITERT_TEXT, "run")
            val second = store.requests.value.getValue(PluginIds.LITERT_TEXT)
            store.acknowledgeAction(PluginIds.LITERT_TEXT, first)
            assertEquals(second, store.requests.value.getValue(PluginIds.LITERT_TEXT))
            store.acknowledgeAction(PluginIds.LITERT_TEXT, second)
            assertTrue(store.requests.value.isEmpty())
            store.requestAction(PluginIds.LITERT_TEXT, "run")
            store.configure(PluginIds.LITERT_TEXT, mapOf("input" to "changed"))
            assertTrue(store.requests.value.isEmpty())
            store.requestAction(PluginIds.LITERT_TEXT, "run")
            store.setEnabled(PluginIds.LITERT_TEXT, false)
            assertTrue(store.requests.value.isEmpty())
        }
        PluginSettingsStore(preferences).use { assertTrue(it.requests.value.isEmpty()) }
    }

    @Test
    fun changingEnhancementNeverReloadsBaseModelsButBaseChangesInvalidateMeasurements() {
        val base = CameraModelSettings(ModelStore.builtIn, PoseBackendPreference.AUTO)
        val plugins = pluginDefaults + (PluginIds.CONFIGURED_MODEL to PluginSettings(enabled = true))
        val first = cameraPluginSettings(plugins, base, 0)
        val disabled = plugins + (PluginIds.POSE_ENHANCEMENT to plugins.getValue(PluginIds.POSE_ENHANCEMENT).copy(enabled = false))
        val second = cameraPluginSettings(disabled, base, 0)
        assertEquals(first.getValue(PluginIds.POSE_MODELS), second.getValue(PluginIds.POSE_MODELS))
        assertFalse(second.getValue(PluginIds.POSE_ENHANCEMENT).enabled)
        val changedBase = base.copy(specs = base.specs.map { it.copy(enabled = false) })
        val third = cameraPluginSettings(plugins, changedBase, 0)
        assertNotEquals(first.getValue(PluginIds.POSE_ENHANCEMENT), third.getValue(PluginIds.POSE_ENHANCEMENT))
        assertEquals(first.getValue(PluginIds.CONFIGURED_MODEL), third.getValue(PluginIds.CONFIGURED_MODEL))
        val newModelConfig = cameraPluginSettings(plugins, base, 1)
        assertNotEquals(first.getValue(PluginIds.CONFIGURED_MODEL), newModelConfig.getValue(PluginIds.CONFIGURED_MODEL))
        assertEquals(first.getValue(PluginIds.POSE_ENHANCEMENT), newModelConfig.getValue(PluginIds.POSE_ENHANCEMENT))
    }
}
