package com.example.minimalapk.models

import com.example.minimalapk.pose.TfliteGraph
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class ConfiguredModelStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun readsNamedSignaturesAndSignedQuantizationWithoutLoadingNativeRuntime() {
        val description = TfliteGraph(fixture()).describe()
        val signature = description.signatures.single { it.key == "predict" }
        assertEquals(1, signature.subgraph)
        assertEquals(listOf("image", "ids"), signature.inputs.map { it.name })
        assertEquals(listOf(1, 3, -1, 4), signature.inputs[0].signature)
        assertEquals(.5f, signature.inputs[0].scale)
        assertEquals(-3L, signature.inputs[0].zeroPoint)
        assertEquals("result", signature.outputs.single().name)
    }

    @Test
    fun preservesUnsupportedPoseModelAndDoesNotOverwriteEditedConfigurationOnReimport() {
        val directory = folder.newFolder()
        val bytes = fixture()
        val model = ConfiguredModelStore.install(directory, "量化多输入", bytes)
        val description = ConfiguredModelStore.describe(model)
        val config = ModelConfig.infer(description.signatures.last()).encode()
        ConfiguredModelStore.saveConfig(model, config, description)
        val importedAgain = ConfiguredModelStore.install(directory, "再次导入", bytes)
        assertEquals(model.id, importedAgain.id)
        assertEquals(config, importedAgain.configFile.readText())
        assertArrayEquals(bytes, importedAgain.file.readBytes())
        assertEquals(1, ConfiguredModelStore.list(directory).size)
        ConfiguredModelStore.delete(importedAgain)
        assertTrue(ConfiguredModelStore.list(directory).isEmpty())
    }

    @Test
    fun truncatedAndOutOfBoundsModelsNeverReachStorage() {
        val directory = folder.newFolder()
        assertThrows(IllegalArgumentException::class.java) { ConfiguredModelStore.install(directory, "broken", fixture().copyOf(60)) }
        val corrupt = fixture().also { for (index in 0..3) it[index] = 0x7f }
        assertThrows(IllegalArgumentException::class.java) { ConfiguredModelStore.install(directory, "broken", corrupt) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun successfulConfigWritesNotifyLiveConsumersEvenForTheSameModelId() {
        val model = ConfiguredModelStore.install(folder.newFolder(), "observable", fixture())
        val description = ConfiguredModelStore.describe(model)
        val before = ConfiguredModelStore.revision.value
        ConfiguredModelStore.saveConfig(model, model.configFile.readText(), description)
        assertEquals(before + 1, ConfiguredModelStore.revision.value)
        assertThrows(IllegalArgumentException::class.java) {
            ConfiguredModelStore.saveConfig(model, "version=invalid", description)
        }
        assertEquals(before + 1, ConfiguredModelStore.revision.value)
        ConfiguredModelStore.delete(model)
        assertEquals(before + 2, ConfiguredModelStore.revision.value)
    }

    @Test
    fun failedAtomicReplacementKeepsTheOldTargetAndCleansTemporaryFile() {
        val target = folder.newFolder("model")
        File(target, "previous").writeText("keep")
        assertThrows(IllegalStateException::class.java) { target.writeAtomically(byteArrayOf(1, 2)) }
        assertEquals("keep", File(target, "previous").readText())
        assertEquals(listOf("model"), folder.root.listFiles()!!.map { it.name })
    }

    @Test
    fun legacySingletonBecomesOneEnabledModelWithoutTouchingBytesConfigOrCheckpoint() {
        val directory = folder.newFolder()
        val model = ConfiguredModelStore.install(directory, "legacy", fixture())
        val bytes = model.file.readBytes()
        val config = model.configFile.readText()
        val checkpoint = File(directory, "personalization.checkpoint").apply { writeText("trained weights") }
        val before = ConfiguredModelStore.revision.value

        assertTrue(ConfiguredModelStore.migrateLegacySelection(directory, model.id))
        assertTrue(ConfiguredModelStore.list(directory).single().enabled)
        assertEquals(before + 1, ConfiguredModelStore.revision.value)
        assertTrue(ConfiguredModelStore.migrateLegacySelection(directory, model.id))
        assertEquals(before + 1, ConfiguredModelStore.revision.value)
        assertArrayEquals(bytes, model.file.readBytes())
        assertEquals(config, model.configFile.readText())
        assertEquals("trained weights", checkpoint.readText())

        ConfiguredModelStore.setEnabled(model, false)
        assertTrue(ConfiguredModelStore.migrateLegacySelection(directory, model.id))
        assertFalse(ConfiguredModelStore.list(directory).single().enabled)
        assertFalse(ConfiguredModelStore.migrateLegacySelection(directory, "missing"))
        assertEquals("trained weights", checkpoint.readText())
    }

    @Test
    fun imageModelTogglePersistsAcrossReimportAndDeletingItDoesNotDeleteOtherFiles() {
        val directory = folder.newFolder()
        val bytes = File("src/main/assets/models/yolo26n_pose.tflite").readBytes()
        val model = ConfiguredModelStore.install(directory, "image", bytes)
        val config = model.configFile.readText()
        assertFalse(model.enabled)
        ConfiguredModelStore.setEnabled(model, true)
        val importedAgain = ConfiguredModelStore.install(directory, "image again", bytes) { error("must preserve saved config") }
        assertTrue(importedAgain.enabled)
        assertTrue(ConfiguredModelStore.list(directory).single().enabled)
        assertEquals(config, importedAgain.configFile.readText())
        ConfiguredModelStore.setEnabled(importedAgain, false)
        assertFalse(ConfiguredModelStore.list(directory).single().enabled)
        val checkpoint = File(directory, "training.bin").apply { writeText("keep") }
        ConfiguredModelStore.delete(importedAgain)
        assertFalse(importedAgain.enabledFile.exists())
        assertEquals("keep", checkpoint.readText())
    }

    @Test
    fun unsupportedCameraInputsStayAvailableForFileRunsWithoutAnEnabledToggle() {
        val model = ConfiguredModelStore.install(folder.newFolder(), "multi-input", fixture())
        val before = ConfiguredModelStore.revision.value
        assertThrows(IllegalArgumentException::class.java) { ConfiguredModelStore.setEnabled(model, true) }
        assertFalse(model.enabledFile.exists())
        assertEquals(before, ConfiguredModelStore.revision.value)
        assertArrayEquals(fixture(), model.file.readBytes())
        assertTrue(model.configFile.isFile)
    }

    // Schema-generated, no operators/weights: two inputs, an INT8 tensor and a named second subgraph.
    private fun fixture(): ByteArray = Base64.getDecoder().decode(
        "HAAAAFRGTDMUABQAEAAAAAwAAAAIAAAAAAAEABQAAAAQAAAAlAAAAKAAAAADAAAAAQAAAAQAAACG/v//AQAAAAwAAAAUAAAALAAAAAcAAABwcmVkaWN0AAEAAAAEAAAAwv///wQAAAAGAAAAcmVzdWx0AAACAAAALAAAAAwAAAAIAAwACAAEAAgAAAABAAAABAAAAAMAAABpZHMAAAAGAAgABAAGAAAABAAAAAUAAABpbWFnZQAAAAEAAAAIAAAABAAEAAQAAAACAAAA9AAAAAQAAAAi////EAAAABgAAAAcAAAAJAAAAAQAAABtYWluAAAAAAEAAAAAAAAAAgAAAAAAAAABAAAAAgAAADQAAAAEAAAAEv///wAAAAEMAAAAAAAAAhAAAAAGAAAAdG9rZW5zAAACAAAAAQAAAAIAAAD6/v//AAAAARQAAAAkAAAAQAAAAAAAAAlEAAAABAAAAAEAAAADAAAA/////wQAAADk/v//CAAAABAAAAABAAAA/f////////8BAAAAAAAAPwYAAABwaXhlbHMAAAQAAAABAAAAAwAAAAIAAAAEAAAAAAAOABQAEAAMAAgAAAAEAA4AAAAQAAAAGAAAABwAAAAkAAAABAAAAG1haW4AAAAAAQAAAAAAAAACAAAAAAAAAAEAAAACAAAAZAAAABwAAAAAABYAFAAQAA8AAAAIAAAAAAAAAAAABwAWAAAAAAAAAQwAAAAAAAACEAAAAAYAAAB0b2tlbnMAAAIAAAABAAAAAgAAAAAAFgAcABgAFwAAABAADAAAAAAACAAHABYAAAAAAAABFAAAADAAAABMAAAAAAAACVAAAAAEAAAAAQAAAAMAAAD/////BAAAAAwADAAAAAAACAAEAAwAAAAIAAAAEAAAAAEAAAD9/////////wEAAAAAAAA/BgAAAHBpeGVscwAABAAAAAEAAAADAAAAAgAAAAQAAAA=",
    )
}
