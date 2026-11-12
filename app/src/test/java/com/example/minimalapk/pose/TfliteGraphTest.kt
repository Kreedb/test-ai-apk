package com.example.minimalapk.pose

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.configuredExecutionProblem
import com.example.minimalapk.models.encodeTensor
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class TfliteGraphTest {
    @get:Rule val folder = TemporaryFolder()
    private fun asset(name: String): ByteArray = File("src/main/assets/models/$name").readBytes()

    private fun contractOf(graph: TfliteGraph): PoseContract {
        val input = graph.inputs().single()
        val output = graph.outputs().single()
        return inferPoseContract(
            inputShape = graph.shape(input),
            outputShape = graph.shape(output),
            inputFloat32 = graph.elementType(input) == 0,
            outputFloat32 = graph.elementType(output) == 0,
        ).contract
    }

    @Test
    fun readsTheBundledPoseContract() {
        val graph = TfliteGraph(asset("yolo26n_pose.tflite"))

        assertEquals(PoseContract(640, 8400, 17), contractOf(graph))
        assertEquals(0, graph.dynamicSignatureCount())
    }

    @Test
    fun readsTheBundledPoseContractFromBytes() {
        val graph = TfliteGraph(asset("yolo26n_pose.tflite"))

        assertEquals(PoseContract(640, 8400, 17), contractOf(graph))
        assertEquals(0, graph.dynamicSignatureCount())
    }

    @Test
    fun rewritesDynamicSignatureFromTheConcreteShape() {
        val graph = TfliteGraph(asset("yolo26n_pose.tflite"))
        val output = graph.outputs().single()
        graph.markSignatureDynamic(output)
        assertTrue(graph.dynamicSignatureCount() > 0)

        assertTrue(graph.staticize() > 0)

        assertEquals(0, graph.dynamicSignatureCount())
        assertArrayEquals(intArrayOf(1, 56, 8400), graph.shapeSignature(output))
    }

    @Test
    fun infersAnchorMajorOutputAndWarnsAboutIt() {
        val guess = inferPoseContract(
            inputShape = intArrayOf(1, 640, 640, 3),
            outputShape = intArrayOf(1, 8400, 56),
            inputFloat32 = true,
            outputFloat32 = true,
        )

        assertEquals(OutputLayout.ANCHOR_MAJOR, guess.contract.outputLayout)
        assertEquals(8400, guess.contract.anchors)
        assertEquals(17, guess.contract.keypoints)
        assertTrue(guess.warnings.any { it.contains("锚点优先") })
    }

    @Test
    fun acceptsAnotherKeypointCountAndWarnsThatCocoIndexingStopsAt17() {
        val guess = inferPoseContract(
            inputShape = intArrayOf(1, 640, 640, 3),
            outputShape = intArrayOf(1, 83, 8400),
            inputFloat32 = true,
            outputFloat32 = true,
        )

        assertEquals(26, guess.contract.keypoints)
        assertTrue(guess.warnings.any { it.contains("17") })
    }

    @Test
    fun rejectsLayoutsTheDecoderCannotRead() {
        // 4 box + 1 class + 80 classes is not 5+3K.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 640, 640, 3), intArrayOf(1, 84, 8400), true, true)
        }
        // Quantized I/O has no normalized write path; the config cannot fix the dtype.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 640, 640, 3), intArrayOf(1, 56, 8400), true, false)
        }
        // NCHW input needs a different preprocessing path.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 3, 640, 640), intArrayOf(1, 56, 8400), true, true)
        }
        // A rank-2 output is a classifier/headless graph, not a 3-D pose head.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 640, 640, 3), intArrayOf(1, 1001), true, true)
        }
    }

    @Test
    fun knownSummaryListsOnlyFileBackedTensorFacts() {
        val summary = TfliteGraph(asset("yolo26n_pose.tflite")).describe().knownSummary()

        assertTrue(summary.contains("FLOAT32"))
        assertTrue(summary.contains("形状"))
        assertTrue(summary.contains("签名"))
        assertFalse(summary.contains("mean"))
        assertFalse(summary.contains("labels"))
    }

    @Test
    fun recognizesOnlyTfliteFiles() {
        assertTrue(PoseModelImport.isTflite(asset("yolo26n_pose.tflite")))
        assertFalse(PoseModelImport.isTflite("plain text".toByteArray()))
    }

    @Test
    fun preservesPerAxisQuantizationAndFutureCustomOpsButDoesNotPretendTheyAreUnquantized() {
        val bytes = perAxisFixture()
        val model = ConfiguredModelStore.install(folder.newFolder(), "future custom", bytes)
        val description = ConfiguredModelStore.describe(model)
        assertEquals(listOf("FutureCustomOp"), description.customOps)
        assertEquals(listOf("CUSTOM:FutureCustomOp v3"), description.operators)
        val graph = description.signatures.last()
        val input = graph.inputs.single()
        assertEquals(listOf(.25f, .5f), input.scales)
        assertEquals(listOf(-3L, 7L), input.zeroPoints)
        assertEquals(1, input.quantizedDimension)
        assertTrue(input.scale.isNaN())
        assertEquals(listOf(1, -1), input.signature)
        assertEquals(input.scales, graph.outputs.single().scales)
        assertTrue(description.knownSummary().contains("quantized_dimension=1"))
        assertTrue(description.knownSummary().contains("[0.25, 0.5]"))
        assertTrue(configuredExecutionProblem(ModelConfig.infer(graph), graph)!!.contains("逐轴量化"))
        assertArrayEquals(bytes, model.file.readBytes())
        assertTrue(model.configFile.isFile)
    }

    @Test
    fun rejectsBrokenOperatorTensorSignatureAndBufferReferencesBeforeAnyStorageWrite() {
        // Offsets come from the schema-generated fixture below, not production-parser internals.
        val corruptions = listOf(560 to 99, 188 to 10000, 188 to 1, 400 to 99, 120 to 3, 204 to 0)
        corruptions.forEach { (offset, value) ->
            val bytes = perAxisFixture().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value) }
            val directory = folder.newFolder()
            assertThrows("offset=$offset", IllegalArgumentException::class.java) {
                ConfiguredModelStore.install(directory, "broken", bytes)
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) { TfliteGraph(perAxisFixture().copyOf(190)) }
    }

    @Test
    fun importsSignatureOnlyOutputsWithoutSelectingTheSignature() {
        // Keep ADD/SUB and all four tensors, but expose only sum in the main subgraph's outputs.
        val bytes = arithmeticFixture().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(864, 1) }
        val directory = folder.newFolder()
        val model = ConfiguredModelStore.install(directory, "signature-only output", bytes)
        val description = ConfiguredModelStore.describe(ConfiguredModelStore.list(directory).single())
        val main = description.signatures.first()
        assertEquals("", main.key)
        assertEquals(listOf("sum"), main.outputs.map { it.name })
        val configured = ModelConfig.decode(model.configFile.readText(), description)
        assertEquals("", configured.signature)
        assertEquals(1, configured.outputs.size)
        val signature = description.signatures.single { it.key == "calculate" }
        assertEquals(listOf("delta", "total"), signature.outputs.map { it.name })
        assertEquals(GraphTensor("delta", listOf(1), listOf(-1), 0), signature.outputs.first())
        assertArrayEquals(bytes, model.file.readBytes())
    }

    @Test
    fun rejectsOutOfBoundsSignatureTensorIndicesBeforeAnyStorageWrite() {
        // Explicit input/output TensorMap.tensor_index fields in both subgraphs; each has 4 tensors.
        for (offset in listOf(424, 356, 260, 208)) {
            for (value in listOf(-1, 4)) {
                val bytes = arithmeticFixture().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value) }
                val directory = folder.newFolder()
                val error = assertThrows("offset=$offset value=$value", IllegalArgumentException::class.java) {
                    ConfiguredModelStore.install(directory, "broken signature", bytes)
                }
                assertEquals("TFLite 签名张量索引越界", error.message)
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun availableCatalogGraphsKeepTheirExistingStructureContracts() {
        val directory = System.getProperty("configured.graph.fixtures")?.let(::File)
        org.junit.Assume.assumeTrue("Optional downloaded catalog fixtures", directory?.isDirectory == true)
        listOf("esrgan", "classification", "segmentation", "personalization", "audio", "detection", "movenet", "yolo11n_pose").forEach { id ->
            val description = TfliteGraph(File(directory, "$id.tflite").readBytes()).describe()
            assertTrue(id, description.signatures.isNotEmpty())
            assertTrue(id, description.operators.isNotEmpty())
        }
    }

    @Test
    fun customQuantizationDetailsOverridePlausibleScalarParametersAndBlockConversion() {
        // Schema QuantizationDetails.CustomQuantization=1 plus scale=.5 / zero=-3. The latter
        // are file facts only: the union discriminator makes affine conversion incorrect.
        val bytes = Base64.getDecoder().decode(
            "GAAAAFRGTDMAAA4AEAAMAAAACAAAAAQADgAAAAwAAAAYAAAAAwAAAAEAAAAIAAAABAAEAAQAAAABAAAAEAAAAAAACgAQAAwACAAEAAoAAAAMAAAAEAAAABQAAAABAAAAAAAAAAEAAAAAAAAAAQAAABgAAAAUABgAFAAPAAAACAAEAAAAAAAQABQAAAA8AAAAEAAAAAAAAAkYAAAAFAAAAAgAAABjdXN0b21fcQAAAAABAAAAAgAAABAAFAAAAAAAEAAMAAsABAAQAAAALAAAAAAAAAEIAAAAEAAAAAEAAAD9/////////wEAAAAAAAA/AAAGAAgABAAGAAAABAAAAAQAAAAKFB4o",
        )
        val model = ConfiguredModelStore.install(folder.newFolder(), "custom quantization", bytes)
        val description = ConfiguredModelStore.describe(model)
        val graph = description.signatures.single()
        val input = graph.inputs.single()
        assertEquals(1, input.quantizationDetailsType)
        assertEquals(listOf(.5f), input.scales)
        assertEquals(listOf(-3L), input.zeroPoints)
        assertTrue(input.quantizationSummary.contains("details_type=1"))
        assertTrue(input.quantizationSummary.contains("忽略"))
        assertTrue(description.knownSummary().contains("details_type=1"))
        assertTrue(configuredExecutionProblem(ModelConfig.infer(graph), graph)!!.contains("非仿射量化"))
        assertThrows(IllegalStateException::class.java) { encodeTensor(doubleArrayOf(0.0, 1.0), input) }
        assertArrayEquals(bytes, model.file.readBytes())
        assertTrue(model.configFile.isFile)
    }

    // Same schema-generated ADD/SUB fixture as ConfiguredModelLifecycleTest, including a second subgraph.
    private fun arithmeticFixture() = Base64.getDecoder().decode(
        "HAAAAFRGTDMUABgAFAAMABAAAAAIAAAAAAAEABQAAABIAAAAEAAAABgAAACMAQAAAwAAAAEAAAAEAAAAQPz//wIAAAAgAAAAEAAAAAwADAAHAAAAAAAIAAwAAAAAAAApKQAAAGj8//8CAAAAsAAAABQAAAAAAA4AFAAQAAwACAAAAAQADgAAAAEAAAAMAAAAGAAAAFAAAAALAAAAb3RoZXJfZ3JhcGgAAgAAACAAAAAEAAAAGP///wIAAAAEAAAABQAAAHRvdGFsAAAAMP///wMAAAAEAAAABQAAAGRlbHRhAAAAAgAAABgAAAAEAAAAav///wQAAAABAAAAYQAAAGT///8BAAAABAAAAAEAAABiAAoAEAAMAAgABAAKAAAADAAAABgAAABQAAAACQAAAGNhbGN1bGF0ZQAAAAIAAAAgAAAABAAAAKz///8CAAAABAAAAAUAAAB0b3RhbAAAAMT///8DAAAABAAAAAUAAABkZWx0YQAAAAIAAAAoAAAADAAAAAAABgAIAAQABgAAAAQAAAABAAAAYQAAAAgADAAIAAQACAAAAAEAAAAEAAAAAQAAAGIAAAACAAAAgAEAAAQAAACW/v//PAAAACAAAAAoAAAAmAAAAAQAAAAJAAAAc2Vjb25kYXJ5AAAAAgAAAAMAAAACAAAAAgAAAAAAAAABAAAAAgAAADgAAAAEAAAAiv7//ygAAAAAAAAcDAAAABAAAAABAAAAAQAAAAMAAAACAAAAAAAAAAEAAABA/v//ev7//yQAAAAAAAALCAAAAAwAAAABAAAAAgAAAAIAAAAAAAAAAQAAAGz+//8EAAAAlAAAAGQAAAA4AAAABAAAAOT9//8MAAAAEAAAABQAAAABAAAA/////wEAAAABAAAACgAAAGRpZmZlcmVuY2UAABT+//8MAAAAEAAAABQAAAABAAAA/////wEAAAABAAAAAwAAAHN1bQA8/v//DAAAABAAAAAUAAAAAQAAAP////8BAAAAAQAAAAUAAAByaWdodAAAAGj+//8MAAAAEAAAABQAAAABAAAA/////wEAAAABAAAABAAAAGxlZnQAAA4AGAAQAAwACAAEABQADgAAADgAAAAcAAAAJAAAALgAAAAEAAAABAAAAG1haW4AAAAAAgAAAAIAAAADAAAAAgAAAAAAAAABAAAAAgAAAFgAAAAUAAAAAAAOABgAFAAQAAwACwAEAA4AAAAoAAAAAAAAHAwAAAAQAAAAAQAAAAEAAAADAAAAAgAAAAAAAAABAAAAxP///wAADgAUAAAAEAAMAAsABAAOAAAAKAAAAAAAAAsIAAAADAAAAAEAAAACAAAAAgAAAAAAAAABAAAABAAEAAQAAAAEAAAAqAAAAGQAAAA4AAAABAAAAHz///8MAAAAEAAAABQAAAABAAAA/////wEAAAABAAAACgAAAGRpZmZlcmVuY2UAAKz///8MAAAAEAAAABQAAAABAAAA/////wEAAAABAAAAAwAAAHN1bQDU////DAAAABAAAAAUAAAAAQAAAP////8BAAAAAQAAAAUAAAByaWdodAAAABQAEAAIAAAAAAAMAAAAAAAAAAQAFAAAAAwAAAAQAAAAFAAAAAEAAAD/////AQAAAAEAAAAEAAAAbGVmdAAAAAA=",
    )

    private fun perAxisFixture() = Base64.getDecoder().decode(
        "HAAAAFRGTDMUABgAFAAMABAAAAAIAAAAAAAEABQAAAAUAAAAfAAAAKQAAADcAAAAAwAAAAEAAAAQAAAAAAAKABAADAAIAAQACgAAAAwAAAAUAAAANAAAAAUAAABpbmZlcgAAAAEAAAAMAAAACAAMAAgABAAIAAAAAgAAAAQAAAABAAAAeQAAAAEAAAAEAAAA4v///wQAAAABAAAAeAAAAAIAAAAkAAAADAAAAAAABgAIAAQABgAAAAQAAAACAAAADCIAAAQABAAEAAAAAQAAABAAAAAMABQACwAQAAQADAAMAAAAAwAAAAAAACAgAAAABAAAAA4AAABGdXR1cmVDdXN0b21PcAAAAQAAABQAAAAAAA4AGAAUABAADAAIAAQADgAAABQAAAAsAAAAGAAAABwAAABoAAAABAAAAG1haW4AAAAAAQAAAAIAAAABAAAAAAAAAAEAAAAUAAAAEAAQAAAADAAIAAAAAAAEABAAAAAMAAAAEAAAABQAAAAEAAAABQYHCAEAAAACAAAAAwAAAAAAAAABAAAA/////wMAAADgAAAAiAAAAAQAAABA////FAAAAAAAAAk8AAAARAAAAEwAAAAq////AQAAAAgAAAAYAAAAAgAAAP3/////////BwAAAAAAAAACAAAAAACAPgAAAD8CAAAAAQAAAP////8CAAAAAQAAAAIAAAAGAAAAcmVzdWx0AAAUABgAEAALAAQAFAAAAAAAAAAMABQAAAABAAAAAAAACQwAAAAUAAAAHAAAAAIAAAABAAAAAgAAAAIAAAABAAAAAgAAAAgAAABjb25zdGFudAAAAAAUABoAEAALAAAAFAAEAAAAAAAMABQAAAAoAAAAAAAACVQAAABcAAAAZAAAAAAAEgAQAAAAAAAMAAgAAAAAAAQAEgAAAAEAAAAIAAAAHAAAAAIAAAD9/////////wcAAAAAAAAAAAAAAAIAAAAAAIA+AAAAPwIAAAABAAAA/////wIAAAABAAAAAgAAAAgAAABwZXJfYXhpcwAAAAA=",
    )
}
