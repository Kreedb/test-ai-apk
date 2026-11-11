package com.example.minimalapk.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPageTest {
    private enum class LegacyPage { MODELS }

    @Test
    fun removedModelsPageRestoresToTheRoot() {
        assertEquals(SettingsPage.ROOT, settingsPageForRestore("MODELS"))
        assertEquals(SettingsPage.ROOT, settingsPageForRestore(LegacyPage.MODELS))
        assertEquals(SettingsPage.ROOT, settingsPageForRestore(null))
        assertEquals(SettingsPage.ROOT, settingsPageForRestore("NOT_A_PAGE"))
    }

    @Test
    fun existingPagesStillRestoreFromTheirSavedNames() {
        assertEquals(SettingsPage.MIRROR, settingsPageForRestore("MIRROR"))
        assertEquals(SettingsPage.THEME, settingsPageForRestore(SettingsPage.THEME))
    }
}
