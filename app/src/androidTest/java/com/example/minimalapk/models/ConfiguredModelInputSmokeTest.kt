package com.example.minimalapk.models

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minimalapk.plugins.ConfiguredPcmWindow
import com.example.minimalapk.plugins.PcmBlock
import com.example.minimalapk.plugins.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Always-run tiny v3 identity graphs, no download/permission/device microphone required. */
class ConfiguredModelInputSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withModel(fixture: String, run: (ConfiguredModel, File) -> Unit) {
        val directory = File(context.cacheDir, "configured-input-${System.nanoTime()}").apply { mkdirs() }
        try {
            run(ConfiguredModelStore.install(directory, "identity", Base64.decode(fixture, Base64.DEFAULT)), directory)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun numericAndBinaryInputsExecuteBothIndexedAndNamedSignatures() = withModel(FLOAT_MODEL) { model, directory ->
        val description = ConfiguredModelStore.describe(model)
        val binary = File(directory, "input.bin").apply {
            writeBytes(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
                listOf(1.25f, -.5f, 0f, 1f).forEach { putFloat(it) }
            }.array())
        }
        description.signatures.forEach { graph ->
            val config = ModelConfig.infer(graph).copy(
                inputs = listOf(TensorInputConfig(listOf(4), InputSource.NUMBERS, values = "1.25,-0.5,0,1")),
                outputs = listOf(TensorOutputConfig()),
            )
            assertTrue(ConfiguredModelRunner.run(context, model, config, emptyMap()).text.contains("1.25, -0.5, 0.0, 1.0"))
            val raw = config.copy(inputs = config.inputs.map { it.copy(source = InputSource.BINARY) })
            assertTrue(ConfiguredModelRunner.run(context, model, raw, mapOf(0 to Uri.fromFile(binary))).text
                .contains("1.25, -0.5, 0.0, 1.0"))
        }
    }

    @Test
    fun stringTensorPassesUnicodeWithoutInventingATokenizer() = withModel(STRING_MODEL) { model, _ ->
        ConfiguredModelStore.describe(model).signatures.forEach { graph ->
            // Exact equality catches JNI-added NULs even for ASCII; emoji catches modified UTF-8.
            // Explicit NULs and an empty second element must survive rather than be trimmed away.
            for (text in listOf("hello\n世界", "café\n🙂e\u0301", "a\u0000b\n世界\u0000", "hello\n")) {
                val config = ModelConfig.infer(graph).copy(inputs = listOf(TensorInputConfig(listOf(2), InputSource.TEXT, values = text)))
                val output = ConfiguredModelRunner.run(context, model, config, emptyMap())
                val expected = "输出 0 · ${graph.outputs.single().name} [2]\n$text"
                val visible = output.text.replace("\u0000", "\\u0000").replace("\n", "\\n")
                assertEquals("signature=${graph.key.ifEmpty { "indexed" }} actual=$visible\n" +
                    "actual codepoints=${codePoints(output.text)}\nexpected codepoints=${codePoints(expected)}", expected, output.text)
                assertTrue(output.images.isEmpty())
            }
        }
    }

