package com.example.minimalapk.plugins

import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.PoseBackendPreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModelSettingsTest {
    private val base = CameraModelSettings(ModelStore.builtIn, PoseBackendPreference.AUTO)
    private val off = pluginDefaults.mapValues { it.value.copy(enabled = false) }

    @Test
    fun allPluginsOffStopsTheHiddenProducerEvenWithSavedEnabledModels() {
        val settings = cameraPluginSettings(off, base, 0)
        assertTrue(settings.values.none { it.enabled })
        val producer = object : CameraPlugin {
            override val descriptor = PluginDescriptor(PluginIds.POSE_MODELS, "姿态模型", setOf(PluginCapability.TEXT))
            override fun onEvent(event: PluginEvent, context: PluginContext) {
                context.publish(PluginOutput(1, listOf(PluginMessage("should never appear"))))
            }
        }
        PluginHost(listOf(producer)).use { host ->
            host.configure(settings)
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            assertTrue(host.snapshot.value.reports.isEmpty())
        }
    }

    @Test
    fun eitherVisiblePoseConsumerCanRunButEnhancementNeverEnablesGenericModels() {
        val enhancementOnly = cameraPluginSettings(off + (PluginIds.POSE_ENHANCEMENT to PluginSettings(true)), base, 0)
        assertTrue(enhancementOnly.getValue(PluginIds.POSE_MODELS).enabled)
        assertFalse(enhancementOnly.getValue(PluginIds.CONFIGURED_MODEL).enabled)
        val customOnly = cameraPluginSettings(off + (PluginIds.CONFIGURED_MODEL to PluginSettings(true)), base, 0)
        assertTrue(customOnly.getValue(PluginIds.POSE_MODELS).enabled)
        assertTrue(customOnly.getValue(PluginIds.CONFIGURED_MODEL).enabled)
        assertFalse(customOnly.getValue(PluginIds.POSE_ENHANCEMENT).enabled)
    }

    @Test
    fun noPoseModelEnabledMeansNoHiddenProducerAndNoNotEnabledStatus() {
        val settings = cameraPluginSettings(
            off + (PluginIds.CONFIGURED_MODEL to PluginSettings(true)),
            base.copy(specs = base.specs.map { it.copy(enabled = false) }), 0,
        )
        assertFalse(settings.getValue(PluginIds.POSE_MODELS).enabled)
        assertTrue(settings.getValue(PluginIds.CONFIGURED_MODEL).enabled)
    }

    @Test
    fun turningOffTheLastConsumerInvalidatesProducerOutputImmediately() {
        val producer = object : CameraPlugin {
            override val descriptor = PluginDescriptor(PluginIds.POSE_MODELS, "姿态模型", setOf(PluginCapability.TEXT))
            override fun onEvent(event: PluginEvent, context: PluginContext) = Unit
        }
        PluginHost(listOf(producer)).use { host ->
            val enabled = cameraPluginSettings(off + (PluginIds.CONFIGURED_MODEL to PluginSettings(true)), base, 0)
            host.configure(enabled)
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            val old = checkNotNull(host.contextFor(PluginIds.POSE_MODELS))
            assertTrue(old.publish(PluginOutput(1, listOf(PluginMessage("loaded")))))
            val disabled = cameraPluginSettings(off, base, 0)
            assertNotEquals(enabled.getValue(PluginIds.POSE_MODELS), disabled.getValue(PluginIds.POSE_MODELS))
            host.configure(disabled)
            assertTrue(host.snapshot.value.reports.isEmpty())
            assertFalse(old.publish(PluginOutput(2, listOf(PluginMessage("late")))))
        }
    }
}
