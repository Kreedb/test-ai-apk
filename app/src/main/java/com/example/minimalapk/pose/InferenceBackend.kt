package com.example.minimalapk.pose

import android.content.Context
import androidx.core.content.edit

enum class InferenceBackend {
    AUTO,
    NNAPI,
    WEBGPU,
    XNNPACK,
    CPU,
    NCNN;

    val label: String
        get() = when (this) {
            AUTO -> "自动"
            NNAPI -> "NNAPI"
            WEBGPU -> "WebGPU"
            XNNPACK -> "XNNPACK"
            CPU -> "CPU"
            NCNN -> "NCNN（需 ncnn 模型）"
        }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_BACKEND = "inference_backend"

        fun load(context: Context): InferenceBackend =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_BACKEND, null)
                ?.let { runCatching { valueOf(it) }.getOrNull() }
                ?: AUTO

        fun save(context: Context, backend: InferenceBackend) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY_BACKEND, backend.name) }
        }
    }
}
