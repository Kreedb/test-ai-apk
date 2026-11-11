package com.example.minimalapk.diagnostics

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.OutputRender
import com.example.minimalapk.pose.TfliteGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException

class ModelSelfCheckTest {
    @Test
    fun namedMainSubgraphSignatureValidatesDataDependentOutputShape() {
        checkDynamicOutput("infer")
    }

    @Test
    fun indexedMainSubgraphValidatesDataDependentOutputShape() {
        checkDynamicOutput("")
    }

    @Test
    fun largeModelRuntimeInspectionDoesNotCacheTheExecutionOutputShape() {
        checkDynamicOutput("infer", large = true)
    }

    @Test
    fun deletingConfiguredTargetWaitsForSelfCheckNativeBorrowAndSuppressesPass() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "delete-self-check-${System.nanoTime()}").apply { mkdirs() }
        val pool = Executors.newFixedThreadPool(2)
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val model = ConfiguredModelStore.install(directory, "where", Base64.decode(WHERE_MODEL, Base64.DEFAULT))
            val graph = ConfiguredModelStore.describe(model).signatures.first()
            val config = ModelConfig.infer(graph).copy(outputs = listOf(com.example.minimalapk.models.TensorOutputConfig()))
            ConfiguredModelStore.saveConfig(model, config.encode(), ConfiguredModelStore.describe(model))
            val check = pool.submit<Boolean> {
                try {
                    ModelSelfCheck(context, CheckTarget("where", "WHERE", CheckKind.CONFIGURED, file = model.file, configured = model),
                        checkpoint = {}, log = {}, onStage = { stage ->
                            if (stage == "prepare") { preparing.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
                        }).run()
                    false
                } catch (_: CancellationException) { true }
            }
            assertTrue(preparing.await(10, TimeUnit.SECONDS))
            val before = ConfiguredModelStore.revision.value
            val deletion = pool.submit { ConfiguredModelStore.delete(model) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (ConfiguredModelStore.revision.value == before && System.nanoTime() < deadline) Thread.yield()
            assertTrue(ConfiguredModelStore.revision.value > before)
            assertTrue(model.file.isFile)
            assertFalse(deletion.isDone)
            release.countDown()
            assertTrue(check.get(10, TimeUnit.SECONDS))
            deletion.get(10, TimeUnit.SECONDS)
            assertFalse(model.file.exists())
        } finally { release.countDown(); pool.shutdownNow(); directory.deleteRecursively() }
    }

    private fun checkDynamicOutput(signature: String, large: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "dynamic-self-check-${System.nanoTime()}").apply { mkdirs() }
        try {
            val bytes = Base64.decode(WHERE_MODEL, Base64.DEFAULT)
            val description = TfliteGraph(bytes).describe()
            val graph = description.signatures.single { it.key == signature }
            assertEquals(0, graph.subgraph)
            assertEquals(listOf(2), graph.inputs.single().shape)
            assertEquals(listOf(2), graph.inputs.single().signature)
            assertEquals(listOf(1, 1), graph.outputs.single().shape)
            assertEquals(listOf(-1, 1), graph.outputs.single().signature)

            val file = File(directory, "where.tflite").apply { writeBytes(bytes) }
            if (large) RandomAccessFile(file, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
            val model = ConfiguredModel("where", "WHERE", file)
            val inferred = ModelConfig.infer(graph)
            model.configFile.writeText(inferred.copy(
                inputs = inferred.inputs.map { it.copy(values = "1") },
                outputs = inferred.outputs.map { it.copy(render = OutputRender.VALUES) },
            ).encode())
            val logs = mutableListOf<String>()
            val check = ModelSelfCheck(context,
                CheckTarget("where", "WHERE", CheckKind.CONFIGURED, file = file, configured = model),
                checkpoint = {}, log = { logs += it }, onStage = {})

            val result = check.run()

            assertEquals(CheckStatus.PASS, result.status)
            assertTrue(result.inferenceRan)
            assertEquals(1, result.validatedOutputs)
            assertTrue(logs.any { "actualShape=[2, 1]" in it && "bytes=16" in it })
            assertTrue(logs.any { "elements=2 finite=true range=0.0..1.0" in it })
            assertEquals(large, "inspect-runtime" in result.timingsMs)
        } finally { directory.deleteRecursively() }
    }

    companion object {
        // Minimal TFLite v3 WHERE: BOOL x[2] -> INT64 indices[?,1], with infer on subgraph 0.
        // Serialized output shape is [1,1]; two true inputs produce [[0],[1]] without resizing x.
        private const val WHERE_MODEL = """
            HAAAAFRGTDMUABgAFAAMABAAAAAIAAAAAAAEABQAAAAUAAAAGAAAABwAAAAgAAAAAwAAAAEAAAAoAAAA
            AQAAAIgAAAABAAAAkAAAAAEAAACkAAAAAAAKABAADAAIAAQACgAAAAwAAAAUAAAAOAAAAAUAAABpbmZl
            cgAAAAEAAAAMAAAACAAMAAgABAAIAAAAAQAAAAQAAAAHAAAAaW5kaWNlcwABAAAADAAAAAAABgAIAAQA
            BgAAAAQAAAABAAAAeAAAAAQABAAEAAAADAAOAAcAAAAAAAgADAAAAAAAAG1tAAAAAAAOABgAFAAQAAwA
            CAAEAA4AAAAUAAAAHAAAADgAAAA8AAAAQAAAAAQAAABtYWluAAAAAAEAAAAQAAAAAAAKAAwAAAAIAAQA
            CgAAAAgAAAAMAAAAAQAAAAEAAAABAAAAAAAAAAIAAABUAAAABAAAAMj///8QAAAAGAAAAAAAAAQcAAAA
            AgAAAP////8BAAAAAgAAAAEAAAABAAAABwAAAGluZGljZXMAFAAUAAgADwAAABAAAAAAAAAABAAUAAAA
            EAAAABQAAAAAAAAGFAAAAAEAAAACAAAAAQAAAAIAAAABAAAAeAAAAA==
        """
    }
}
