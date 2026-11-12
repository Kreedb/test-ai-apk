package com.example.minimalapk.plugins

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minimalapk.pose.DeviceUsage
import com.example.minimalapk.pose.DeviceUsageSource
import com.example.minimalapk.pose.NpuProbeReader
import com.example.minimalapk.pose.NpuSource
import com.example.minimalapk.pose.PhotoBounds
import com.example.minimalapk.pose.ProbeFileSystem
import com.example.minimalapk.pose.ProbeRead
import com.example.minimalapk.pose.UsageMetrics
import org.junit.Assert.*
import org.junit.Test

/** Real Android clipboard service + production action/host/exporter, deterministic driver text.
 * This proves the copy path, not availability of a vendor sysfs node on this device. */
class SystemMonitorClipboardTest {
    @Test
    fun explicitActionCopiesEveryBytePastOverlayLimitsAndStopsWithSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        val original = "  driver raw\r\n" + "payload not a percent\n".repeat(130) + "END-OF-SOURCE\n"
        val path = "/sys/class/misc/npu/device/load"
        val reader = NpuProbeReader(object : ProbeFileSystem {
            override fun read(path: String): ProbeRead = if (path == "/sys/class/misc/npu/device/load")
                ProbeRead.Content(original) else ProbeRead.Missing
            override fun listDirectories(parent: String): List<String> = emptyList()
        }, { 1L })
        val usage = object : DeviceUsageSource {
            override fun sample(source: NpuSource, nowNanos: Long, metrics: UsageMetrics, stillNeeded: () -> Boolean) = DeviceUsage(
                npuResults = reader.probe(source, nowNanos), sampledAtNanos = nowNanos,
            )
            override fun reset() = Unit
        }
        try {
            instrumentation.runOnMainSync {
                PluginHost(listOf(SystemMonitorPlugin(context, usage))).use { host ->
                    host.configure(mapOf(PluginIds.SYSTEM_MONITOR to PluginSettings(true,
                        SystemMonitorSettings(npuSource = NpuSource.ALL).toConfig())))
                    host.lifecycle(CameraLifecycle.ENTER)
                    host.lifecycle(CameraLifecycle.FOREGROUND)
                    val space = ImageSpace(16, 16, PhotoBounds(0f, 0f, 16f, 16f))
                    val image = object : PluginImage {
                        override val width = 16
                        override val height = 16
                        override val bitmap: Bitmap get() = error("Clipboard test does not render pixels")
                    }
                    host.acceptFrame(CameraFrame(ImageSource(host.sessionId()!!, 1, space, 0), System.nanoTime(),
                        image, image, ViewportMapping(space, 16, 16), emptyList()))
                    assertTrue(host.action(PluginIds.SYSTEM_MONITOR, SystemMonitorPlugin.COPY_DIAGNOSIS))
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = checkNotNull(clipboard.primaryClip).getItemAt(0).coerceToText(context).toString()
                    assertTrue(text.length > PluginHost.MAX_MESSAGES * PluginHost.MAX_MESSAGE_CHARS)
                    assertTrue(text.contains(path))
                    val raw = text.substringAfter("raw_begin (${original.toByteArray(Charsets.UTF_8).size} UTF-8 bytes)\n")
                        .substringBefore("\nraw_end")
                    assertArrayEquals(original.toByteArray(Charsets.UTF_8), raw.toByteArray(Charsets.UTF_8))
                    assertTrue(text.contains("counter_delta") && text.contains("missing"))
                    host.lifecycle(CameraLifecycle.BACKGROUND)
                    assertTrue(host.snapshot.value.monitors.isEmpty())
                    assertFalse(host.action(PluginIds.SYSTEM_MONITOR, SystemMonitorPlugin.COPY_DIAGNOSIS))
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
