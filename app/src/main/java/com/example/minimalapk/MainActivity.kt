package com.example.minimalapk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.minimalapk.ui.MinimalApkApp
import com.example.minimalapk.ui.theme.MinimalApkTheme
import com.example.minimalapk.ui.theme.ThemeMode
import com.example.minimalapk.ui.theme.loadThemeMode
import com.example.minimalapk.ui.theme.saveThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialThemeMode = loadThemeMode(this)
        setContent {
            var themeMode by rememberSaveable { mutableStateOf(initialThemeMode) }
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            MinimalApkTheme(darkTheme = darkTheme) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = !darkTheme
                            isAppearanceLightNavigationBars = !darkTheme
                        }
                    }
                }
                MinimalApkApp(
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        saveThemeMode(this, mode)
                    },
                )
            }
        }
    }
}
