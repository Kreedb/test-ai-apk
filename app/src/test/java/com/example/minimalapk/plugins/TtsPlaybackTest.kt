package com.example.minimalapk.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackTest {
    @Test
    fun completionClearsQueuedAndSpeakingTextAndLeavesEngineReady() {
        val playback = ready()
        val id = playback.enqueue("你好")!!
        assertEquals("你好", playback.text)
        assertTrue(playback.started(id))
        assertTrue(playback.finished(id, "朗读完成"))
        assertNull(playback.text)
        assertEquals("朗读完成", playback.message)
        assertTrue(playback.ready)
        assertFalse(playback.started(id))
    }

    @Test
    fun queueFlushIgnoresEveryLateCallbackFromThePreviousUtterance() {
        val playback = ready()
        val old = playback.enqueue("旧文本")!!
        val current = playback.enqueue("新文本")!!
        assertNotEquals(old, current)
        assertFalse(playback.started(old))
        assertFalse(playback.finished(old, "朗读完成"))
        assertFalse(playback.finished(old, "已停止朗读"))
        assertFalse(playback.finished(old, "错误"))
        assertEquals("新文本", playback.text)
        assertTrue(playback.started(current))
        assertTrue(playback.finished(current, "朗读完成"))
        assertNull(playback.text)
    }

    @Test
    fun stopClearsImmediatelyAndDelayedEngineStopCannotClearNextUtterance() {
        val playback = ready()
        val stopped = playback.enqueue("文本")!!
        assertTrue(playback.stop())
        assertNull(playback.text)
        assertFalse(playback.started(stopped))
        playback.enqueue("再次朗读")
        assertFalse(playback.finished(stopped, "已停止朗读"))
        assertEquals("再次朗读", playback.text)
    }

    @Test
    fun bothImmediateRejectionAndAsyncErrorClearPlaybackAndAllowRetry() {
        val playback = ready()
        repeat(2) {
            val id = playback.enqueue("失败文本")!!
            if (it == 1) playback.started(id)
            assertTrue(playback.finished(id, "朗读失败"))
            assertNull(playback.text)
            assertEquals("朗读失败", playback.message)
            assertTrue(playback.ready)
        }
    }

    @Test
    fun engineSwitchOrLifecycleCloseRejectsInitializationAndSpeechCallbacks() {
        val playback = ready()
        val id = playback.enqueue("后台前的文本")!!
        playback.close()
        assertFalse(playback.ready)
        assertNull(playback.text)
        assertFalse(playback.initialized(true, "迟到的初始化"))
        assertFalse(playback.started(id))
        assertFalse(playback.finished(id, "迟到的完成"))
        assertFalse(playback.stop())
        assertNull(playback.enqueue("关闭后"))
    }

    @Test
    fun failedInitializationNeverQueuesSpeech() {
        val playback = TtsPlayback()
        assertNull(playback.enqueue("尚未初始化"))
        playback.initialized(false, "缺少中文语音包")
        assertFalse(playback.ready)
        assertNull(playback.enqueue("不能朗读"))
        assertEquals("缺少中文语音包", playback.message)
    }

    private fun ready() = TtsPlayback().apply { initialized(true, "就绪") }
}
