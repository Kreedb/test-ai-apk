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
    fun everyManualPreferenceMatchesAProbe() {
        val manual = PoseBackendPreference.entries.filter { it != PoseBackendPreference.AUTO }
        assertEquals(
            PoseAnalyzer.gpuProbeNames.toSet(),
            manual.filter { it.gpu }.map { checkNotNull(it.backendName) }.toSet(),
        )
        assertEquals(
            PoseAnalyzer.cpuProbeNames.toSet(),
            manual.filterNot { it.gpu }.map { checkNotNull(it.backendName) }.toSet(),
        )
    }
}
