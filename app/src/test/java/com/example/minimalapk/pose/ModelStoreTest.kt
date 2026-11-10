package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelStoreTest {
    @Test
    fun roundTripsSpecsThroughStorage() {
        val specs = ModelStore.builtIn.mapIndexed { index, spec ->
            spec.copy(
                enabled = index == 0,
                scoreThreshold = 0.4f,
                keypointThreshold = 0.6f,
                debounceFrames = 3,
                boxFormat = BoxFormat.CENTER,
            )
        }

        assertEquals(specs, ModelStore.decode(ModelStore.encode(specs)))
    }

    @Test
    fun dropsMalformedEntries() {
        val valid = ModelStore.encode(listOf(ModelStore.builtIn.first()))

        assertEquals(1, ModelStore.decode("$valid\nnot-a-spec").size)
    }
}
