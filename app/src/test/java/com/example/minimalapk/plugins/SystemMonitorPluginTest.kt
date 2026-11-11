package com.example.minimalapk.plugins

import android.graphics.Bitmap
import com.example.minimalapk.pose.DeviceUsage
import com.example.minimalapk.pose.DeviceUsageSource
import com.example.minimalapk.pose.ModelResult
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.NpuProbeResult
import com.example.minimalapk.pose.NpuProbeScheme
import com.example.minimalapk.pose.NpuProbeState
import com.example.minimalapk.pose.NpuSource
import com.example.minimalapk.pose.PhotoBounds
import com.example.minimalapk.pose.PoseDetection
import com.example.minimalapk.pose.PoseKeypoint
import com.example.minimalapk.pose.PoseTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemMonitorPluginTest {
    private class Image : PluginImage {
        override val width = 640
        override val height = 640
        override val bitmap: Bitmap get() = error("Pure host tests do not access Android pixels")
    }

    private class FakeUsage : DeviceUsageSource {
        var samples = 0
        var resets = 0
        override fun sample(source: NpuSource, nowNanos: Long): DeviceUsage {
            samples++
            return DeviceUsage(
                cpuPercent = 12f,
                gpuPercent = 34f,
                gpuSource = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                npuResults = listOf(NpuProbeResult(
                    NpuProbeScheme.VENDOR_PERCENT, "/sys/class/misc/npu/device/load", 5f, "%",
                    "NPU 负载", nowNanos, NpuProbeState.OK,
                )),
            )
        }

        override fun reset() {
            resets++
        }
    }

    private val space = ImageSpace(640, 640, PhotoBounds(80f, 0f, 560f, 640f))

    private fun frame(
        session: Long = 1,
        number: Long = 1,
        timestamp: Long = 0,
        poses: List<ModelResult> = emptyList(),
        provider: String? = null,
    ) = CameraFrame(
        ImageSource(session, number, space, 90), timestamp, Image(), Image(),
        ViewportMapping(space, 640, 640), poses, executionProvider = provider,
    )

    @Test
    fun cameraFpsInferenceAndAllModelAnalysisStaySeparate() {
        val usage = FakeUsage()
        val engine = SystemMonitorEngine(usage) { 0L }
        engine.start(SystemMonitorSettings())
        val poses = listOf(
            ModelResult(ModelStore.builtIn.first(), null, PoseTimings(2f, 50f, 3f)),
            ModelResult(ModelStore.builtIn.last(), null, PoseTimings(1f, 3f, 1f)),
        )
        val output = engine.onFrame(frame(poses = poses, provider = "LiteRT (GPU)"))
        assertNotNull(output)
        assertEquals(1, usage.samples)
        assertTrue(checkNotNull(output).metricLines.contains("帧率 —"))
        assertTrue(output.metricLines.contains("推理 50.0 ms"))
        // 55 ms + 5 ms, not 1000 / inferenceMs.
        assertTrue(output.metricLines.contains("分析耗时(全部) 60.0 ms"))
        assertTrue(output.metricLines.contains("加速器 LiteRT (GPU)"))
        assertFalse(output.metricLines.any { it.contains("20.0 FPS") })
    }

    @Test
    fun refreshGateUsesFrameStreamAndStopDisablesSampling() {
        var now = 0L
        val usage = FakeUsage()
        val engine = SystemMonitorEngine(usage) { now }
        engine.start(SystemMonitorSettings(refreshMs = 1000, fpsWindowSeconds = 2))

        now = 0
        assertNotNull(engine.onFrame(frame(number = 1, timestamp = 0)))
        assertEquals(1, usage.samples)
        now = 500_000_000
        assertNull(engine.onFrame(frame(number = 2, timestamp = 500_000_000)))
        assertEquals(1, usage.samples)
        now = 1_000_000_000
        val third = engine.onFrame(frame(number = 3, timestamp = 1_000_000_000))
        assertNotNull(third)
        assertEquals(2, usage.samples)
        assertEquals("帧率 2.0 FPS", checkNotNull(third).metricLines.first())

        val resets = usage.resets
        engine.stop()
        assertEquals(resets + 1, usage.resets)
        assertNull(engine.onFrame(frame(number = 4, timestamp = 1_100_000_000)))
    }

    @Test
    fun fpsUsesRecordedFrameTimestampsNotPostInferenceClock() {
        var now = 0L
        val engine = SystemMonitorEngine(FakeUsage()) { now }
        engine.start(SystemMonitorSettings(refreshMs = 250, fpsWindowSeconds = 2))

        // Capture is 500 ms apart, but the clock is read 200 ms after each capture (inference time).
        now = 200_000_000
        engine.onFrame(frame(number = 1, timestamp = 0))
        now = 700_000_000
        engine.onFrame(frame(number = 2, timestamp = 500_000_000))
        now = 1_200_000_000
        val third = engine.onFrame(frame(number = 3, timestamp = 1_000_000_000))

        // 2 intervals over the 1 s span between captured timestamps; the clock would report 1.7.
        assertEquals("帧率 2.0 FPS", checkNotNull(third).metricLines.first())
    }

    @Test
    fun defaultRefreshPublishesAtFourHertzWithoutSamplingEveryCameraFrame() {
        var now = 0L
        val usage = FakeUsage()
        val engine = SystemMonitorEngine(usage) { now }
        engine.start(SystemMonitorSettings())
        assertNotNull(engine.onFrame(frame(timestamp = now)))
        for (millis in 50..200 step 50) {
            now = millis * 1_000_000L
            assertNull(engine.onFrame(frame(timestamp = now)))
        }
        assertEquals(1, usage.samples)
        now = 250_000_000L
        val refreshed = checkNotNull(engine.onFrame(frame(timestamp = now)))
        assertEquals("帧率 20.0 FPS", refreshed.metricLines.first())
        assertEquals(2, usage.samples)
    }

    @Test
    fun monitorMetricsStayInPanelAlongsidePoseEnhancementBetweenSamples() {
        var now = 0L
        val usage = FakeUsage()
        val monitor = SystemMonitorPlugin(usage, { now }) {
            MonitorDeviceInfo("Test Phone", "Test SoC", "Android 15", "1.0")
        }
        val pose = ModelResult(
            ModelStore.builtIn.first(),
            PoseDetection(100f, 20f, 500f, 620f, .9f, List(17) { PoseKeypoint(320f, 320f, .9f) }),
            PoseTimings(2f, 10f, 1f),
        )
        PluginHost(listOf(monitor, PoseEnhancementPlugin())).use { host ->
            host.configure(mapOf(
                PluginIds.SYSTEM_MONITOR to PluginSettings(true, SystemMonitorSettings().toConfig()),
                PluginIds.POSE_ENHANCEMENT to PluginSettings(true, PoseEnhancementSettings().toConfig()),
            ))
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            for (number in 1L..6L) {
                now = (number - 1) * 50_000_000L
                host.acceptFrame(frame(session = host.sessionId()!!, number = number, timestamp = now, poses = listOf(pose)))
                val snapshot = host.snapshot.value
                assertEquals(PluginIds.SYSTEM_MONITOR, snapshot.monitors.single().pluginId)
                assertTrue(snapshot.monitors.single().output.messages.any { it.text.startsWith("NPU ") })
                // No dummy image layer and no metric text for the bottom report area to append.
                assertTrue(snapshot.monitors.single().output.layers.isEmpty())
                assertEquals(PluginIds.POSE_ENHANCEMENT, snapshot.layers.single().pluginId)
                val heightLine = snapshot.layers.single().output.layers.single().lines.single()
                assertEquals(ImagePoint(300f, 20f), heightLine.start)
                assertEquals(ImagePoint(300f, 620f), heightLine.end)
                assertEquals(listOf(pose), snapshot.frame!!.poses)
                assertEquals(listOf(PluginIds.POSE_ENHANCEMENT), snapshot.reports.map { it.pluginId })
            }
            assertEquals(2, usage.samples)
            assertEquals("帧率 20.0 FPS", host.snapshot.value.monitors.single().output.messages.first().text)
        }
    }

    @Test
    fun reconfigureAppliesRefreshAndVisibilityLiveAndDisabledShowsNothing() {
        var now = 0L
        val usage = FakeUsage()
        val engine = SystemMonitorEngine(usage) { now }
        engine.start(SystemMonitorSettings(refreshMs = 5000))
        now = 0
        assertNotNull(engine.onFrame(frame(number = 1, timestamp = 0)))
        now = 100_000_000
        assertNull(engine.onFrame(frame(number = 2, timestamp = 100_000_000)))
        engine.reconfigure(SystemMonitorSettings(refreshMs = 250))
        now = 200_000_000
        assertNotNull(engine.onFrame(frame(number = 3, timestamp = 200_000_000)))
        assertEquals(2, usage.samples)

        engine.reconfigure(SystemMonitorSettings(
            showFps = false, showTimings = false, showBackend = false,
            showCpu = false, showGpu = false, showNpu = false,
        ))
        now = 300_000_000
        val hidden = engine.onFrame(frame(number = 4, timestamp = 300_000_000))
        assertNotNull(hidden)
        assertTrue(checkNotNull(hidden).metricLines.isEmpty())
    }

    @Test
    fun hostDisableAndBackgroundClearMonitorOutput() {
        var now = 0L
        val usage = FakeUsage()
        val plugin = SystemMonitorPlugin(usage, { now }) {
            MonitorDeviceInfo("Test Phone", "Test SoC", "Android 15 (API 35)", "1.0")
        }
        PluginHost(listOf(plugin)).use { host ->
            host.configure(mapOf(
                PluginIds.SYSTEM_MONITOR to PluginSettings(true, SystemMonitorSettings().toConfig()),
            ))
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            now = 0
            host.acceptFrame(frame(session = host.sessionId()!!))
            assertTrue(host.snapshot.value.monitors.isNotEmpty())

            val resets = usage.resets
            host.configure(mapOf(PluginIds.SYSTEM_MONITOR to PluginSettings(false)))
            assertTrue(host.snapshot.value.monitors.isEmpty())
            assertTrue(usage.resets > resets)

            host.configure(mapOf(
                PluginIds.SYSTEM_MONITOR to PluginSettings(true, SystemMonitorSettings().toConfig()),
            ))
            now = 100_000_000
            host.acceptFrame(frame(session = host.sessionId()!!, number = 2, timestamp = now))
            assertTrue(host.snapshot.value.monitors.isNotEmpty())
            host.lifecycle(CameraLifecycle.BACKGROUND)
            assertTrue(host.snapshot.value.monitors.isEmpty())
        }
    }

    @Test
    fun testAllDiagnosisListsEverySchemeAndFitsHostLimits() {
        val results = listOf(
            probe(NpuProbeScheme.VENDOR_PERCENT, NpuProbeState.OK, 12f),
            probe(NpuProbeScheme.DEVFREQ, NpuProbeState.MISSING),
            probe(NpuProbeScheme.ROCKCHIP, NpuProbeState.OK, 7.5f, perCore = listOf(12f, 3f)),
            probe(NpuProbeScheme.SAMSUNG_RQ_LOAD, NpuProbeState.OK, 25f, scope = "NPU 请求队列忙碌率"),
            probe(NpuProbeScheme.QUALCOMM_DIAGNOSTIC, NpuProbeState.OK, null, unit = "ticks",
                scope = "DSP 睡眠统计（非利用率）"),
            probe(NpuProbeScheme.QUALCOMM_DIAGNOSTIC, NpuProbeState.PERMISSION_DENIED, null,
                path = "/sys/kernel/debug/qcom_stats/cdsp1", unit = "ticks"),
            probe(NpuProbeScheme.COUNTER_DELTA, NpuProbeState.UNSUPPORTED, null,
                detail = "无可证明更新且导出的 busy/total 计数"),
        )
        val lines = monitorDiagnosis(
            MonitorDeviceInfo("Test Phone", "Test SoC", "Android 15 (API 35)", "1.0"),
            results, 123,
        )
        assertEquals(7, lines.size)
        assertTrue(lines.first().contains("Test Phone"))
        assertTrue(lines.first().contains("Test SoC"))
        assertTrue(lines.any { it.startsWith("vendor_percent:") })
        assertTrue(lines.any { it.startsWith("rockchip:") })
        assertTrue(lines.any { it.startsWith("samsung_rq_load:") })
        assertTrue(lines.any { it.startsWith("qualcomm_diag:") })
        assertTrue(lines.any { it.startsWith("counter_delta:") })
        assertTrue(lines.all { it.length <= PluginHost.MAX_MESSAGE_CHARS })
    }

    @Test
    fun settingsValidateRangesPersistRoundTripAndSamsungLabelIsHonest() {
        val clamped = SystemMonitorSettings.from(PluginSettings(true, mapOf(
            "refresh_ms" to "100", "fps_window_s" to "99",
        )))
        assertEquals(SystemMonitorSettings.MIN_REFRESH_MS, clamped.refreshMs)
        assertEquals(SystemMonitorSettings.MAX_FPS_WINDOW_SECONDS, clamped.fpsWindowSeconds)
        val custom = SystemMonitorSettings(
            showFps = false, showTimings = false, showBackend = false, showCpu = false,
            showGpu = false, showNpu = false, refreshMs = 250, fpsWindowSeconds = 10,
            npuSource = NpuSource.ALL,
        )
        assertEquals(custom, SystemMonitorSettings.from(PluginSettings(config = custom.toConfig())))
        assertEquals(SystemMonitorSettings(), SystemMonitorSettings.from(PluginSettings()))

        val samsung = DeviceUsage(npuResults = listOf(probe(
            NpuProbeScheme.SAMSUNG_RQ_LOAD, NpuProbeState.OK, 25f, scope = "NPU 请求队列忙碌率",
        )))
        assertTrue(npuMetricLine(samsung).startsWith("NPU 请求队列忙碌率 25.0%"))
        assertFalse(npuMetricLine(samsung).startsWith("NPU 25.0%"))
    }

    private fun probe(
        scheme: NpuProbeScheme,
        state: NpuProbeState,
        value: Float? = null,
        path: String = "/sys/test/${scheme.id}",
        unit: String = "%",
        scope: String = scheme.label,
        perCore: List<Float> = emptyList(),
        detail: String? = null,
    ) = NpuProbeResult(
        scheme = scheme, sourcePath = path, value = value, unit = unit, scope = scope,
        timestampNanos = 1, state = state, perCore = perCore,
        rawExcerpt = if (state == NpuProbeState.OK) "raw sample" else null,
        detail = detail,
    )
}
