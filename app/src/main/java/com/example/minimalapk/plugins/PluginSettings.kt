package com.example.minimalapk.plugins

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stored IDs, never display names or navigation indices. Keep these when moving entry points. */
internal object PluginIds {
    const val POSE_ENHANCEMENT = "pose_enhancement"
    const val CONFIGURED_MODEL = "configured_model"
    const val POSE_MODELS = "pose_models"
    const val SYSTEM_MONITOR = "system_monitor"
}

/** Stable key inside `plugin.configured_model.config`; shared by the runner and its configuration UI. */
internal const val CONFIGURED_MODEL_ID_KEY = "model_id"

internal data class PluginSettings(
    val enabled: Boolean = false,
    val config: Map<String, String> = emptyMap(),
    val revision: Long = 0,
)

internal data class PoseEnhancementSettings(
    val heightText: String = "170",
    val showHeightLine: Boolean = true,
    val showArmSpan: Boolean = false,
) {
    val heightCm: Float? get() = heightText.toFloatOrNull()?.takeIf { it in 50f..250f }

    fun toConfig(): Map<String, String> = mapOf(
        "height_cm" to heightText,
        "height_line" to showHeightLine.toString(),
        "arm_span" to showArmSpan.toString(),
    )

    companion object {
        fun from(settings: PluginSettings): PoseEnhancementSettings = PoseEnhancementSettings(
            heightText = settings.config["height_cm"] ?: "170",
            showHeightLine = settings.config["height_line"]?.toBooleanStrictOrNull() ?: true,
            showArmSpan = settings.config["arm_span"]?.toBooleanStrictOrNull() ?: false,
        )
    }
}

internal val pluginDefaults = mapOf(
    // Preserve the previously visible enhancement and metrics. New model consumers remain opt-in.
    PluginIds.POSE_ENHANCEMENT to PluginSettings(true, PoseEnhancementSettings().toConfig()),
    PluginIds.CONFIGURED_MODEL to PluginSettings(config = mapOf(CONFIGURED_MODEL_ID_KEY to "")),
    PluginIds.SYSTEM_MONITOR to PluginSettings(true, SystemMonitorSettings().toConfig()),
)

/** Small storage seam: tests exercise the same restore/update code without an Android runtime. */
internal interface PluginPreferences {
    fun read(): Map<String, *>
    fun write(values: Map<String, Any?>)
    fun observe(listener: () -> Unit): AutoCloseable
}

internal class PluginSettingsStore(
    private val preferences: PluginPreferences,
    private val defaults: Map<String, PluginSettings> = pluginDefaults,
) : AutoCloseable {
    constructor(context: Context) : this(AndroidPluginPreferences(context.applicationContext))

    private val mutableSettings = MutableStateFlow(restore())
    val settings: StateFlow<Map<String, PluginSettings>> = mutableSettings.asStateFlow()
    private val observer = preferences.observe { mutableSettings.value = restore() }

    init {
        // Include a write that raced with listener registration.
        mutableSettings.value = restore()
    }

    fun setEnabled(id: String, enabled: Boolean) = update(id) { it.copy(enabled = enabled) }

    fun configure(id: String, config: Map<String, String>) = update(id) { it.copy(config = config.toMap()) }

    @Synchronized
    fun update(id: String, change: (PluginSettings) -> PluginSettings) {
        require(id.matches(Regex("[a-z][a-z0-9_]*")))
        val previous = restore()[id] ?: defaults[id] ?: PluginSettings()
        val changed = change(previous)
        require(changed.config.keys.all { it.matches(Regex("[a-z][a-z0-9_]*")) })
        require(changed.config.size <= 64 && changed.config.values.all { it.length <= 4096 })
        if (changed.enabled == previous.enabled && changed.config == previous.config) return
        val prefix = "plugin.$id."
        val values = mutableMapOf<String, Any?>(
            "${prefix}enabled" to changed.enabled,
            "${prefix}revision" to previous.revision + 1,
        )
        previous.config.keys.forEach { values["${prefix}config.$it"] = null }
        changed.config.forEach { (key, value) -> values["${prefix}config.$key"] = value }
        preferences.write(values)
        mutableSettings.value = restore()
    }

    private fun restore(): Map<String, PluginSettings> = restorePluginSettings(preferences.read(), defaults)

    override fun close() = observer.close()
}

internal fun restorePluginSettings(
    values: Map<String, *>,
    defaults: Map<String, PluginSettings> = pluginDefaults,
): Map<String, PluginSettings> {
    val ids = defaults.keys + values.keys.filter { it.startsWith("plugin.") }
        .map { it.removePrefix("plugin.").substringBefore('.') }
        .filter { it.matches(Regex("[a-z][a-z0-9_]*")) }
    return ids.associateWith { id ->
        val fallback = defaults[id] ?: PluginSettings()
        val prefix = "plugin.$id."
        val config = values.entries.asSequence()
            .filter { it.key.startsWith("${prefix}config.") && it.value is String }
            .take(64)
            .associate { it.key.removePrefix("${prefix}config.") to (it.value as String).take(4096) }
        PluginSettings(
            enabled = values["${prefix}enabled"] as? Boolean ?: fallback.enabled,
            config = fallback.config + config,
            revision = (values["${prefix}revision"] as? Long)?.coerceAtLeast(0) ?: 0,
        )
    }
}

private class AndroidPluginPreferences(context: Context) : PluginPreferences {
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    override fun read(): Map<String, *> = preferences.all

    override fun write(values: Map<String, Any?>) {
        preferences.edit {
            values.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Long -> putLong(key, value)
                }
            }
        }
    }

    override fun observe(listener: () -> Unit): AutoCloseable {
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("plugin.")) listener()
        }
        preferences.registerOnSharedPreferenceChangeListener(callback)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(callback) }
    }
}
