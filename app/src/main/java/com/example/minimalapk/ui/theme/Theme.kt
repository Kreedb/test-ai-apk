package com.example.minimalapk.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

private val LightPrimary = Color(0xFF2E7D32)
private val DarkPrimary = Color(0xFF9CD67D)

@Composable
fun MinimalApkTheme(
    settings: ThemeSettings,
    content: @Composable () -> Unit,
) {
    val darkTheme = settings.mode.isDark(isSystemInDarkTheme())
    MaterialTheme(colorScheme = rememberThemeColorScheme(settings, darkTheme), content = content)
}

@Composable
fun rememberThemeColorScheme(settings: ThemeSettings, darkTheme: Boolean): ColorScheme {
    val context = LocalContext.current
    // Read the system palette only when the user did not pick a custom color; the theme page also
    // calls this for every swatch, and dynamic*ColorScheme builds a full scheme from the wallpaper.
    val seed = if (settings.keyColor != 0) {
        Color(settings.keyColor)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    } else {
        if (darkTheme) DarkPrimary else LightPrimary
    }
    return rememberDynamicColorScheme(
        seedColor = seed,
        isDark = darkTheme,
        style = settings.paletteStyle,
        specVersion = settings.colorSpec.effectiveFor(settings.paletteStyle),
    )
}

internal fun ColorSpec.SpecVersion.effectiveFor(style: PaletteStyle): ColorSpec.SpecVersion =
    if (this == ColorSpec.SpecVersion.SPEC_2025 && style !in SPEC_2025_STYLES) {
        ColorSpec.SpecVersion.SPEC_2021
    } else {
        this
    }

private val SPEC_2025_STYLES = setOf(
    PaletteStyle.TonalSpot,
    PaletteStyle.Neutral,
    PaletteStyle.Vibrant,
    PaletteStyle.Expressive,
)
