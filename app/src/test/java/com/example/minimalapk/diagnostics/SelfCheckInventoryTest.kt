package com.example.minimalapk.diagnostics

import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.models.AssetCatalog
import com.example.minimalapk.litert.lm.LocalTextModels
import com.example.minimalapk.pose.ModelCatalog
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.TfliteGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import android.content.ContextWrapper
import com.example.minimalapk.examples.BOTTLENECK_SIZE
import com.example.minimalapk.examples.SoftmaxHead

class SelfCheckInventoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun emptyInstallationStillEnumeratesAllKnownModelsAndEveryCatalogFileWithoutWriting() {
        val filesDir = File(folder.root, "does-not-exist")
        val sets = AssetCatalog.parse(File("src/main/assets/models/litert-models.tsv").readText()) +
            AssetCatalog.parse(File("src/main/assets/${LocalTextModels.ASSET_PATH}").readText())
        val targets = selfCheckInventory(filesDir, emptyList(), sets)
        assertEquals(ModelStore.builtIn.size, targets.count { it.kind == CheckKind.POSE })
        assertEquals(ModelCatalog.models.size, targets.count { it.kind == CheckKind.REFERENCE })
        assertEquals(7, targets.count { it.kind == CheckKind.EXAMPLE })
        assertEquals(77, targets.count { it.kind == CheckKind.CATALOG })
        assertEquals(35, targets.mapNotNull { it.set?.id }.toSet().size)
        val lm = targets.single { it.set?.id == LocalTextModels.QWEN_ID }
        assertEquals(LocalTextModels.SHA256, lm.expectedHash)
        assertEquals(LocalTextModels.MODEL_BYTES, lm.expectedBytes)
        assertTrue(LocalTextModels.selfCheckNote(lm.set).contains("需要专用本地文本场景验证"))
        assertFalse(LocalTextModels.selfCheckNote(lm.set).contains("未集成"))
        assertEquals(targets.size, targets.map { it.id }.toSet().size)
        assertEquals(sets.sumOf { it.files.size }, targets.count { it.set != null })
        assertFalse("Enumeration must not create download/install directories", filesDir.exists())
    }

    @Test
    fun disabledMissingOrphanAndConfiguredModelsAreNotSilentlyDropped() {
        val builtIn = ModelStore.builtIn.single().copy(enabled = false)
        val missing = builtIn.copy(id = "gone", name = "Missing", source = "file:gone.tflite")
        val poseDir = folder.newFolder("models")
        File(poseDir, "orphan.tflite").writeText("test")
        val configured = folder.newFolder("configured_models")
        val installed = "a".repeat(64)
        val dangling = "b".repeat(64)
        File(configured, "$installed.tflite").writeText("test")
        File(configured, "$installed.properties").writeText("invalid config is still an inventory entry")
        File(configured, "$dangling.properties").writeText("model file was removed")
        val examples = folder.newFolder("examples")
        File(examples, "personalization-head.bin").writeText("user checkpoint")
        val checkpointBytes = File(examples, "personalization-head.bin").readBytes()

        val targets = selfCheckInventory(folder.root, listOf(builtIn, missing), emptyList())

        assertFalse(targets.single { it.id == "pose:${builtIn.id}" }.pose!!.enabled)
        assertFalse(targets.single { it.id == "pose:gone" }.file!!.exists())
        assertNotNull(targets.single { it.id == "pose-file:orphan.tflite" })
        assertEquals(setOf(installed, dangling), targets.mapNotNull { it.configured?.id }.toSet())
        assertFalse(targets.single { it.id == "configured:$dangling" }.file!!.exists())
        assertEquals(CheckKind.RESOURCE, targets.single { it.file?.name == "personalization-head.bin" }.kind)
        assertTrue(checkpointBytes.contentEquals(File(examples, "personalization-head.bin").readBytes()))
    }

    @Test
    fun downloadedExamplesAndInstalledConfigurationsGetSeparateChecks() {
        val example = ExampleCatalog.audio
        val configured = folder.newFolder("configured_models")
        File(configured, "${example.sha256}.tflite").writeText("test")
        val examples = folder.newFolder("examples")
        File(examples, "${example.id}.tflite").writeText("test")
        val targets = selfCheckInventory(folder.root, emptyList(), emptyList())
        assertEquals(example, targets.single { it.id == "configured:${example.sha256}" }.example)
        assertEquals(example, targets.single { it.id == "example:${example.id}" }.example)
    }

    @Test
    fun oldCatalogModelFilesRemainVisibleButPartialDownloadsAreNotInstallations() {
        val root = folder.newFolder("model_assets")
        val old = File(root, "removed-task").apply { mkdir() }
        File(old, "legacy.tflite").writeText("test")
        File(old, "future.litertlm").writeText("test")
        val partial = File(root, ".task.partial").apply { mkdir() }
        File(partial, "incomplete.tflite").writeText("partial")
        val targets = selfCheckInventory(folder.root, emptyList(), emptyList())
        assertEquals(2, targets.count { it.id.startsWith("catalog-file:removed-task/") })
        assertFalse(targets.any { it.file?.name == "incomplete.tflite" })
    }

    @Test
    fun unreadableDirectoryIsAnExplicitInventoryFailure() {
        File(folder.root, "configured_models").writeText("a file where a directory should be")
        val targets = selfCheckInventory(folder.root, emptyList(), emptyList())
        assertNotNull(targets.single { it.id == "inventory:configured_models" }.inventoryError)
    }

    @Test
    fun onlyActualInferenceWithValidatedOutputsCanBePass() {
        assertThrows(IllegalArgumentException::class.java) {
            CheckResult("model", CheckStatus.PASS, "ready", "file exists")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckResult("model", CheckStatus.PASS, "load", "loaded", inferenceRan = true)
        }
        val pass = CheckResult("good", CheckStatus.PASS, "validate", "finite", mapOf("load" to 12L), true, 1)
        val missing = CheckResult("missing", CheckStatus.SKIP, "readiness", "MISSING")
        val failed = CheckResult("bad", CheckStatus.FAIL, "validate", "NaN", inferenceRan = true)
        assertEquals("PASS 1 · FAIL 1 · SKIP 1", SelfCheckState(results = listOf(pass, missing, failed)).summary)
        assertTrue(pass.logLine().contains("timingsMs={load=12}"))
        assertTrue(missing.logLine().contains("inference=false"))
        assertTrue(failed.logLine().contains("stage=validate"))
    }

    @Test
    fun signatureSelectionNeverFallsBackToTrainingAndHonoursConfiguredEntry() {
        assertEquals("load", chooseCheckSignature(listOf("train", "save", "load"), null, true))
        assertEquals("predict", chooseCheckSignature(listOf("save", "predict"), null, false))
        assertEquals("my_infer", chooseCheckSignature(listOf("serving_default", "my_infer"), "my_infer", false))
        assertEquals("", chooseCheckSignature(emptyList(), null, false))
        assertThrows(CheckSkipped::class.java) { chooseCheckSignature(listOf("train", "save", "restore"), null, false) }
        assertNull(signatureSkipReason("load", listOf("feature"), true))
        assertNotNull(signatureSkipReason("load", listOf("feature"), false))
        assertNotNull(signatureSkipReason("serving_default", listOf("checkpoint_path"), false))
        assertNotNull(signatureSkipReason("TRAIN", emptyList(), false))
    }

    @Test
    fun missingRuntimeAndOpsAreExplicitSkipsButCorruptionIsFailure() {
        assertNotNull(unsupportedRuntimeReason(UnsatisfiedLinkError("libLiteRt.so")))
        assertNotNull(unsupportedRuntimeReason(IllegalArgumentException("Select TF ops need the Flex delegate")))
        assertNotNull(unsupportedRuntimeReason(IllegalStateException("load", RuntimeException("unresolved custom op Foo"))))
        assertNull(unsupportedRuntimeReason(IllegalArgumentException("Buffer has wrong size")))
        assertNull(unsupportedRuntimeReason(IllegalArgumentException("SHA-256 mismatch")))
    }

    @Test
    fun bundledModelContractFitsTheSelfCheckBudgetAndUsesTheConfiguredPoseLayout() {
        val pose = ModelStore.builtIn.single()
        val description = TfliteGraph(File("src/main/assets/${pose.assetPath}").readBytes()).describe()
        val main = description.signatures.first()
        assertEquals(listOf(1, pose.inputSize, pose.inputSize, 3), main.inputs.single().shape)
        assertEquals(listOf(1, pose.valuesPerAnchor, pose.anchors), main.outputs.single().shape)
        assertTrue(checkInputElements(main.inputs.single().shape) > 0)
        assertTrue(checkInputElements(main.outputs.single().shape) > 0)
        assertNull(signatureSkipReason(main.key, main.inputs.map { it.name }, false))
    }

    @Test
    fun localLearnedHeadRunsReadOnlyAndCorruptCheckpointFails() {
        val file = File(folder.root, "personalization-head.bin")
        SoftmaxHead(BOTTLENECK_SIZE).save(file)
        val before = file.readBytes()
        val target = CheckTarget("resource:personalization-head", "分类头", CheckKind.RESOURCE, file = file)
        val check = ModelSelfCheck(ContextWrapper(null), target, {}, {}, {})
        val result = check.run()
        assertEquals(CheckStatus.PASS, result.status)
        assertTrue(result.inferenceRan)
        assertTrue(before.contentEquals(file.readBytes()))
        file.writeBytes(before.copyOf(20))
        assertThrows(IllegalArgumentException::class.java) {
            ModelSelfCheck(ContextWrapper(null), target, {}, {}, {}).run()
        }
    }
}
