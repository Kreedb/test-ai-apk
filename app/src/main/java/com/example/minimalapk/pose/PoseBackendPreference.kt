package com.example.minimalapk.pose

import android.content.Context
import androidx.core.content.edit

/**
 * Manual override for the backend probed by [PoseAnalyzer.open]. [backendName] matches the probe
 * names in `PoseAnalyzer.gpuBackends`/`cpuBackends`; null means automatic selection.
 */
enum class PoseBackendPreference(
    val label: String,
    internal val backendName: String?,
    internal val gpu: Boolean,
) {
    AUTO("自动选择", null, false),
    MNN_GPU("MNN (GPU)", "MNN GPU", true),
    LITERT_GPU("LiteRT (GPU)", "LiteRT GPU", true),
    NCNN_VULKAN("ncnn (Vulkan)", "ncnn Vulkan", true),
    LITERT_CPU("LiteRT (CPU)", "LiteRT CPU", false),
    MNN_CPU("MNN (CPU)", "MNN CPU", false),
    NCNN_CPU("ncnn (CPU)", "ncnn CPU", false);

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_BACKEND = "pose_backend"

        fun load(context: Context): PoseBackendPreference = preferenceFromStorage(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_BACKEND, null),
        )

        fun save(context: Context, preference: PoseBackendPreference) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY_BACKEND, preference.name) }
        }
    }
}

internal fun preferenceFromStorage(value: String?): PoseBackendPreference =
    value?.let { name -> PoseBackendPreference.entries.firstOrNull { it.name == name } }
        ?: PoseBackendPreference.AUTO
