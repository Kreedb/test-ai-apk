package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseBackendPreferenceTest {
    @Test
    fun storedPreferenceFallsBackToAuto() {
        assertEquals(PoseBackendPreference.AUTO, preferenceFromStorage(null))
        assertEquals(PoseBackendPreference.AUTO, preferenceFromStorage("unknown"))
    }

    @Test
    fun storedPreferenceRoundTrips() {
        PoseBackendPreference.entries.forEach { preference ->
            assertEquals(preference, preferenceFromStorage(preference.name))
        }
    }

    @Test
    fun everyManualPreferenceMapsToItsOwnProbeName() {
        val names = PoseBackendPreference.entries
            .filter { it != PoseBackendPreference.AUTO }
            .map { checkNotNull(it.backendName) }
        assertEquals(PoseBackendPreference.entries.size - 1, names.distinct().size)
    }
}
