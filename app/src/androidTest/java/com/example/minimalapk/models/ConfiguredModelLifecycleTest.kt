package com.example.minimalapk.models

import android.util.Base64
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Additional native gaps: real arithmetic, resizing, aliases, secondary subgraph and drain. */
class ConfiguredModelLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withModel(bytes: ByteArray = Base64.decode(MODEL, Base64.DEFAULT), action: (ConfiguredModel, File) -> Unit) {
        val directory = File(context.cacheDir, "configured-lifecycle-${System.nanoTime()}").apply { mkdirs() }
        try {
            action(ConfiguredModelStore.install(directory, "dynamic arithmetic", bytes), directory)
        } finally { directory.deleteRecursively() }
    }

    private fun config(graph: com.example.minimalapk.pose.GraphSignature) = ModelConfig.infer(graph).copy(
        inputs = graph.inputs.map { tensor -> TensorInputConfig(listOf(3), InputSource.NUMBERS,
            values = if (tensor.name in listOf("left", "a")) "1.25,2.5,-4" else "0.5,-1,2") },
        outputs = graph.outputs.map { TensorOutputConfig() },
    )

    @Test
    fun dynamicMultiInputOutputAndTwoSignaturesExecuteExactArithmetic() = withModel { model, _ ->
        val original = model.file.readBytes()
        val description = ConfiguredModelStore.describe(model)
        assertEquals(listOf("", "calculate", "other_graph"), description.signatures.map { it.key })
        assertEquals(1, description.signatures.last().subgraph)
        description.signatures.forEach { graph ->
            assertTrue(graph.inputs.all { it.signature == listOf(-1) })
            val configured = config(graph)
            assertEquals(configured, ModelConfig.decode(configured.encode(), description))
            val output = ConfiguredModelRunner.run(context, model, configured, emptyMap())
            val expected = graph.outputs.mapIndexed { index, tensor ->
                val values = if (tensor.name in listOf("sum", "total")) "1.75, 1.5, -2.0" else "0.75, 3.5, -6.0"
                "输出 $index · ${tensor.name} [3]\n$values"
            }.joinToString("\n\n")
            assertEquals("signature=${graph.key}", expected, output.text)
        }
        assertArrayEquals(original, model.file.readBytes()) // Generic import must not staticize.
    }

    @Test
    fun unselectedSignatureOnlyOutputKeepsDefaultAndOtherSignatureExecutionWorking() {
        val bytes = Base64.decode(MODEL, Base64.DEFAULT).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(864, 1) // Main outputs: [2], not [2, 3].
        }
        withModel(bytes) { model, _ ->
            val description = ConfiguredModelStore.describe(model)
            val main = description.signatures.first()
            assertEquals("", ModelConfig.decode(model.configFile.readText(), description).signature)
            assertEquals("输出 0 · sum [3]\n1.75, 1.5, -2.0",
                ConfiguredModelRunner.run(context, model, config(main), emptyMap()).text)
            val signature = description.signatures.single { it.key == "other_graph" }
            assertEquals("输出 0 · delta [3]\n0.75, 3.5, -6.0\n\n输出 1 · total [3]\n1.75, 1.5, -2.0",
                ConfiguredModelRunner.run(context, model, config(signature), emptyMap()).text)
            assertArrayEquals(bytes, model.file.readBytes())
        }
    }

    @Test
    fun cancelledNativeHandleRejectsOldWorkAndFreshRunnerStillExecutes() = withModel { model, _ ->
        ConfiguredModelStore.describe(model).signatures.forEach { graph ->
            val configured = config(graph)
            var nativeFailure: Throwable? = null
            ConfiguredModelAccess.open(model).use { owner ->
                assertThrows(CancellationException::class.java) {
                    owner.run { interpreter ->
                        configureInputs(interpreter, configured)
                        val inputs = Array<Any>(graph.inputs.size) { index ->
                            encodeTensor(numericValues(configured.inputs[index].values, 3), graph.inputs[index])
                        }
                        // Already inside the borrow: exercise setCancelled + native invoke, not
                        // merely the Kotlin entry checkpoint. Assertions stay outside that catch.
                        owner.cancel()
                        nativeFailure = runCatching { execute(interpreter, configured, graph, inputs, emptyMap()) }.exceptionOrNull()
                    }
                }
                assertNotNull("signature=${graph.key} must honor the native cancellation flag", nativeFailure)
                assertTrue("actual=$nativeFailure", nativeFailure!!.message.orEmpty().contains("cancel", ignoreCase = true))
                assertThrows(CancellationException::class.java) { owner.run { error("cancelled native handle reused") } }
            }
        }
        val graph = ConfiguredModelStore.describe(model).signatures.last()
        assertTrue(ConfiguredModelRunner.run(context, model, config(graph), emptyMap()).text.contains("1.75, 1.5, -2.0"))
    }

    @Test
    fun deleteWaitsForBorrowedNativeOwnerAndDoesNotTouchACachedCopy() = withModel { model, directory ->
        val cache = File(directory, "examples.tflite").apply { writeBytes(model.file.readBytes()) }
        val other = model.copy(file = cache)
        val owner = ConfiguredModelAccess.open(model)
        val independent = ConfiguredModelAccess.open(other)
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        owner.cancellation.onCancel { cancelled.countDown() }
        try {
            val work = pool.submit<Boolean> {
                try {
                    owner.run {
                        entered.countDown()
                        assertTrue(release.await(10, TimeUnit.SECONDS))
                        42
                    }
                    false
                } catch (_: CancellationException) { true }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val deletion = pool.submit { ConfiguredModelStore.delete(model) }
            assertTrue(cancelled.await(10, TimeUnit.SECONDS))
            assertTrue("model cannot be unlinked before native borrower drains", model.file.isFile)
            assertFalse(deletion.isDone)
            release.countDown()
            assertTrue(work.get(10, TimeUnit.SECONDS))
            deletion.get(10, TimeUnit.SECONDS)
            assertFalse(model.file.exists())
            assertFalse(model.configFile.exists())
            assertEquals(2, independent.run { it.inputTensorCount })
            assertTrue(cache.isFile)
        } finally { release.countDown(); owner.close(); independent.close(); pool.shutdownNow() }
    }

    @Test
    fun binaryInt64ValuesAboveDoublePrecisionKeepTheirExactOutputText() = withModel { _, directory ->
        val model = ConfiguredModelStore.install(directory, "exact integers", Base64.decode(INT64_MODEL, Base64.DEFAULT))
        val values = longArrayOf(9007199254740993L, Long.MIN_VALUE, Long.MAX_VALUE)
        val binary = File(directory, "exact.bin").apply {
            writeBytes(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putLong(it) } }.array())
        }
        val graph = ConfiguredModelStore.describe(model).signatures.first()
        val configured = ModelConfig.infer(graph).copy(inputs = listOf(TensorInputConfig(listOf(3), InputSource.BINARY)))
        val output = ConfiguredModelRunner.run(context, model, configured, mapOf(0 to Uri.fromFile(binary)))
        assertEquals("输出 0 · int64_values [3]\n9007199254740993, -9223372036854775808, 9223372036854775807", output.text)
    }

    companion object {
        // Schema-generated ADD + SUB, [1] concrete / [-1] signature; each graph has two inputs
        // and two outputs. Aliases intentionally reverse ordering. 1244 bytes, SHA-256:
        // c8fb66a5dd9121d4c68fbd0ca43bd4a031937551c42848d6b84735d9bb3f00d2
        private const val MODEL = "HAAAAFRGTDMUABgAFAAMABAAAAAIAAAAAAAEABQAAABIAAAAEAAAABgAAACMAQAAAwAAAAEAAAAEAAAAQPz//wIAAAAgAAAAEAAAAAwADAAHAAAAAAAIAAwAAAAAAAApKQAAAGj8//8CAAAAsAAAABQAAAAAAA4AFAAQAAwACAAAAAQADgAAAAEAAAAMAAAAGAAAAFAAAAALAAAAb3RoZXJfZ3JhcGgAAgAAACAAAAAEAAAAGP///wIAAAAEAAAABQAAAHRvdGFsAAAAMP///wMAAAAEAAAABQAAAGRlbHRhAAAAAgAAABgAAAAEAAAAav///wQAAAABAAAAYQAAAGT///8BAAAABAAAAAEAAABiAAoAEAAMAAgABAAKAAAADAAAABgAAABQAAAACQAAAGNhbGN1bGF0ZQAAAAIAAAAgAAAABAAAAKz///8CAAAABAAAAAUAAAB0b3RhbAAAAMT///8DAAAABAAAAAUAAABkZWx0YQAAAAIAAAAoAAAADAAAAAAABgAIAAQABgAAAAQAAAABAAAAYQAAAAgADAAIAAQACAAAAAEAAAAEAAAAAQAAAGIAAAACAAAAgAEAAAQAAACW/v//PAAAACAAAAAoAAAAmAAAAAQAAAAJAAAAc2Vjb25kYXJ5AAAAAgAAAAMAAAACAAAAAgAAAAAAAAABAAAAAgAAADgAAAAEAAAAiv7//ygAAAAAAAAcDAAAABAAAAABAAAAAQAAAAMAAAACAAAAAAAAAAEAAABA/v//ev7//yQAAAAAAAALCAAAAAwAAAABAAAAAgAAAAIAAAAAAAAAAQAAAGz+//8EAAAAlAAAAGQAAAA4AAAABAAAAOT9//8MAAAAEAAAABQAAAABAAAA/////wEAAAABAAAACgAAAGRpZmZlcmVuY2UAABT+//8MAAAAEAAAABQAAAABAAAA/////wEAAAABAAAAAwAAAHN1bQA8/v//DAAAABAAAAAUAAAAAQAAAP////8BAAAAAQAAAAUAAAByaWdodAAAAGj+//8MAAAAEAAAABQAAAABAAAA/////wEAAAABAAAABAAAAGxlZnQAAA4AGAAQAAwACAAEABQADgAAADgAAAAcAAAAJAAAALgAAAAEAAAABAAAAG1haW4AAAAAAgAAAAIAAAADAAAAAgAAAAAAAAABAAAAAgAAAFgAAAAUAAAAAAAOABgAFAAQAAwACwAEAA4AAAAoAAAAAAAAHAwAAAAQAAAAAQAAAAEAAAADAAAAAgAAAAAAAAABAAAAxP///wAADgAUAAAAEAAMAAsABAAOAAAAKAAAAAAAAAsIAAAADAAAAAEAAAACAAAAAgAAAAAAAAABAAAABAAEAAQAAAAEAAAAqAAAAGQAAAA4AAAABAAAAHz///8MAAAAEAAAABQAAAABAAAA/////wEAAAABAAAACgAAAGRpZmZlcmVuY2UAAKz///8MAAAAEAAAABQAAAABAAAA/////wEAAAABAAAAAwAAAHN1bQDU////DAAAABAAAAAUAAAAAQAAAP////8BAAAAAQAAAAUAAAByaWdodAAAABQAEAAIAAAAAAAMAAAAAAAAAAQAFAAAAAwAAAAQAAAAFAAAAAEAAAD/////AQAAAAEAAAAEAAAAbGVmdAAAAAA="
        private const val INT64_MODEL = "GAAAAFRGTDMAAA4AEAAMAAAACAAAAAQADgAAAAwAAAAYAAAAAwAAAAEAAAAIAAAABAAEAAQAAAABAAAAEAAAAAAACgAQAAwACAAEAAoAAAAMAAAAEAAAABQAAAABAAAAAAAAAAEAAAAAAAAAAQAAABgAAAAUABQAEAAHAAAACAAAAAAAAAAMABQAAAAAAAAEDAAAABwAAAAYAAAADAAAAGludDY0X3ZhbHVlcwAAAAABAAAAAwAAAA=="
    }
}
