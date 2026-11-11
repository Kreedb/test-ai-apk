package com.example.minimalapk.litert.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minimalapk.litert.ImageTaskRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Opt-in REAL model check. Download the pinned NIMA file and prepare nima-lr1-224.png outside the
 * repository, then stage them in the target app's filesDir with adb run-as. Invoke, for example:
 *
 * adb shell am instrument -w -e class com.example.minimalapk.litert.vision.VisionModelFixtureTest
 *   -e visionFixtureDir /data/user/0/com.example.minimalapk/files/vision-fixtures
 *   -e visionBackend cpu com.example.minimalapk.test/androidx.test.runner.AndroidJUnitRunner
 *
 * visionBackend=gpu requires actual GPU selection (a CPU fallback fails the check).
 * Source photo: litert-samples@f9f0e000, samples/litert_interpreter/super_resolution/android/
 * app/src/main/assets/lr-1.jpg (sha256 a6ab0b43b1a13497fa30c747e5f76b33790b380a49c057c2a8f29dbd19e2b80e).
 * Fixture: Pillow RGB resize(224,224,BILINEAR), saved losslessly as PNG, optimize=False.
 * Oracle: ai-edge-litert 2.2.0 CPU on that exact RGB input, mean=3.8151063919067383.
 * Models and photograph are deliberately not bundled in either APK; absent opt-in is a skipped test.
 */
class VisionModelFixtureTest {
    @Test
    fun nimaMatchesPinnedRealPhotoReference() {
        val args = InstrumentationRegistry.getArguments()
        val directoryArg = args.getString("visionFixtureDir")
        assumeTrue("Set visionFixtureDir to opt in to the downloaded real-model fixture", directoryArg != null)
        val directory = File(requireNotNull(directoryArg))
        val model = File(directory, "nima_aesthetic_fp16.tflite")
        assertEquals(6448632L, model.length())
        assertEquals("a5051a0fcced735682735e3e0fd58ee54c83ed664282a003f52235b3dbcb9320", sha256(model))
        val bitmap = requireNotNull(BitmapFactory.decodeFile(File(directory, "nima-lr1-224.png").absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }))
        try {
            assertEquals(224, bitmap.width)
            assertEquals(224, bitmap.height)
            val pixels = IntArray(224 * 224)
            bitmap.getPixels(pixels, 0, 224, 0, 0, 224, 224)
            val rgb = ByteArray(pixels.size * 3)
            for (i in pixels.indices) for (c in 0..2) rgb[i * 3 + c] = (pixels[i] ushr ((2 - c) * 8)).toByte()
            assertEquals("b1124577e67179d7e0cfe33ffe6922b37e2e99fecf3a082ea443dc1c90aa94fb", hex(MessageDigest.getInstance("SHA-256").digest(rgb)))
            val backend = args.getString("visionBackend") ?: "cpu"
            require(backend == "cpu" || backend == "gpu")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            VisionTasks.create("image-quality", context, directory, backend).use { engine ->
                val output = engine.run(ImageTaskRequest(bitmap))
                assertEquals(if (backend == "cpu") "CPU" else "GPU", output.backend)
                assertNull(output.fallbackReason)
                assertTrue(output.inferenceMs > 0)
                assertTrue(output.text.contains("Aesthetic score"))
                val distribution = (output.metrics.getValue("distribution") as List<*>).map { (it as Number).toFloat() }.toFloatArray()
                val tolerance = if (backend == "cpu") 0.015f else 0.035f
                assertArrayEquals(EXPECTED_DISTRIBUTION, distribution, tolerance)
                assertEquals(3.8151064f, (output.metrics.getValue("meanScore") as Number).toFloat(), if (backend == "cpu") 0.07f else 0.18f)
            }
            assertFalse("Engine must not recycle caller's bitmap", bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private val EXPECTED_DISTRIBUTION = floatArrayOf(
            0.0006573044f, 0.19822584f, 0.20451841f, 0.31012231f, 0.19767706f,
            0.056721315f, 0.021774836f, 0.009911168f, 0.0003905871f, 0.0000011207782f,
        )
    }
}
