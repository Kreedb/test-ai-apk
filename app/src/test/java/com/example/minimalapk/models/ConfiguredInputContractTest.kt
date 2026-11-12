package com.example.minimalapk.models

import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.GraphSignature
import com.example.minimalapk.pose.GraphTensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredInputContractTest {
    private fun graph(shape: List<Int> = listOf(1, 16000), type: Int = 0, scale: Float = 0f) =
        GraphSignature("predict", 0, listOf(GraphTensor("waveform", shape, shape, type, scale)),
            listOf(GraphTensor("scores", listOf(1, 2), listOf(1, 2), 0)))

    @Test
    fun inferenceDoesNotGuessAudioFromShapeButAnExplicitWaveformDeclaresRateAndWindow() {
        val graph = graph()
        val inferred = ModelConfig.infer(graph)
        assertEquals(ConfiguredInputMode.ON_DEMAND, configuredInputContract(inferred, graph).mode)
        val configured = inferred.copy(inputs = listOf(inferred.inputs.single().copy(source = InputSource.AUDIO, sampleRate = 8000)))
        val decoded = ModelConfig.decode(configured.encode(), GraphDescription(listOf(graph), emptyList()))
        val contract = configuredInputContract(decoded, graph)
        assertEquals(ConfiguredInputMode.PCM, contract.mode)
        assertEquals(ConfiguredPcmContract(16000, 8000), contract.pcm)
        assertTrue(contract.description.contains("8000 Hz"))
    }

    @Test
    fun supportedRanksAndQuantizedWaveformsUseOnlyTheSavedContract() {
        for (shape in listOf(listOf(1024), listOf(1, 1024), listOf(1, 1024, 1))) {
            for ((type, scale) in listOf(0 to 0f, 3 to (1f / 128), 9 to (1f / 128), 7 to (1f / 32768))) {
                val graph = graph(shape, type, scale)
                val config = ModelConfig.infer(graph).copy(inputs = listOf(TensorInputConfig(shape, InputSource.AUDIO)))
                assertEquals(ConfiguredPcmContract(1024, 16000), configuredInputContract(config, graph).pcm)
            }
        }
    }

    @Test
    fun spectrogramIntegerPcmAndUndeclaredNormalizationGiveActionableDiagnostics() {
        val cases = listOf(
            graph(listOf(1, 64, 80)) to TensorInputConfig(listOf(1, 64, 80), InputSource.AUDIO),
            graph(type = 7) to TensorInputConfig(listOf(1, 16000), InputSource.AUDIO),
            graph(type = 5) to TensorInputConfig(listOf(1, 16000), InputSource.AUDIO),
            graph() to TensorInputConfig(listOf(1, 16000), InputSource.AUDIO, mean = 127.5f, std = 127.5f),
        )
        cases.forEach { (graph, input) ->
            val contract = configuredInputContract(ModelConfig.infer(graph).copy(inputs = listOf(input)), graph)
            assertEquals(ConfiguredInputMode.ON_DEMAND, contract.mode)
            assertNull(contract.pcm)
            assertTrue(contract.description.contains("未就绪"))
            assertTrue(contract.description.contains("NUMBERS/BINARY") || contract.description.contains("量化"))
            assertNotNull(configuredWaveformProblem(input, graph.inputs.single()))
        }
    }

    @Test
    fun oversizedOrHighRateAudioRemainsFileRunnableButNeverStartsTheMicrophone() {
        for ((shape, rate) in listOf(listOf(1, 16000) to 96000, listOf(1, 480001) to 16000, listOf(1, 240001) to 8000)) {
            val graph = graph(shape)
            val input = TensorInputConfig(shape, InputSource.AUDIO, sampleRate = rate)
            assertNull(configuredWaveformProblem(input, graph.inputs.single()))
            val contract = configuredInputContract(ModelConfig.infer(graph).copy(inputs = listOf(input)), graph)
            assertEquals(ConfiguredInputMode.ON_DEMAND, contract.mode)
            assertNull(contract.pcm)
            assertTrue(contract.description.contains("WAV"))
        }
    }

    @Test
    fun mixedAudioAndTextRemainExplicitOnDemandAndDoNotInventTokenizerOrFeatures() {
        val graph = graph().let { it.copy(inputs = it.inputs + GraphTensor("words", listOf(1), listOf(1), 5)) }
        val config = ModelConfig.infer(graph).let { it.copy(inputs = it.inputs.mapIndexed { i, input ->
            if (i == 0) input.copy(source = InputSource.AUDIO) else input
        }) }
        val contract = configuredInputContract(config, graph)
        assertEquals(ConfiguredInputMode.ON_DEMAND, contract.mode)
        assertTrue(contract.description.contains("混合输入"))
        assertNull(contract.pcm)
        val integer = graph(type = 2)
        val wrongText = ModelConfig.infer(integer).copy(inputs = listOf(TensorInputConfig(listOf(1, 16000), InputSource.TEXT)))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ModelConfig.decode(wrongText.encode(), GraphDescription(listOf(integer), emptyList()))
        }
        assertTrue(failure.message!!.contains("token"))
    }

    @Test
    fun imageNumericalStringAndBinarySourcesKeepTheirSeparateExecutionPaths() {
        val image = graph(listOf(1, 8, 8, 3))
        assertEquals(ConfiguredInputMode.CAMERA, configuredInputContract(ModelConfig.infer(image), image).mode)
        for ((source, type) in listOf(InputSource.NUMBERS to 2, InputSource.TEXT to 5, InputSource.BINARY to 4)) {
            val graph = graph(listOf(2), type)
            val config = ModelConfig.infer(graph).copy(inputs = listOf(TensorInputConfig(listOf(2), source)))
            val decoded = ModelConfig.decode(config.encode(), GraphDescription(listOf(graph), emptyList()))
            assertEquals(source, decoded.inputs.single().source)
            val contract = configuredInputContract(decoded, graph)
            assertEquals(ConfiguredInputMode.ON_DEMAND, contract.mode)
            assertNull(contract.pcm)
        }
    }

    @Test
    fun unsupportedDtypesAndImpossibleRenderContractsAreNotAdvertisedAsReady() {
        val float16 = graph(listOf(1, 8, 8, 3), type = 1)
        val unsupported = configuredInputContract(ModelConfig.infer(float16), float16)
        assertEquals(ConfiguredInputMode.ON_DEMAND, unsupported.mode)
        assertTrue(unsupported.description.contains("未就绪"))
        assertTrue(unsupported.description.contains("FLOAT16"))
        val string = graph().copy(outputs = listOf(GraphTensor("words", listOf(1, 2), listOf(1, 2), 5)))
        assertEquals(OutputRender.VALUES, ModelConfig.infer(string).outputs.single().render)
        val classifiedString = ModelConfig.infer(string).copy(outputs = listOf(TensorOutputConfig(OutputRender.CLASSIFICATION)))
        assertTrue(configuredExecutionProblem(classifiedString, string)!!.contains("STRING"))
        assertNull(configuredExecutionProblem(classifiedString.copy(outputs = listOf(TensorOutputConfig())), string))
        val boxes = graph().let { it.copy(outputs = List(4) { i -> it.outputs.single().copy(name = "out$i") }) }
        val wrongSource = ModelConfig.infer(boxes).let {
            it.copy(outputs = listOf(TensorOutputConfig(OutputRender.BOXES)) + it.outputs.drop(1))
        }
        assertTrue(configuredExecutionProblem(wrongSource, boxes)!!.contains("IMAGE"))
    }
}
