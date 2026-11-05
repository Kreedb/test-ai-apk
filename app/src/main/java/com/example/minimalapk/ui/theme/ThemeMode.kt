package com.example.minimalapk.ui.theme

import android.content.Context
import androidx.core.content.edit

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_THEME_MODE = "theme_mode"

        fun load(context: Context): ThemeMode {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val value = prefs.getString(KEY_THEME_MODE, null) ?: return SYSTEM
            return runCatching { valueOf(value) }.getOrDefault(SYSTEM)
        }

        fun save(context: Context, mode: ThemeMode) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY_THEME_MODE, mode.name) }
        }
    }
}
