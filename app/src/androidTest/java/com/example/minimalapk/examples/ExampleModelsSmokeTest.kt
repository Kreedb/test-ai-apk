package com.example.minimalapk.examples

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.LiveConfiguredModel
import com.example.minimalapk.models.sha256
import com.example.minimalapk.plugins.*
import com.example.minimalapk.pose.PhotoBounds
import com.example.minimalapk.pose.TfliteGraph
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual APK/LiteRT CPU calls for the eight legacy scenes' model-backed paths. Stage the fixed
 * catalog files via `-e exampleModels /data/local/tmp/issue68-models`; tests never download weights.
 * `-e examplePhoto .../grace_hopper.bmp` is google-coral/test_data@104342d2d3480b3e66203073dac24f4e2dbb4c41.
 * Missing fixtures skip explicitly; invalid hashes/protocols/native failures fail the test. */
class ExampleModelsSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val root: File get() = arguments.getString("exampleModels")?.let(::File)
        ?: File(instrumentation.targetContext.filesDir, "examples")

    @Test
    fun yamnetSilenceHasCorrectLabelAndIgnoresBrokenEditableSidecar() {
        val source = model(ExampleCatalog.audio)
        val directory = File(instrumentation.targetContext.cacheDir, "yamnet-contract-${System.nanoTime()}").apply { mkdirs() }
        try {
            val copied = File(directory, "audio.tflite").also { source.copyTo(it) }
            val configured = ConfiguredModel(ExampleCatalog.audio.sha256, "test", copied)
            val invalidConfig = "signature=missing\ninput.0.shape=1,10\ninput.0.sampleRate=48000\noutput.0.labels=wrong"
            configured.configFile.writeText(invalidConfig)
            AudioClassificationRunner(configured).use { runner ->
                assertEquals(15600, runner.windowSamples)
                assertEquals(16000, runner.sampleRate)
                assertEquals(521, runner.labels.size)
                assertEquals("Child speech, kid speaking", runner.labels[1])
                val labels = runner.classify(FloatArray(runner.windowSamples))
                assertEquals("Silence", labels.first().label)
                assertTrue(labels.first().score > .5f)
                assertTrue(labels.all { it.score.isFinite() && it.score in 0f..1f })
                assertEquals(invalidConfig, configured.configFile.readText())
                Log.i(TAG, "YAMNet silence=${labels.take(3)} fixed samples=${runner.windowSamples}; editable sidecar unchanged")
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun classificationDetectionAndSegmentationRecognizePinnedPhotoUsingSceneContracts() {
        val photo = photo()
        try {
            for (spec in listOf(ExampleScenes.classification, ExampleScenes.detection, ExampleScenes.segmentation)) {
                val file = model(spec.example)
                val graph = TfliteGraph(file.readBytes()).describe().signatures.first { it.key.isEmpty() }
                val config = sceneModelConfig(spec.id, graph)
                val inputSize = config.inputs.single().shape[1]
                val input = fittedImage(photo, inputSize, inputSize)
                val labels = ExampleDownload.labels(file, spec.example)
                try {
                    LiveConfiguredModel(ConfiguredModel(spec.example.sha256, spec.name, file), config, graph).use { runner ->
                        val result = runner.runTensors(input.bitmap)
                        when (spec.id) {
                            PluginIds.IMAGE_CLASSIFICATION_SCENE -> {
                                val scores = result.single().values
                                val best = scores.indices.maxBy { scores[it] }
                                assertEquals("military uniform", labels[best])
                                assertTrue(scores[best] > .3)
                                Log.i(TAG, "MobileNet ${labels[best]}=${scores[best]}")
                            }
                            PluginIds.OBJECT_DETECTION_SCENE -> {
                                val objects = sceneDetections(result, input.fit, .5f, labels)
                                assertTrue("Expected person: $objects", objects.any { it.label == "person" })
                                assertTrue(objects.all { it.left in 0f..1f && it.top in 0f..1f && it.right in 0f..1f && it.bottom in 0f..1f })
                                Log.i(TAG, "SSD scene objects=$objects")
                            }
                            PluginIds.IMAGE_SEGMENTATION_SCENE -> {
                                val tensor = result.single()
                                val mask = sceneSegmentation(tensor.values, tensor.shape[2], tensor.shape[1], labels.size, input.fit)
                                val person = labels.indexOf("person")
                                assertTrue(person >= 0)
                                val coverage = mask.counts[person].toDouble() / mask.photoPixels
                                assertTrue("Person coverage=$coverage", coverage > .3)
                                assertEquals(mask.photoPixels, mask.counts.sum())
                                Log.i(TAG, "DeepLab personArea=$coverage excludes model padding")
                            }
                        }
                    }
                } finally { input.bitmap.recycle() }
            }
        } finally { photo.recycle() }
    }

    @Test
    fun moveNetNativeOutputFeedsRealConfidenceGeometryWithoutInventingRepetition() {
        val file = model(ExampleCatalog.moveNet)
        val photo = photo()
        val input = fittedImage(photo, 192, 192)
        try {
            val graph = TfliteGraph(file.readBytes()).describe().signatures.first { it.key.isEmpty() }
            LiveConfiguredModel(ConfiguredModel(ExampleCatalog.moveNet.sha256, "MoveNet", file),
                sceneModelConfig(PluginIds.MOVENET_SCENE, graph), graph).use { runner ->
                val result = runner.runTensors(input.bitmap).single()
                assertEquals(listOf(1, 1, 17, 3), result.shape)
                val points = moveNetPoints(result.values, input.fit, .3f)
                assertTrue("Expected visible shoulders in fixed portrait", points[5] != null && points[6] != null)
                assertTrue(points.filterNotNull().all { it.confidence in .3f..1f })
                val tracker = RaiseCycleTracker()
                val pose = armPosition(points, input.fit.sourceWidth, input.fit.sourceHeight)
                repeat(6) { assertEquals(0, tracker.update(pose, it * 300_000_000L).repetitions) }
                Log.i(TAG, "MoveNet ${moveNetConfidenceMessage(points)} pose=$pose; static photo not a full cycle")
            }
        } finally { input.bitmap.recycle(); photo.recycle() }
    }

    @Test
    fun esrganWholePhotoPipelineUsesActualNativeTilesAndReportsMeasuredCost() {
        val file = model(ExampleCatalog.superResolution)
        val width = 64
        val height = 48
        val sourcePixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            0xff000000.toInt() or ((40 + x * 2) shl 16) or ((50 + y * 2) shl 8) or (if ((x / 8 + y / 8) % 2 == 0) 80 else 170)
        }
        val raw = Bitmap.createBitmap(sourcePixels, width, height, Bitmap.Config.ARGB_8888)
        val space = ImageSpace(width, height, PhotoBounds(0f, 0f, width.toFloat(), height.toFloat()))
        val frame = CameraFrame(ImageSource(1, 1, space, 0), 1, BitmapImage(raw), BitmapImage(raw),
            ViewportMapping(space, width, height), emptyList())
        val plan = superResolutionPlan(width, height)
        val started = System.nanoTime()
        var maxPssKb = Debug.getPss()
        try {
            SuperResolutionRunner(file).use { runner ->
                val result = checkNotNull(superResolutionComposite(frame, plan, runner, { true }) { maxPssKb = maxOf(maxPssKb, Debug.getPss()) })
                try {
                    assertEquals(256, result.width)
                    assertEquals(192, result.height)
                    assertEquals(4, plan.tiles.size)
                    val enhanced = pixels(result)
                    assertTrue(enhanced.all { it ushr 24 == 255 })
                    for ((x, y) in listOf(2 to 2, 252 to 2, 2 to 188, 252 to 188, 160 to 96)) {
                        assertTrue("Native output unexpectedly black at $x,$y", result.getPixel(x, y) and 0xffffff != 0)
                    }
                    assertArrayEquals(sourcePixels, pixels(raw))
                    Log.i(TAG, "ESRGAN CPU full-photo=${width}x$height output=${result.width}x${result.height} " +
                        "tiles=${plan.tiles.size} elapsedMs=${(System.nanoTime() - started) / 1e6} " +
                        "sampledMaxProcessPssKb=$maxPssKb bitmapBytes=${result.allocationByteCount}; not phone/per-frame throughput")
                } finally { result.recycle() }
            }
        } finally { raw.recycle() }
    }

    @Test
    fun personalizationNativeFeaturesTrainRestoreAndRetainNamedCheckpoint() {
        val file = model(ExampleCatalog.personalization)
        val images = intArrayOf(0xffe02020.toInt(), 0xff20d040.toInt(), 0xff2050d0.toInt(), 0xffe0c020.toInt()).map { color ->
            Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }
        val checkpoint = File(instrumentation.targetContext.cacheDir, "personalization-native-${System.nanoTime()}.bin")
        try {
            val samples = BottleneckExtractor(file).use { extractor -> images.mapIndexed { label, bitmap ->
                val features = extractor.extract(bitmap)
                assertEquals(BOTTLENECK_SIZE, features.size)
                assertTrue(features.all { it.isFinite() })
                TrainingSample(features, label)
            } }
            val head = SoftmaxHead(BOTTLENECK_SIZE)
            val before = samples.sumOf { head.predict(it.bottleneck)[it.label].toDouble() }
            repeat(30) { head.train(samples) }
            val after = samples.sumOf { head.predict(it.bottleneck)[it.label].toDouble() }
            assertTrue("Synthetic training should raise matching scores: $before->$after", after > before)
            val profile = PersonalizationProfile(listOf("红色", "绿色", "蓝色", "黄色"))
            head.save(checkpoint, profile)
            val original = checkpoint.readBytes()
            val restored = SoftmaxHead(BOTTLENECK_SIZE).apply { restore(checkpoint) }
            assertEquals(profile, restored.profile)
            samples.forEach { assertArrayEquals(head.predict(it.bottleneck), restored.predict(it.bottleneck), 0f) }
            assertArrayEquals(original, checkpoint.readBytes())
            Log.i(TAG, "Personalization native features + synthetic learning $before->$after; named checkpoint unchanged on restore")
        } finally { images.forEach { it.recycle() }; checkpoint.delete() }
    }

    private fun model(example: ExampleModel): File {
        val file = File(root, "${example.id}.tflite")
        assumeTrue("Stage verified ${example.name} at $file", file.isFile)
        assertEquals(example.sizeBytes, file.length())
        assertEquals(example.sha256, file.readBytes().sha256())
        return file
    }

    private fun photo(): Bitmap {
        val file = arguments.getString("examplePhoto")?.let(::File) ?: File(root, "grace_hopper.bmp")
        assumeTrue("Stage fixed google-coral Grace Hopper fixture at $file", file.isFile)
        assertEquals("c43444bf3b1b1e3080551ca194bd231da4b67f20fc91454502915a6b806b614f", file.readBytes().sha256())
        BitmapFactory.decodeFile(file.path)?.let { return it }
        // Some Android Skia builds omit BMP. This lossless conversion is the same fixed image.
        val png = File(file.parentFile, "grace_hopper.png")
        assumeTrue("This device cannot decode BMP; stage the verified lossless PNG at $png", png.isFile)
        assertEquals("6c870cebe91ab94620210bbc54d578e841c56655922b2daf632aa1d809514913", png.readBytes().sha256())
        return checkNotNull(BitmapFactory.decodeFile(png.path))
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private companion object { const val TAG = "ExampleModelSmoke" }
}
