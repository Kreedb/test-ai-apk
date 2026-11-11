package com.example.minimalapk.plugins

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AudioClassificationPluginTest {
    @Test
    fun permissionGrantLoadsWithNewGenerationAndRegrantCanRestart() {
        val opens = LinkedBlockingQueue<Boolean>()
        val context = object : ContextWrapper(null) {
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

            val session = host.sessionId()!!
            host.permissionChanged(session, AudioPermission.GRANTED)
            assertFalse(beforeGrant.isCurrent())
            assertNotNull("Grant must reload using the permission generation", opens.poll(5, TimeUnit.SECONDS))

            host.permissionChanged(session, AudioPermission.REVOKED)
            assertEquals("请授予麦克风权限以识别周围声音", host.snapshot.value.reports.single().output.messages.single().text)
            host.permissionChanged(session, AudioPermission.GRANTED)
            assertNotNull("A revoked model must be restartable", opens.poll(5, TimeUnit.SECONDS))
        }
    }
}
