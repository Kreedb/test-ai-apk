package com.example.minimalapk.plugins

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
}
