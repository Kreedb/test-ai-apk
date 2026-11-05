package com.example.minimalapk.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    secondary = Color(0xFF4C662B),
    tertiary = Color(0xFF386A20),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD67D),
    secondary = Color(0xFFB8CC9C),
    tertiary = Color(0xFF9FD67C),
)

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
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicSeed = if (dynamicSupported) {
        (if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    } else {
        if (darkTheme) DarkColors.primary else LightColors.primary
    }
    val seed = if (settings.keyColor == 0) dynamicSeed else Color(settings.keyColor)
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
