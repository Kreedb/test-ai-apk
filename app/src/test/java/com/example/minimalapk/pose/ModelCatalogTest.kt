package com.example.minimalapk.pose

import com.example.minimalapk.examples.ExampleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun remoteMenuContainsEveryPreviousCatalogAndPreservesDownloadVerification() {
        val examples = ExampleCatalog.configurable + listOf(ExampleCatalog.superResolution, ExampleCatalog.personalization)
        examples.forEach { example ->
            val remote = ModelCatalog.models.single { it.url == example.url }
            assertEquals(example.sizeBytes, remote.sizeBytes)
            assertEquals(example.sha256, remote.sha256)
        }
        assertTrue(ModelCatalog.models.any { it.name.startsWith("YOLO11") && it.boxFormat == BoxFormat.CENTER })
        assertEquals(ModelCatalog.models.size, ModelCatalog.models.map { it.url }.distinct().size)
    }
}
