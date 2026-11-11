package com.example.minimalapk.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Opt-in real-network download of a >64 MB catalog file. Skipped unless:
 *
 *   LITERT_ASSET_SMOKE=1 ./gradlew testDebugUnitTest --tests '*LargeAssetSmokeTest'
 */
class LargeAssetSmokeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun downloadsAndVerifiesCrowdCountingDmCountThenDeletesIt() {
        Assume.assumeTrue(
            "set LITERT_ASSET_SMOKE=1 to run the real >64 MB download",
            System.getenv("LITERT_ASSET_SMOKE") == "1",
        )
        val catalog = File("src/main/assets/models/litert-models.tsv")
        assertTrue(catalog.isFile)
        val set = AssetCatalog.parse(catalog.readText()).single { it.id == "crowd-counting" }
        val file = set.files.single { it.name == "dmcount.tflite" }
        assertEquals(86_027_724L, file.bytes)

        val root = folder.newFolder("smoke-assets")
        val installer = AssetInstaller(root, HttpAssetTransport())
        try {
            installer.install(set)
            val installed = File(installer.installedDirectory(set), file.name)
            assertEquals(file.bytes, installed.length())
            assertEquals(file.sha256, sha256Of(installed))
            assertTrue(installer.isReady(set))
        } finally {
            installer.delete(set.id)
            assertTrue(!installer.installedDirectory(set).exists())
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
