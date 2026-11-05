package com.example.minimalapk.ui.theme

import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsTest {
    @Test
    fun spec2025FallsBackForUnsupportedStyle() {
        assertEquals(
            ColorSpec.SpecVersion.SPEC_2021,
            ColorSpec.SpecVersion.SPEC_2025.effectiveFor(PaletteStyle.Fidelity),
        )
    }

    @Test
    fun spec2025StaysForSupportedStyle() {
        assertEquals(
            ColorSpec.SpecVersion.SPEC_2025,
            ColorSpec.SpecVersion.SPEC_2025.effectiveFor(PaletteStyle.TonalSpot),
        )
    }

    @Test
    fun modesResolveDarkMode() {
        assertTrue(ThemeMode.DARK.isDark(false))
        assertFalse(ThemeMode.LIGHT.isDark(true))
        assertTrue(ThemeMode.SYSTEM.isDark(true))
    }

    @Test
    fun storedModeFallsBackAndMigratesAmoled() {
        assertEquals(ThemeMode.LIGHT, themeModeFromStorage("LIGHT"))
        assertEquals(ThemeMode.DARK, themeModeFromStorage("DARK_AMOLED"))
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage("unknown"))
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage(null))
    }
}