    private fun codePoints(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val point = Character.codePointAt(text, index)
            if (isNotEmpty()) append(' ')
            append("U+").append(point.toString(16).uppercase().padStart(4, '0'))
            index += Character.charCount(point)
        }
    }

    @Test
    fun wavAndSharedPcmUseTheSameConfiguredSampleRateAndRealInterpreter() = withModel(FLOAT_MODEL) { model, directory ->
        val samples = shortArrayOf(-32768, -16384, 0, 8192, 16384, 24576, 32767, 0)
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it) }; flip()
        }
        val wav = File(directory, "input.wav").apply {
            writeBytes(ByteBuffer.allocate(44 + pcm.remaining()).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.remaining()); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.remaining()); put(pcm.duplicate())
            }.array())
        }
        val block = PcmBlock(pcm.asReadOnlyBuffer(), 16000, 1, PcmFormat.PCM_16_LE, 1_000_000L)
        ConfiguredModelStore.describe(model).signatures.forEach { graph ->
            val config = ModelConfig.infer(graph).copy(inputs = listOf(TensorInputConfig(listOf(4), InputSource.AUDIO, sampleRate = 8000)))
            val fromFile = ConfiguredModelRunner.run(context, model, config, mapOf(0 to Uri.fromFile(wav)))
            val window = ConfiguredPcmWindow(configuredInputContract(config, graph).pcm!!).append(block)!!
            ConfiguredPcmModel(model, config, graph).use { runner ->
                assertEquals(fromFile.text, runner.run(window).text)
                assertTrue(fromFile.text.contains("-1.0, 0.0, 0.5, 0.999969482421875"))
            }
        }
    }

    @Test
    fun selectedImageOutputCarriesItsActualInputAndRetainedPreviewOwnsBoundedCopies() = withModel(IMAGE_MODEL) { model, directory ->
        val source = Bitmap.createBitmap(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), 2, 1, Bitmap.Config.ARGB_8888)
        val imageFile = File(directory, "input.png").apply {
            outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        source.recycle()
        val graph = ConfiguredModelStore.describe(model).signatures.first()
        val config = ModelConfig.infer(graph).copy(outputs = listOf(TensorOutputConfig(OutputRender.IMAGE, scale = 255f)))
        val output = ConfiguredModelRunner.run(context, model, config, mapOf(0 to Uri.fromFile(imageFile)))
        assertTrue(output.sourceLabel.contains("所选文件图片"))
        assertEquals(0xffff0000.toInt(), output.inputImage!!.getPixel(0, 0))
        assertEquals(0xff00ff00.toInt(), output.images.single().second.getPixel(1, 0))
        val preview = ConfiguredResultPreview.from(output)
        assertNotSame(output.inputImage, preview.input)
        assertNotSame(output.images.single().second, preview.output)
        output.inputImage.recycle()
        output.images.single().second.recycle()
        assertFalse(preview.input!!.isRecycled)
        assertEquals(0xff00ff00.toInt(), preview.output!!.getPixel(1, 0))
        preview.input.recycle()
        preview.output.recycle()

        val large = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        val bounded = ConfiguredResultPreview.from(ModelRunResult("x".repeat(10000), listOf("a" to large, "b" to large), large, "文件测试"))
        assertEquals(512, bounded.input!!.width)
        assertEquals(256, bounded.input.height)
        assertEquals(512, bounded.output!!.width)
        assertEquals(ConfiguredResultPreview.MAX_TEXT, bounded.text.length)
        assertTrue(bounded.label.contains("首个"))
        large.recycle()
        bounded.input.recycle()
        bounded.output.recycle()
    }

    companion object {
        // Generated from the TFLite schema: input and output reference the same tensor, no ops.
        // All include the main subgraph and an explicit "infer" signature with input/output aliases.
        private const val FLOAT_MODEL = "HAAAAFRGTDMUABgAFAAQAAwAAAAIAAAAAAAEABQAAAAUAAAAgAAAAIwAAAAgAQAAAwAAAAEAAAAQAAAAAAAKABAADAAIAAQACgAAAAwAAAAUAAAALAAAAAUAAABpbmZlcgAAAAEAAAAEAAAA4v///wQAAAAGAAAAb3V0cHV0AAABAAAADAAAAAAABgAIAAQABgAAAAQAAAAFAAAAaW5wdXQAAAABAAAACAAAAAQABAAEAAAAAQAAABQAAAAAAA4AGAAUABAADAAIAAQADgAAABQAAAAcAAAAHAAAACAAAAAkAAAABAAAAG1haW4AAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAABAAAAGAAAABQAEAAMAAAAAAAIAAAAAAAAAAQAFAAAAAwAAAAQAAAAGAAAAAEAAAAEAAAABQAAAGlucHV0AAAAAQAAAAQAAAAAAAAA"
        private const val STRING_MODEL = "HAAAAFRGTDMUABgAFAAQAAwAAAAIAAAAAAAEABQAAAAUAAAAgAAAAIwAAAAkAQAAAwAAAAEAAAAQAAAAAAAKABAADAAIAAQACgAAAAwAAAAUAAAALAAAAAUAAABpbmZlcgAAAAEAAAAEAAAA4v///wQAAAAGAAAAb3V0cHV0AAABAAAADAAAAAAABgAIAAQABgAAAAQAAAAFAAAAaW5wdXQAAAABAAAACAAAAAQABAAEAAAAAQAAABQAAAAAAA4AGAAUABAADAAIAAQADgAAABQAAAAcAAAAHAAAACAAAAAkAAAABAAAAG1haW4AAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAABAAAAGAAAABQAFAAQAA8AAAAIAAAAAAAAAAQAFAAAABAAAAAUAAAAAAAABRgAAAABAAAAAgAAAAUAAABpbnB1dAAAAAEAAAACAAAAAAAAAA=="
        private const val IMAGE_MODEL = "HAAAAFRGTDMUABgAFAAQAAwAAAAIAAAAAAAEABQAAAAUAAAAgAAAAIwAAAA4AQAAAwAAAAEAAAAQAAAAAAAKABAADAAIAAQACgAAAAwAAAAUAAAALAAAAAUAAABpbmZlcgAAAAEAAAAEAAAA4v///wQAAAAGAAAAb3V0cHV0AAABAAAADAAAAAAABgAIAAQABgAAAAQAAAAFAAAAaW5wdXQAAAABAAAACAAAAAQABAAEAAAAAQAAABQAAAAAAA4AGAAUABAADAAIAAQADgAAABQAAAAcAAAAHAAAACAAAAAkAAAABAAAAG1haW4AAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAABAAAAGAAAABQAEAAMAAAAAAAIAAAAAAAAAAQAFAAAAAwAAAAcAAAAJAAAAAQAAAABAAAAAQAAAAIAAAADAAAABQAAAGlucHV0AAAABAAAAAEAAAABAAAAAgAAAAMAAAAAAAAA"
    }
}
