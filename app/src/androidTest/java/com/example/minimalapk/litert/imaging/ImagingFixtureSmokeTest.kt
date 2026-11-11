package com.example.minimalapk.litert.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minimalapk.litert.ImageTaskRequest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Opt-in REAL inference, not a load/shape-only test. Generate inputs + CPU reference renderings
 * with prepare_fixtures.py, stage all model sets using the existing catalog/store, then run:
 *
 * adb shell am instrument -w -e class com.example.minimalapk.litert.imaging.ImagingFixtureSmokeTest \
 *   -e imagingFixtures /data/user/0/com.example.minimalapk/files/imaging-fixtures \
 *   -e imagingTask portrait-matting -e imagingBackend cpu \
 *   com.example.minimalapk.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Omit imagingTask to exercise all nine sequentially. Run each GPU case in a fresh process and
 * use imagingBackend=gpu; this test rejects a CPU fallback masquerading as GPU verification.
 * It logs whole-call latency/native heap deltas and writes actual-<backend>.png for visual review.
 * Empty instrumentation arguments SKIP, never claim model verification.
 */
class ImagingFixtureSmokeTest {
    @Test
    fun pinnedPhotosMatchCpuGoldenAndInputsSurviveRunAndClose() {
        val args = InstrumentationRegistry.getArguments()
        val fixturesPath = args.getString("imagingFixtures")
        assumeTrue("Prepare and stage imaging fixtures to run real model inference", !fixturesPath.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = File(fixturesPath!!)
        val modelRoot = File(args.getString("imagingModels") ?: File(context.filesDir, "model_assets").absolutePath)
        val backend = args.getString("imagingBackend") ?: "cpu"
        require(backend in setOf("cpu", "gpu"))
        val tasks = args.getString("imagingTask")?.let { listOf(it) } ?: ImagingTasks.ids.toList()
        require(tasks.all { it in ImagingTasks.ids })
        for (task in tasks) {
            val directory = File(fixtures, task)
            val reference = JSONObject(File(directory, "reference.json").readText())
            val inputFile = File(directory, "input.png")
            val goldenFile = File(directory, "expected.png")
            assertEquals(reference.getString("inputSha256"), digest(inputFile))
            assertEquals(reference.getString("expectedSha256"), digest(goldenFile))
            val models = reference.getJSONArray("models")
            for (i in 0 until models.length()) {
                val file = models.getJSONObject(i)
                assertEquals("$task/${file.getString("name")}", file.getString("sha256"),
                    digest(File(File(modelRoot, task), file.getString("name"))))
            }
            val source = checkNotNull(BitmapFactory.decodeFile(inputFile.absolutePath))
            val golden = checkNotNull(BitmapFactory.decodeFile(goldenFile.absolutePath))
            var actual: Bitmap? = null
            try {
                val before = imagingPixels(source)
                val heapBefore = Debug.getNativeHeapAllocatedSize()
                val started = System.nanoTime()
                val engine = ImagingTasks.create(task, context, File(modelRoot, task), backend)
                val loadMs = imagingElapsedMs(started)
                val output = try { engine.run(ImageTaskRequest(source)) } finally { engine.close() }
                val bitmap = checkNotNull(output.bitmap)
                actual = bitmap
                assertNotSame(source, bitmap)
                assertFalse(source.isRecycled)
                assertFalse("Published output must survive engine.close", bitmap.isRecycled)
                assertArrayEquals("Engine mutated borrowed source", before, imagingPixels(source))
                assertEquals(golden.width, bitmap.width)
                assertEquals(golden.height, bitmap.height)
                assertTrue(output.inferenceMs.isFinite() && output.inferenceMs > 0)
                assertTrue(output.text.isNotBlank())
                assertEquals("Requested backend was not verified: ${output.fallbackReason}", backend.uppercase(), output.backend)
                val difference = compare(task, bitmap, golden, backend)
                assertMeaning(task, reference.getJSONObject("details"), output.metrics, bitmap, output.text)
                File(directory, "actual-$backend.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i("ImagingFixture", "$task backend=${output.backend} loadMs=$loadMs runAndRenderMs=${output.inferenceMs} " +
                    "nativeBytesDelta=${Debug.getNativeHeapAllocatedSize() - heapBefore} difference=$difference")
            } finally {
                actual?.recycle()
                golden.recycle()
                source.recycle()
            }
        }
    }

    private fun assertMeaning(task: String, expected: JSONObject, metrics: Map<String, Any?>, image: Bitmap, text: String) {
        when (task) {
            "semantic-segmentation" -> {
                val coverage = metrics["classCoverage"] as Map<*, *>
                assertTrue((coverage["road"] as Number).toDouble() > .25)
                assertTrue((coverage["building"] as Number).toDouble() > .1)
                assertTrue((coverage["car"] as Number).toDouble() > .03)
            }
            "lane-detection" -> {
                assertTrue((metrics["drivableFraction"] as Number).toDouble() > .1)
                assertTrue((metrics["laneFraction"] as Number).toDouble() > .001)
            }
            "instance-segmentation" -> {
                assertTrue(text.contains("person"))
                assertEquals(expected.getJSONArray("instances").length(), (metrics["instances"] as Number).toInt())
            }
            "background-removal", "portrait-matting" -> {
                assertTrue((image.getPixel(image.width / 2, image.height / 2) ushr 24) > 178)
                assertTrue((image.getPixel(0, 0) ushr 24) < 77)
                assertTrue(imagingPixels(image).any { (it ushr 24) in 5..250 })
            }
            "monocular-geometry-estimation" -> assertTrue(text.contains("Relative depth") && text.contains("not calibrated"))
        }
    }

    private fun compare(task: String, actual: Bitmap, golden: Bitmap, backend: String): String {
        val a = imagingPixels(actual)
        val g = imagingPixels(golden)
        val alphaOnly = task == "background-removal" || task == "portrait-matting"
        val channels = if (alphaOnly) intArrayOf(24) else intArrayOf(0, 8, 16)
        var error = 0.0
        var samples = 0
        var maskMismatch = 0
        var maskUnion = 0
        for (i in a.indices) {
            // RF-DETR boxes/text depend on Android fonts. Compare the inferred masks over the
            // central photo, away from the border label and the tie's box/label, and check detected
            // categories/count separately above. The golden intentionally contains masks only.
            if (task == "instance-segmentation") {
                val x = i % actual.width
                val y = i / actual.width
                if (x !in actual.width / 10 until actual.width * 9 / 10 ||
                    y !in actual.height / 6 until actual.height * 3 / 5) continue
            }
            for (shift in channels) {
                error += abs(((a[i] ushr shift) and 255) - ((g[i] ushr shift) and 255))
                samples++
            }
            if (alphaOnly) {
                val af = (a[i] ushr 24) >= 128
                val gf = (g[i] ushr 24) >= 128
                if (af != gf) maskMismatch++
                if (af || gf) maskUnion++
            }
        }
        val mae = error / samples
        // CPU comparison tolerates one-byte raster rounding. Segmentation/depth are sensitive
        // to close logit/percentile boundaries; GPU thresholds allow bounded numerical drift.
        val tolerance = if (backend == "gpu") 12.0 else when (task) {
            "semantic-segmentation", "lane-detection", "instance-segmentation", "monocular-geometry-estimation" -> 5.0
            else -> 2.5
        }
        assertTrue("$task $backend rendered MAE $mae exceeds $tolerance", mae < tolerance)
        if (alphaOnly) {
            val iou = 1.0 - maskMismatch.toDouble() / maskUnion.coerceAtLeast(1)
            assertTrue("$task alpha silhouette IoU=$iou", iou > if (backend == "gpu") .94 else .98)
        }
        return "MAE=$mae"
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(bytes)
                if (read < 0) break
                hash.update(bytes, 0, read)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
