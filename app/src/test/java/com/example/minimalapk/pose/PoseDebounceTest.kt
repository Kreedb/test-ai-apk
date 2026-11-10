package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseDebounceTest {
    @Test
    fun debounceOffPublishesEveryFrame() {
        val debounce = PoseDebounce(1)

        assertTrue(debounce.accept(true))
        assertFalse(debounce.accept(false))
    }

    @Test
    fun publishesOnlyAfterConsecutiveAgreeingFrames() {
        val debounce = PoseDebounce(3)

        assertFalse(debounce.accept(true))
        assertFalse(debounce.accept(true))
        // One disagreeing frame restarts the run.
        assertFalse(debounce.accept(false))
        assertFalse(debounce.accept(true))
        assertFalse(debounce.accept(true))
        assertTrue(debounce.accept(true))

        // Staying in the published state never accumulates a run.
        assertTrue(debounce.accept(true))
        assertTrue(debounce.accept(false))
        assertTrue(debounce.accept(false))
        assertFalse(debounce.accept(false))
    }
}
