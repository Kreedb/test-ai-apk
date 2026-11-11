package com.example.minimalapk.plugins

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.minimalapk.examples.BOTTLENECK_SIZE
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.ExampleModel
import com.example.minimalapk.examples.SoftmaxHead
import com.example.minimalapk.models.sha256
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real plugin/host/helper interaction; no network, bitmap inference or native runtime needed. */
class ExampleDownloadLifecycleTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun disablingEachImplicitDownloaderDuringSourceLookupKeepsCacheAndCheckpoint() {
        listOf(
            PluginIds.IMAGE_CLASSIFICATION_SCENE to ExampleCatalog.classification,
            PluginIds.SUPER_RESOLUTION to ExampleCatalog.superResolution,
            PluginIds.AUDIO_CLASSIFICATION to ExampleCatalog.audio,
            PluginIds.PERSONALIZATION to ExampleCatalog.personalization,
        ).forEach { (id, example) ->
            val root = folder.newFolder(id)
            val examples = File(root, "examples").apply { mkdir() }
            val oldBytes = "old incomplete cache must not be clobbered".toByteArray()
            val cached = File(examples, "${example.id}.tflite").apply { writeBytes(oldBytes) }
            val checkpoint = File(examples, "personalization-head.bin")
            if (id == PluginIds.PERSONALIZATION) SoftmaxHead(BOTTLENECK_SIZE).save(checkpoint)
            val savedCheckpoint = checkpoint.takeIf { it.isFile }?.readBytes()
            val guardBytes = ByteArray(32) { it.toByte() }
            val guard = ExampleModel("guard", "Guard", "https://example.com/guard", 32L, guardBytes.sha256())
            File(examples, "guard.tflite").writeBytes(guardBytes)

            val sourceEntered = CountDownLatch(1)
            val sourceReturned = CountDownLatch(1)
            val releaseSource = CountDownLatch(1)
            val sourceCalls = AtomicInteger()
            val preferenceCalls = AtomicInteger()
            val context = object : ContextWrapper(null) {
                override fun getFilesDir(): File {
                    // Personalization reads its existing training head before requesting weights.
                    val weightsLookup = if (id == PluginIds.PERSONALIZATION) 2 else 1
                    if (sourceCalls.incrementAndGet() == weightsLookup) {
                        sourceEntered.countDown()
                        check(releaseSource.await(5, TimeUnit.SECONDS))
                        sourceReturned.countDown()
                    }
                    return root
                }

                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                    preferenceCalls.incrementAndGet()
                    error("A disabled load must stop before requesting network settings")
                }
            }
            val plugin = when (id) {
                PluginIds.IMAGE_CLASSIFICATION_SCENE -> ExampleScenePlugin(context, ExampleScenes.classification)
                PluginIds.SUPER_RESOLUTION -> SuperResolutionPlugin(context)
                PluginIds.AUDIO_CLASSIFICATION -> AudioClassificationPlugin(context)
                else -> PersonalizationPlugin(context)
            }
            val verifier = Executors.newSingleThreadExecutor()
            try {
                PluginHost(listOf(plugin)).use { host ->
                    host.configure(mapOf(id to PluginSettings(true)))
                    host.lifecycle(CameraLifecycle.ENTER)
                    host.lifecycle(CameraLifecycle.FOREGROUND)
                    if (id == PluginIds.AUDIO_CLASSIFICATION) {
                        host.permissionChanged(host.sessionId()!!, AudioPermission.GRANTED)
                    }
                    assertTrue("$id must reach the actual cache source", sourceEntered.await(5, TimeUnit.SECONDS))
                    val loadingContext = checkNotNull(host.contextFor(id))
                    host.configure(mapOf(id to PluginSettings(false)))
                    assertFalse(loadingContext.isCurrent())
                    releaseSource.countDown()
                    assertTrue(sourceReturned.await(5, TimeUnit.SECONDS))

                    // The original helper holds the shared cache lock at the source seam. A
                    // second cache-only call returning proves the first load unwound. It also
                    // verifies cancellation did not poison cache reuse for the next consumer.
                    val reusable = verifier.submit<File> { ExampleDownload.ensure(context, guard) }
                    assertEquals(File(examples, "guard.tflite"), reusable.get(5, TimeUnit.SECONDS))
                    assertEquals("$id must not start a download after disable", 0, preferenceCalls.get())
                    assertArrayEquals(oldBytes, cached.readBytes())
                    assertFalse(File(root, "configured_models").exists())
                    if (savedCheckpoint != null) assertArrayEquals(savedCheckpoint, checkpoint.readBytes())
                    assertTrue("Cancellation must not restore a plugin error/result", host.snapshot.value.reports.isEmpty())
                }
            } finally {
                releaseSource.countDown()
                verifier.shutdownNow()
            }
        }
    }
}
