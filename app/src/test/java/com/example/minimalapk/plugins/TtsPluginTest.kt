package com.example.minimalapk.plugins

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test

class TtsPluginTest {
    private class Engine(
        val playback: (String?) -> Unit,
        val status: (String, Boolean) -> Unit,
    ) : TtsSession {
        override var ready = false
        var closed = false
        val spoken = mutableListOf<String>()
        fun initialize() { ready = true; status("中文语音服务就绪", true) }
        override fun speak(text: String): Boolean {
            spoken += text
            playback(text)
            status("正在朗读…", true)
            return true
        }
        fun finish() { playback(null); status("朗读完成", true) }
        override fun stop() { playback(null); status("已停止朗读", ready) }
        override fun close() { closed = true; ready = false }
    }

    @Test
    fun cameraSpeechRequiresExplicitActionAndCompletionWithdrawsCurrentText() {
        val engines = mutableListOf<Engine>()
        val plugin = TtsPlugin(ContextWrapper(null), onMain = { it() }, createEngine = { _, _, playback, status ->
            Engine(playback, status).also { engines += it }
        })
        PluginHost(listOf(plugin)).use { host ->
            host.configure(mapOf(PluginIds.TTS to PluginSettings(true, mapOf(TTS_TEXT_KEY to "已保存文本"))))
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            assertFalse(host.requiresAudioPermission())
            assertFalse(host.action(PluginIds.TTS, "speak"))
            val engine = engines.single()
            engine.initialize()
            assertTrue(engine.spoken.isEmpty())
            assertTrue(host.action(PluginIds.TTS, "speak"))
            assertEquals(listOf("已保存文本"), engine.spoken)
            assertTrue(messages(host).any { it.contains("当前朗读") })
            assertTrue(host.snapshot.value.reports.single().output.actions.single { it.id == "stop" }.enabled)
            engine.finish()
            assertTrue(messages(host).any { it.contains("朗读完成") })
            assertFalse(messages(host).any { it.contains("当前朗读") || it.contains("正在朗读") })
            assertFalse(host.action(PluginIds.TTS, "stop"))
            assertTrue(host.action(PluginIds.TTS, "speak"))
            assertTrue(host.action(PluginIds.TTS, "stop"))
            assertFalse(messages(host).any { it.contains("当前朗读") })
            host.configure(mapOf(PluginIds.TTS to PluginSettings(false)))
            assertTrue(engine.closed)
            engine.playback("迟到文本")
            engine.status("迟到状态", true)
            assertTrue(host.snapshot.value.reports.isEmpty())
        }
    }

    @Test
    fun engineSwitchAndBackgroundCloseBindingsAndIgnoreOldCallbacks() {
        val engines = mutableListOf<Pair<String, Engine>>()
        val plugin = TtsPlugin(ContextWrapper(null), onMain = { it() }, createEngine = { _, name, playback, status ->
            Engine(playback, status).also { engines += name to it }
        })
        PluginHost(listOf(plugin)).use { host ->
            val initial = PluginSettings(true, mapOf(TTS_TEXT_KEY to "你好", TTS_ENGINE_KEY to "engine.a"))
            host.configure(mapOf(PluginIds.TTS to initial))
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            val first = engines.single().second.also { it.initialize() }
            host.action(PluginIds.TTS, "speak")
            host.configure(mapOf(PluginIds.TTS to initial.copy(config = initial.config + (TTS_ENGINE_KEY to "engine.b"))))
            assertTrue(first.closed)
            assertEquals("engine.b", engines.last().first)
            first.playback("旧引擎迟到文本")
            first.status("旧引擎迟到状态", true)
            assertFalse(messages(host).any { it.contains("迟到") || it.contains("当前朗读") })
            val second = engines.last().second.also { it.initialize() }
            assertTrue(second.spoken.isEmpty())
            host.lifecycle(CameraLifecycle.BACKGROUND)
            assertTrue(second.closed)
            assertTrue(host.snapshot.value.reports.isEmpty())
            host.lifecycle(CameraLifecycle.FOREGROUND)
            assertEquals(3, engines.size)
            second.playback("后台前的回调")
            assertFalse(messages(host).any { it.contains("后台前") })
        }
    }

    private fun messages(host: PluginHost) = host.snapshot.value.reports.flatMap { it.output.messages }.map { it.text }
}
