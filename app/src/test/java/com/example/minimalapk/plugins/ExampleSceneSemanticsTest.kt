package com.example.minimalapk.plugins

import com.example.minimalapk.examples.ImageFit
import com.example.minimalapk.models.LiveTensorOutput
import com.example.minimalapk.models.encodeTensor
import com.example.minimalapk.models.imageValues
import com.example.minimalapk.pose.GraphSignature
import com.example.minimalapk.pose.GraphTensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleSceneSemanticsTest {
    @Test
    fun pinnedSsdAcceptsDeferredOutputShapesAndQuantizesDocumentedNormalizedRgb() {
        val input = GraphTensor("normalized_input_image_tensor", listOf(1, 300, 300, 3), listOf(1, 300, 300, 3),
            3, 1f / 128, 128)
        val graph = GraphSignature("", 0, listOf(input), List(4) { GraphTensor("output$it", emptyList(), emptyList(), 0) })
        val config = sceneModelConfig(PluginIds.OBJECT_DETECTION_SCENE, graph)
        val pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt())
        val bytes = encodeTensor(imageValues(pixels, 3, config.inputs.single()), input, config.inputs.single().source)
        assertEquals(0, bytes.get().toInt() and 255)
        bytes.position(3)
        assertEquals(255, bytes.get().toInt() and 255)
        assertThrows(IllegalArgumentException::class.java) {
            sceneModelConfig(PluginIds.OBJECT_DETECTION_SCENE, graph.copy(outputs = graph.outputs.drop(1)))
        }
    }

    @Test
    fun nonSquareInputsPreserveAspectAndPaddingNeverBecomesPhoto() {
        val fit = ImageFit(1280, 720, 257, 257)
        assertEquals(640f, fit.sourceX(.5f), .001f)
        assertEquals(360f, fit.sourceY(.5f), .001f)
        assertEquals(0f, fit.sourceY(fit.top / fit.height), .001f)
        assertEquals(720f, fit.sourceY((fit.top + fit.photoHeight) / fit.height), .001f)
        assertFalse(fit.contains(.5f, .05f))
        assertTrue(fit.contains(.01f, .5f))
        val portrait = ImageFit(720, 1280, 192, 192)
        assertEquals(360f, portrait.sourceX(.5f), .001f)
        assertFalse(portrait.contains(.05f, .5f))
    }

    @Test
    fun lowConfidenceOrPaddedWristsCannotTriggerAnAction() {
        val fit = ImageFit(400, 200, 192, 192)
        val values = DoubleArray(51) { .5 }
        values[9 * 3] = .05 // A confident wrist in letterbox padding.
        values[9 * 3 + 2] = .99
        values[10 * 3 + 2] = .1
        val points = moveNetPoints(values, fit, .3f)
        assertNull(points[9])
        assertNull(points[10])
        assertNull(armPosition(points, 400, 200))
    }

    @Test
    fun wristGeometryRequiresBothArmsAndAVisibleUprightTorso() {
        fun points(left: Float, right: Float) = MutableList<ScenePoint?>(17) { null }.apply {
            this[5] = ScenePoint(30f, 30f); this[6] = ScenePoint(70f, 30f)
            this[11] = ScenePoint(35f, 70f); this[12] = ScenePoint(65f, 70f)
            this[9] = ScenePoint(20f, left); this[10] = ScenePoint(80f, right)
        }
        assertEquals(ArmPosition.LOWERED, armPosition(points(65f, 65f), 100, 100))
        assertEquals(ArmPosition.RAISED, armPosition(points(15f, 15f), 100, 100))
        assertEquals(ArmPosition.MOVING, armPosition(points(15f, 65f), 100, 100))
        assertNull(armPosition(points(15f, 15f).apply { this[11] = null }, 100, 100))
    }

    @Test
    fun repetitionsNeedStableDownUpDownAndNeverCountNoiseOrStartingWithHandsUp() {
        val tracker = RaiseCycleTracker()
        var time = 0L
        fun hold(position: ArmPosition?): RaiseProgress {
            var result = tracker.update(position, time)
            repeat(3) { time += 200_000_000; result = tracker.update(position, time) }
            time += 200_000_000
            return result
        }
        assertEquals(0, hold(ArmPosition.RAISED).repetitions)
        assertEquals(0, hold(ArmPosition.LOWERED).repetitions)
        tracker.update(ArmPosition.RAISED, time)
        time += 100_000_000
        tracker.update(ArmPosition.LOWERED, time)
        time += 100_000_000
        assertEquals(0, hold(ArmPosition.LOWERED).repetitions)
        assertEquals(0, hold(ArmPosition.RAISED).repetitions)
        assertEquals(1, hold(ArmPosition.LOWERED).repetitions)
        assertEquals(1, hold(ArmPosition.LOWERED).repetitions)
        hold(ArmPosition.RAISED)
        hold(null) // Tracking loss invalidates the unfinished repetition.
        assertEquals(1, hold(ArmPosition.LOWERED).repetitions)
        hold(ArmPosition.RAISED)
        time += 2_000_000_000
        assertEquals(1, hold(ArmPosition.LOWERED).repetitions)
    }

    @Test
    fun detectionsUseRealLabelsAndPhotoRelativeLocationAndRejectPadding() {
        val tensors = listOf(
            LiveTensorOutput(listOf(1, 10, 4), doubleArrayOf(.25, .0, .5, .2, .0, .2, .1, .4, .5, .7, .75, .9).copyOf(40)),
            LiveTensorOutput(listOf(1, 10), doubleArrayOf(0.0, 1.0, 1.0).copyOf(10)),
            LiveTensorOutput(listOf(1, 10), doubleArrayOf(.9, .8, .2).copyOf(10)),
            LiveTensorOutput(listOf(1), doubleArrayOf(3.0)),
        )
        val results = sceneDetections(tensors, ImageFit(400, 200, 300, 300), .3f, listOf("person", "bicycle"))
        assertEquals(1, results.size)
        assertEquals("person", results[0].label)
        assertEquals(0f, results[0].top, .0001f)
        assertEquals(.5f, results[0].bottom, .0001f)
        assertEquals("左上", results[0].let { objectLocation(it.left, it.top, it.right, it.bottom) })
        assertEquals("右下", objectLocation(.8f, .8f, 1f, 1f))
    }

    @Test
    fun duplicateBoxesDoNotInflateCountsButDifferentClassesAndSeparateObjectsRemain() {
        val tensors = listOf(
            LiveTensorOutput(listOf(1, 10, 4), doubleArrayOf(.1, .1, .4, .4, .11, .11, .4, .4,
                .1, .1, .4, .4, .7, .7, .9, .9).copyOf(40)),
            LiveTensorOutput(listOf(1, 10), doubleArrayOf(0.0, 0.0, 1.0, 0.0).copyOf(10)),
            LiveTensorOutput(listOf(1, 10), doubleArrayOf(.8, .9, .7, .6).copyOf(10)),
            LiveTensorOutput(listOf(1), doubleArrayOf(4.0)),
        )
        val objects = sceneDetections(tensors, ImageFit(300, 300, 300, 300), .3f, listOf("person", "tie"))
        assertEquals(3, objects.size)
        assertEquals(2, objects.count { it.label == "person" })
        assertEquals(.9, objects.first().score, 0.0)
    }

    @Test
    fun segmentationUsesLogitArgmaxNotAnArbitraryProbabilityThresholdAndExcludesPadding() {
        val values = DoubleArray(4 * 4 * 21) { -30.0 }
        // A 2:1 photo occupies only the middle two rows of the square model input.
        for (y in 0 until 4) for (x in 0 until 4) {
            val label = if (y == 0 || y == 3) 2 else if (x < 2) 15 else 0
            values[(y * 4 + x) * 21 + label] = -2.0 // Valid negative logits still yield a category.
        }
        val mask = sceneSegmentation(values, 4, 4, 21, ImageFit(400, 200, 4, 4))
        assertEquals(8, mask.photoPixels)
        assertEquals(4, mask.counts[15])
        assertEquals(4, mask.counts[0])
        assertEquals(0, mask.counts[2])
        assertEquals(-1, mask.classes[0])
        val shifted = sceneSegmentation(DoubleArray(values.size) { values[it] + 1000 }, 4, 4, 21, ImageFit(400, 200, 4, 4))
        assertTrue(mask.classes.contentEquals(shifted.classes))
        values[(1 * 4 + 0) * 21] = Double.NaN
        assertThrows(IllegalArgumentException::class.java) {
            sceneSegmentation(values, 4, 4, 21, ImageFit(400, 200, 4, 4))
        }
    }
}
