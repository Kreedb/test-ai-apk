package com.example.minimalapk.ui

import org.junit.Assert.assertEquals
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
}
