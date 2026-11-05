package com.example.minimalapk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.minimalapk.ui.MinimalApkApp
import com.example.minimalapk.ui.theme.MinimalApkTheme
import com.example.minimalapk.ui.theme.ThemeSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeSettings by remember { mutableStateOf(ThemeSettings.load(this)) }
            val darkTheme = themeSettings.mode.isDark(isSystemInDarkTheme())
            val view = LocalView.current

            if (!view.isInEditMode) {
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            }

            MinimalApkTheme(settings = themeSettings) {
                MinimalApkApp(
                    themeSettings = themeSettings,
                    onThemeSettingsChange = { settings ->
                        themeSettings = settings
                        ThemeSettings.save(this, settings)
                    },
                )
            }
        }
    }
}
