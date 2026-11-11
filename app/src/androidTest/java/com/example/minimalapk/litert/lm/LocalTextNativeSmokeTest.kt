package com.example.minimalapk.litert.lm

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minimalapk.models.AssetImportSource
import com.example.minimalapk.models.AssetResourceStore
import com.example.minimalapk.pose.LiteRtBackend
import com.example.minimalapk.pose.ModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Opt-in REAL generation, cancellation, fresh engine, then bundled pose inference in one process.
 * Provision the pinned .litertlm outside the APK and pass `-e localTextModel /absolute/path`.
 * Missing opt-in is a skipped test, never evidence of generation. An opted-in missing/corrupt
 * resource, insufficient memory, wrong template or native failure is an actual test failure.
 * Run this class on the shrunk release too: debug alone does not exercise JNI/R8 keep rules.
 */
class LocalTextNativeSmokeTest {
    @Test
    fun generateCancelRegenerateThenOpenPoseInSameProcess() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("localTextModel")
        assumeTrue("Set localTextModel to opt into the 1.04 GiB pinned Qwen native fixture", path != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(requireNotNull(path))
        // Real stream/hash/receipt installation in an isolated root, using the same resource
        // manager and leases as production. No direct naked model path is passed to the runner.
        val resources = AssetResourceStore.at(File(context.cacheDir, "lm-smoke-assets"))
        val set = LocalTextModels.assetSets(context).single()
        try {
            resources.importFiles(set, listOf(AssetImportSource(LocalTextModels.FILE_NAME, model.length()) { model.inputStream() }))
            assertTrue(resources.installedDirectory(set) != null)
            runGenerationFixtures(LocalTextRunner(context, resources))
            assertTrue("Per-request packed weights must be removed after native close",
                File(context.noBackupFilesDir, "local_text_compile").listFiles().isNullOrEmpty())
            checkPoseAfterLm()
        } finally {
            resources.delete(set.id)
            assertEquals(null, resources.installedDirectory(set))
        }
    }

    private fun runGenerationFixtures(runner: LocalTextRunner) {
        val arithmetic = LocalTextOptions(
            task = LocalTextTask.QUESTION, text = "What is 17 + 25? Answer with only the number.", maxOutputTokens = 32,
        )
        val chunks = mutableListOf<String>()
        val first = runner.generate(arithmetic, LocalTextCancellation()) { chunks += it }
        assertEquals("42", first.text.trim())
        assertTrue("Actual streaming callback required", chunks.isNotEmpty())
        assertEquals(first.text, chunks.last())
        assertTrue(first.totalTokens in 1..LocalTextModels.CONTEXT_TOKENS)
        assertTrue(requireNotNull(first.firstTextMs) > 0)
        assertTrue(first.elapsedMs >= first.initializationMs)
        assertFalse(first.text.contains("<|"))
        evidence("first-generation", first.text, first.totalTokens, first.elapsedMs)

        val cancellation = LocalTextCancellation()
        val cancelledChunks = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            runner.generate(arithmetic.copy(
                text = "Count from one to one hundred, writing every number as a word.", maxOutputTokens = 128,
            ), cancellation) { text ->
                cancelledChunks += text
                cancellation.cancel()
            }
        }
        assertEquals("No callback publication after cancellation returns", 1, cancelledChunks.size)
        assertTrue(cancelledChunks.single().isNotBlank())
        evidence("cancelled-generation", cancelledChunks.single(), -1, 0.0)

        // A new native Engine is created even after cancellation. Reusing only a Conversation
        // is not a generally valid recurrent-cache reset and is not the contract of this runner.
        val fresh = runner.generate(arithmetic, LocalTextCancellation()) { }
        assertEquals("42", fresh.text.trim())
        assertEquals(first.text, fresh.text)
        evidence("fresh-after-cancel", fresh.text, fresh.totalTokens, fresh.elapsedMs)
    }

    private fun checkPoseAfterLm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val spec = ModelStore.builtIn.single { it.id == "yolo26n_pose" }
        context.assets.open(requireNotNull(spec.assetPath)).use {
            assertEquals("65beb2266be18bd9bb2f1054a2f7a9e1cf48081544ff2cab69a2b5e3b8316f4a", sha256(it))
        }
        // A fixed uniform RGB input has an explicit no-person oracle. This verifies actual
        // model execution and tensor values after LM closes, not accuracy on a human photo.
        val pixels = IntArray(640 * 640) { 0xff727272.toInt() }
        val backend = LiteRtBackend.open(context, spec, useGpu = false)
        try {
            val output = backend.analyze(pixels)
            assertEquals(56 * 8400, output.size)
            assertTrue(output.all { it.isFinite() })
            val maxScore = (0 until 8400).maxOf { output[4 * 8400 + it] }
            assertEquals(0.0018333872f, maxScore, 0.0005f)
            assertTrue("No person in RGB(114,114,114)", maxScore < 0.01f)
            // ai-edge-litert 2.3.0 CPU host oracle, Android LiteRT 2.3.2 tolerance for kernels.
            mapOf(0 to -0.7099342f, 100 to 122.9048f, 8399 to 530.105f,
                16800 to 19.042149f, 42000 to 4.857241f).forEach { (index, expected) ->
                assertEquals("pose output[$index]", expected, output[index], 0.25f)
            }
            assertEquals(0.14517482f, output.last(), 0.02f)
            Log.i(TAG, "pose-after-lm PASS runtime=LiteRT2.3.2 ${backend.name} output=[1,56,8400] maxScore=$maxScore noPerson=true")
        } finally {
            backend.close()
        }
    }

    private fun evidence(stage: String, text: String, tokens: Int, elapsedMs: Double) {
        Log.i(TAG, "$stage runtime=LiteRT-LM${LocalTextModels.RUNTIME_VERSION} api=${Build.VERSION.SDK_INT} " +
            "abi=${Build.SUPPORTED_ABIS.joinToString()} modelRevision=${LocalTextModels.REVISION} " +
            "backendRequested=CPU placement=unmeasured cache=private-request context=1024 tokens=$tokens elapsedMs=$elapsedMs output=$text")
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "LocalTextNativeSmoke"
    }
}
