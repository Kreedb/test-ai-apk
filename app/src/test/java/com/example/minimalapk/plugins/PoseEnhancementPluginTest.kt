package com.example.minimalapk.plugins

import android.graphics.Bitmap
import com.example.minimalapk.pose.ModelResult
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.PhotoBounds
import com.example.minimalapk.pose.PoseDetection
import com.example.minimalapk.pose.PoseKeypoint
import com.example.minimalapk.pose.PoseNet
import com.example.minimalapk.pose.PoseTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseEnhancementPluginTest {
    private val bounds = PhotoBounds(80f, 0f, 560f, 640f)
    private fun result() = ModelResult(
        ModelStore.builtIn.first(),
        PoseDetection(100f, 20f, 500f, 620f, .9f, List(17) { index ->
            when (index) {
                PoseNet.LEFT_WRIST -> PoseKeypoint(100f, 300f, .9f)
                PoseNet.RIGHT_WRIST -> PoseKeypoint(500f, 300f, .9f)
                else -> PoseKeypoint(320f, 320f, .9f)
            }
        }), PoseTimings(0f, 0f, 0f),
    )

    @Test
    fun enhancementOwnsMeasurementsAndLimitationsWhileLineTogglesDoNotHideText() {
        val result = result()
        val noLines = poseEnhancement(result, bounds, PoseEnhancementSettings("180", false, false))
        val withLines = poseEnhancement(result, bounds, PoseEnhancementSettings("180", true, true))
        assertEquals(noLines.messages, withLines.messages)
        assertTrue(noLines.lines.isEmpty())
        assertEquals(2, withLines.lines.size)
        assertTrue(withLines.messages.any { "腕距约 120.0 cm" in it.text })
        assertTrue(withLines.messages.any { "不能用于医学诊断" in it.text })
        assertEquals(result(), result)
    }

    @Test
    fun invalidHeightAndCroppedBodyCannotGenerateMeasurementLines() {
        val invalid = poseEnhancement(result(), bounds, PoseEnhancementSettings("NaN", true, true))
        assertTrue(invalid.lines.isEmpty())
        assertTrue(invalid.messages.first().text.contains("有效身高"))
        val result = result()
        val cropped = result.copy(detection = result.detection!!.copy(top = bounds.top))
        val output = poseEnhancement(cropped, bounds, PoseEnhancementSettings("180", true, true))
        assertTrue(output.lines.isEmpty())
        assertTrue(output.messages.first().text.contains("全身入镜"))
    }

    @Test
    fun modelKeypointThresholdAndPhotoBoundsStillGateArms() {
        val base = result()
        val strict = base.copy(spec = base.spec.copy(keypointThreshold = .95f))
        assertTrue(poseEnhancement(strict, bounds, PoseEnhancementSettings()).lines.isEmpty())
        val outside = base.copy(detection = base.detection!!.copy(keypoints = base.detection.keypoints.mapIndexed { index, point ->
            if (index == PoseNet.LEFT_WRIST) point.copy(x = 10f) else point
        }))
        val output = poseEnhancement(outside, bounds, PoseEnhancementSettings("180", false, true))
        assertTrue(output.lines.isEmpty())
        assertFalse(output.messages.any { it.text.startsWith("腕距") })
    }

    @Test
    fun absentModelAndDetectionHavePluginOwnedStatus() {
        val noModel = poseEnhancement(null, bounds, PoseEnhancementSettings())
        assertTrue(noModel.messages.first().text.contains("等待"))
        val noBody = poseEnhancement(result().copy(detection = null), bounds, PoseEnhancementSettings())
        assertEquals("未检测到人体", noBody.messages.first().text)
    }

    @Test
    fun disablingRealEnhancementPluginWithdrawsItsOutputAndPreservesBasePoseFrame() {
        val settings = pluginDefaults.getValue(PluginIds.POSE_ENHANCEMENT)
        PluginHost(listOf(PoseEnhancementPlugin())).use { host ->
            host.configure(pluginDefaults)
            host.lifecycle(CameraLifecycle.ENTER)
            host.lifecycle(CameraLifecycle.FOREGROUND)
            val image = object : PluginImage {
                override val width = 640
                override val height = 640
                override val bitmap: Bitmap get() = error("No Android pixels in pure host tests")
            }
            val space = ImageSpace(640, 640, bounds)
            val frame = CameraFrame(ImageSource(host.sessionId()!!, 1, space, 90), 100,
                image, image, ViewportMapping(space, 640, 640), listOf(result()))
            host.acceptFrame(frame)
            assertTrue(host.snapshot.value.layers.isNotEmpty())
            assertTrue(host.snapshot.value.reports.any { report -> report.output.messages.any { "不能用于医学诊断" in it.text } })
            host.configure(mapOf(PluginIds.POSE_ENHANCEMENT to settings.copy(enabled = false)))
            assertTrue(host.snapshot.value.layers.isEmpty())
            assertTrue(host.snapshot.value.reports.isEmpty())
            assertEquals(frame.poses, host.snapshot.value.frame!!.poses)
            host.acceptFrame(frame.copy(source = frame.source.copy(frameNumber = 2), timestampNanos = 200))
            assertTrue(host.snapshot.value.reports.isEmpty())
        }
    }
}
