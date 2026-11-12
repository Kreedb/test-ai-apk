package com.example.minimalapk.plugins

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.minimalapk.examples.SoundClassifier
import com.example.minimalapk.examples.SoundLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AudioClassificationPluginTest {
    /** Downloader mirror lookup happens before filesDir; this test stops at that real file seam. */
    private object DefaultPreferences : SharedPreferences {
        override fun getAll(): Map<String, *> = emptyMap<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = error("Loading a model must not write mirror settings")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    @Test
    fun permissionGrantLoadsWithNewGenerationAndRegrantCanRestart() {
        val opens = LinkedBlockingQueue<Boolean>()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = DefaultPreferences
            override fun getFilesDir(): File {
                opens.offer(true)
                error("Test stops before downloads or native model loading")
            }
        }
        PluginHost(listOf(AudioClassificationPlugin(context))).use { host ->
            host.configure(mapOf(PluginIds.AUDIO_CLASSIFICATION to PluginSettings(true)))
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            assertEquals("等待麦克风权限", host.snapshot.value.reports.single().output.messages.single().text)
            val beforeGrant = host.contextFor(PluginIds.AUDIO_CLASSIFICATION)!!
            assertNull("No permission must mean no model loading", opens.poll())

            val session = host.sessionId()!!
            host.permissionChanged(session, AudioPermission.GRANTED)
            assertFalse(beforeGrant.isCurrent())
            assertNotNull("Grant must reload using the permission generation", opens.poll(5, TimeUnit.SECONDS))
            val granted = host.contextFor(PluginIds.AUDIO_CLASSIFICATION)!!
            assertTrue(granted.isCurrent())

            host.permissionChanged(session, AudioPermission.REVOKED)
            assertFalse(granted.isCurrent())
            assertEquals("请授予麦克风权限以识别周围声音", host.snapshot.value.reports.single().output.messages.single().text)
            assertNull("Revocation must not start another load", opens.poll())
            host.permissionChanged(session, AudioPermission.GRANTED)
            assertNotNull("A revoked model must be restartable", opens.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun wavModeLoadsWithoutMicrophoneDemandAndChangingBackRequestsOnlyAudio() {
        val opens = LinkedBlockingQueue<Boolean>()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File {
                opens.offer(true)
                error("Test stops before downloads or native model loading")
            }
        }
        PluginHost(listOf(AudioClassificationPlugin(context))).use { host ->
            val file = PluginSettings(true, mapOf(AUDIO_INPUT_KEY to AUDIO_INPUT_WAV, AUDIO_WAV_URI_KEY to "content://audio/test.wav"))
            host.configure(mapOf(PluginIds.AUDIO_CLASSIFICATION to file))
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            assertNotNull(opens.poll(5, TimeUnit.SECONDS))
            assertFalse(host.requiresAudioPermission())
            assertFalse(host.snapshot.value.audioCaptureRequested)
            assertFalse(host.action(PluginIds.AUDIO_CLASSIFICATION, "run_file")) // Not model-ready.
            host.configure(mapOf(PluginIds.AUDIO_CLASSIFICATION to PluginSettings(true)))
            assertTrue(host.requiresAudioPermission())
            assertEquals("等待麦克风权限", host.snapshot.value.reports.single().output.messages.single().text)
            host.permissionChanged(host.sessionId()!!, AudioPermission.DENIED)
            assertEquals("请授予麦克风权限以识别周围声音", host.snapshot.value.reports.single().output.messages.single().text)
            host.configure(mapOf(PluginIds.AUDIO_CLASSIFICATION to file.copy(revision = 2)))
            assertFalse(host.requiresAudioPermission())
            assertFalse(host.snapshot.value.audioCaptureRequested)
        }
    }

    @Test
    fun playbackPauseDropsInFlightResultAndPartialWindowThenRecoversOnFreshSamples() {
        val inference = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val opens = AtomicInteger()
        val windows = LinkedBlockingQueue<FloatArray>()
        val plugin = AudioClassificationPlugin(ContextWrapper(null)) { _, _ ->
            val first = opens.incrementAndGet() == 1
            object : SoundClassifier {
                override val windowSamples = 4
                override val sampleRate = 16000
                override val labels = listOf("Speech")
                override fun classify(samples: FloatArray): List<SoundLabel> {
                    windows.offer(samples)
                    if (first) {
                        inference.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return listOf(SoundLabel(if (first) "pre-playback" else "fresh", .9f))
                }
                override fun close() { if (first) closed.countDown() }
            }
        }
        PluginHost(listOf(plugin)).use { host ->
            try {
                host.configure(mapOf(PluginIds.AUDIO_CLASSIFICATION to PluginSettings(true)))
                host.lifecycle(CameraLifecycle.ENTER)
                host.lifecycle(CameraLifecycle.FOREGROUND)
                val session = host.sessionId()!!
                host.permissionChanged(session, AudioPermission.GRANTED)
                await { messages(host).any { it.contains("已就绪") } }
                host.audio(session, pcm(shortArrayOf(100, 100, 100, 100)))
                assertTrue(inference.await(5, TimeUnit.SECONDS))
                host.audio(session, pcm(shortArrayOf(200, 200))) // Partial next window before TTS.
                val before = host.contextFor(PluginIds.AUDIO_CLASSIFICATION)!!
                host.playbackChanged(true)
                assertTrue(before.isCurrent()) // Playback intentionally leaves the host token unchanged.
                assertEquals(listOf("播放中，共享麦克风已暂停"), messages(host))
                release.countDown()
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertFalse(messages(host).any { it.contains("pre-playback") })
                host.playbackChanged(false)
                await { opens.get() == 2 && messages(host).any { it.contains("已就绪") } }
                host.audio(session, pcm(shortArrayOf(300, 300, 300, 300)))
                await { messages(host).any { it.contains("fresh") } }
                windows.poll()
                val samples = windows.poll(5, TimeUnit.SECONDS)!!
                assertTrue(samples.all { it == 300f / 32768 })
                host.permissionChanged(session, AudioPermission.MICROPHONE_BUSY)
                assertFalse(messages(host).any { it.contains("fresh") })
            } finally { release.countDown() }
        }
    }

    private fun pcm(samples: ShortArray): PcmBlock {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putShort(it) }
        bytes.flip()
        return PcmBlock(bytes.asReadOnlyBuffer(), 16000, 1, PcmFormat.PCM_16_LE, System.nanoTime())
    }

    private fun messages(host: PluginHost): List<String> = host.snapshot.value.reports.flatMap { it.output.messages }.map { it.text }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Timed out waiting for actual plugin state", condition())
    }
}
