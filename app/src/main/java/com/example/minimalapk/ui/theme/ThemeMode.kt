package com.example.minimalapk.ui.theme

import android.content.Context
import androidx.core.content.edit

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

private const val PREFS_NAME = "app_settings"
private const val THEME_MODE_KEY = "theme_mode"

fun loadThemeMode(context: Context): ThemeMode {
    val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(THEME_MODE_KEY, null)
    return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
}

fun saveThemeMode(context: Context, mode: ThemeMode) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit { putString(THEME_MODE_KEY, mode.name) }
}
