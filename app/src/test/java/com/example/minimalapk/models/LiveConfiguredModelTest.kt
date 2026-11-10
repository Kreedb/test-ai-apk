package com.example.minimalapk.models

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveConfiguredModelTest {
    @Test
    fun imageOnlyModelsCanTakeCameraFrames() {
        assertNull(cameraModelProblem(ModelConfig("", listOf(imageInput), emptyList())))
    }

    @Test
    fun audioAndTextModelsStayInTheWorkbench() {
        assertNotNull(
            cameraModelProblem(
                ModelConfig("", listOf(TensorInputConfig(listOf(1, 4), InputSource.AUDIO)), emptyList()),
            ),
        )
        assertNotNull(
            cameraModelProblem(
                ModelConfig("", listOf(TensorInputConfig(listOf(1, 1), InputSource.TEXT)), emptyList()),
            ),
        )
    }

    private val imageInput = TensorInputConfig(listOf(1, 192, 192, 3), InputSource.IMAGE)
}