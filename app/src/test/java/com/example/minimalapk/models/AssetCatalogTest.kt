package com.example.minimalapk.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssetCatalogTest {
    @Test
    fun checkedInCatalogParsesEveryPinnedTaskAndFile() {
        val catalog = File("src/main/assets/models/litert-models.tsv")
        assertTrue("缺少生成的模型目录 ${catalog.absolutePath}", catalog.isFile)

        val sets = AssetCatalog.parse(catalog.readText())

        assertEquals(34, sets.size)
        assertEquals(76, sets.sumOf { it.files.size })
        assertEquals(76, sets.flatMap { set -> set.files.map { set.id to it.name } }.toSet().size)
        assertTrue(sets.all { it.files.isNotEmpty() })
        assertTrue(sets.all { set -> set.files.all { it.sha256.length == 64 && it.bytes > 0 } })
        assertTrue(sets.any { it.id == "crowd-counting" && it.files.single().name == "dmcount.tflite" })
        assertTrue(sets.flatMap { it.files }.count { it.bytes > 64L * 1024 * 1024 } == 16)
    }

    @Test
    fun rejectsNonHttpsUrlsButHonoursTheTestSeam() {
        val line = row(url = "http://example.com/model.tflite")

        assertThrows(IllegalArgumentException::class.java) { AssetCatalog.parse(line) }

        val parsed = AssetCatalog.parse(line, allowInsecureUrls = true)
        assertEquals("http://example.com/model.tflite", parsed.single().files.single().url)
    }

    @Test
    fun rejectsTraversalAndSeparatorFileNames() {
        listOf("../model.tflite", "dir/model.tflite", "dir\\model.tflite", "..", ".hidden").forEach { name ->
            assertThrows(name, IllegalArgumentException::class.java) {
                AssetCatalog.parse(row(fileName = name))
            }
        }
    }

    @Test
    fun rejectsDuplicateFileNamesInsideOneSet() {
        val text = listOf(
            row(fileName = "same.tflite"),
            row(fileName = "same.tflite"),
        ).joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) { AssetCatalog.parse(text) }
    }

    @Test
    fun rejectsInvalidSha256() {
        assertThrows(IllegalArgumentException::class.java) {
            AssetCatalog.parse(row(sha256 = "a".repeat(63)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssetCatalog.parse(row(sha256 = "g".repeat(64)))
        }
    }

    @Test
    fun rejectsMissingFieldsAndUnsafeTaskIds() {
        assertThrows(IllegalArgumentException::class.java) {
            AssetCatalog.parse(row().substringBeforeLast('\t'))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssetCatalog.parse(row(taskId = "../escape"))
        }
    }

    @Test
    fun rejectsInconsistentMetadataForTheSameTask() {
        val text = listOf(
            row(fileName = "a.tflite"),
            row(fileName = "b.tflite", name = "另一个模型"),
        ).joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) { AssetCatalog.parse(text) }
    }

    @Test
    fun escapedFieldsRoundTrip() {
        val text = row(name = "Line\\twith\\nbreaks\\\\and slash")
        val set = AssetCatalog.parse(text).single()
        assertEquals("Line\twith\nbreaks\\and slash", set.name)
    }

    private fun row(
        taskId: String = "test-task",
        name: String = "Test Model",
        group: String = "Vision",
        backend: String = "cpu",
        licenseName: String = "Apache-2.0",
        licenseUrl: String = "https://example.com/LICENSE",
        upstream: String = "https://example.com/upstream",
        modelCard: String = "https://example.com/card",
        fileName: String = "model.tflite",
        url: String = "https://example.com/model.tflite",
        bytes: Long = 10L,
        sha256: String = "ab".repeat(32),
    ): String = listOf(
        taskId,
        name,
        group,
        backend,
        licenseName,
        licenseUrl,
        upstream,
        modelCard,
        fileName,
        url,
        bytes.toString(),
        sha256,
    ).joinToString("\t")
}
