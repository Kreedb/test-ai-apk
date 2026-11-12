package com.example.minimalapk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabIndexTest {
    @Test
    fun legacyIndicesKeepTheirDestinations() {
        assertEquals(0, tabForRestoredIndex(0))
        assertEquals(1, tabForRestoredIndex(1))
        assertEquals(2, tabForRestoredIndex(3))
        assertEquals(3, tabForRestoredIndex(4))
    }

    @Test
    fun removedExamplesTabFallsBackToHome() {
        assertEquals(0, tabForRestoredIndex(2))
    }

    @Test
    fun unknownAndOutOfRangeRestoredValuesFallBackToHome() {
        assertEquals(0, tabForRestoredIndex(null))
        assertEquals(0, tabForRestoredIndex(-1))
        assertEquals(0, tabForRestoredIndex(5))
        assertEquals(0, tabForRestoredIndex(99))
        assertEquals(0, tabForRestoredIndex("3"))
        assertEquals(0, tabForRestoredIndex(Unit))
    }

    @Test
    fun numericRestoredValuesUseTheirIntValue() {
        assertEquals(2, tabForRestoredIndex(3L))
        assertEquals(3, tabForRestoredIndex(4.0))
    }

    @Test
    fun currentVersionRoundTripsEveryTab() {
        (0..3).forEach { index ->
            assertEquals(index, tabForRestoredState("v2:$index"))
        }
    }

    @Test
    fun invalidCurrentVersionValuesFallBackToHome() {
        assertEquals(0, tabForRestoredState("v2:9"))
        assertEquals(0, tabForRestoredState("v2:x"))
        assertEquals(0, tabForRestoredState("v2:"))
    }

    @Test
    fun pageStateKeysIsolateLegacyPagesAndRetainEachCurrentDestinationOnReturn() {
        // SaveableStateHolder stores page registries under Any keys. Both ambiguous legacy
        // numeric entries must remain unreachable even when the selected index is migrated.
        val restoredPages = mutableMapOf<Any, String>(2 to "old example form", 3 to "old plugin dialog")
        val plugins = tabPageStateKey(tabForRestoredState(3))
        val settings = tabPageStateKey(tabForRestoredState(4))
        assertEquals("page:v2:plugins", plugins)
        assertEquals("page:v2:settings", settings)
        assertNull(restoredPages[plugins])
        assertNull(restoredPages[settings])
        restoredPages[plugins] = "current plugin dialog"
        restoredPages[settings] = "current settings form"
        assertEquals("current plugin dialog", restoredPages[tabPageStateKey(tabForRestoredState("v2:2"))])
        assertEquals("current settings form", restoredPages[tabPageStateKey(tabForRestoredState("v2:3"))])
        assertEquals(4, (0..3).map(::tabPageStateKey).distinct().size)
        assertNotEquals(tabPageStateKey(0), tabPageStateKey(1))
    }
}
