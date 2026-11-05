package com.example.minimalapk.ui.theme

import android.content.Context
import androidx.core.content.edit
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }
}

fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

internal fun themeModeFromStorage(value: String?): ThemeMode =
    if (value == "DARK_AMOLED") {
        ThemeMode.DARK
    } else {
        value?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
    } ?: ThemeMode.SYSTEM

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val keyColor: Int = 0,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2025,
) {
    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_COLOR = "key_color"
        private const val KEY_COLOR_STYLE = "color_style"
        private const val KEY_COLOR_SPEC = "color_spec"

        fun load(context: Context): ThemeSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mode = themeModeFromStorage(prefs.getString(KEY_THEME_MODE, null))
            val paletteStyle = prefs.getString(KEY_COLOR_STYLE, null)
                ?.let { runCatching { PaletteStyle.valueOf(it) }.getOrNull() }
                ?: PaletteStyle.TonalSpot
            val colorSpec = prefs.getString(KEY_COLOR_SPEC, null)
                ?.let { runCatching { ColorSpec.SpecVersion.valueOf(it) }.getOrNull() }
                ?: ColorSpec.SpecVersion.SPEC_2025
            return ThemeSettings(
                mode = mode,
                keyColor = prefs.getInt(KEY_COLOR, 0),
                paletteStyle = paletteStyle,
                colorSpec = colorSpec,
            )
        }

        fun save(context: Context, settings: ThemeSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit {
                    putString(KEY_THEME_MODE, settings.mode.name)
                    putInt(KEY_COLOR, settings.keyColor)
                    putString(KEY_COLOR_STYLE, settings.paletteStyle.name)
                    putString(KEY_COLOR_SPEC, settings.colorSpec.name)
                }
        }
    }
}
