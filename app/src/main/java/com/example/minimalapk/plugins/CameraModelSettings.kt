package com.example.minimalapk.plugins

import android.content.Context
import android.content.SharedPreferences
import com.example.minimalapk.pose.ModelSpec
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.PoseBackendPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CameraModelSettings(val specs: List<ModelSpec>, val backend: PoseBackendPreference) {
    fun toConfig(): Map<String, String> = mapOf("models" to ModelStore.encode(specs), "backend" to backend.name)
}

/** Include producer dependencies in the consumer's generation without changing stored user options. */
internal fun cameraPluginSettings(
    plugins: Map<String, PluginSettings>,
    base: CameraModelSettings,
    configuredModelRevision: Long,
): Map<String, PluginSettings> {
    val configured = plugins.getValue(PluginIds.CONFIGURED_MODEL)
    val enhancement = plugins.getValue(PluginIds.POSE_ENHANCEMENT)
    val baseConfig = base.toConfig()
    return plugins +
        (PluginIds.POSE_MODELS to PluginSettings(enabled = true, config = baseConfig)) +
        (PluginIds.POSE_ENHANCEMENT to enhancement.copy(config = enhancement.config + mapOf(
            "pose_models" to baseConfig.getValue("models"), "pose_backend" to baseConfig.getValue("backend"),
        ))) +
        (PluginIds.CONFIGURED_MODEL to configured.copy(config = configured.config +
            ("store_revision" to configuredModelRevision.toString())))
}

/** Observe the existing stores, including callers not yet migrated to plugin settings. */
internal class CameraModelSettingsStore(context: Context) : AutoCloseable {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private fun read() = CameraModelSettings(ModelStore.load(application), PoseBackendPreference.load(application))
    private val mutableSettings = MutableStateFlow(read())
    val settings = mutableSettings.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "pose_models" || key == "pose_backend") mutableSettings.value = read()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        mutableSettings.value = read()
    }

    override fun close() = preferences.unregisterOnSharedPreferenceChangeListener(listener)
}
