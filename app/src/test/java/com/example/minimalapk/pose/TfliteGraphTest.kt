package com.example.minimalapk.pose

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TfliteGraphTest {
    private fun asset(name: String): ByteArray = File("src/main/assets/models/$name").readBytes()

    // Downloadable models live at the repository root, outside the APK.
    private fun downloadable(name: String): ByteArray = File("../models/$name").readBytes()

    private fun contractOf(graph: TfliteGraph): PoseContract {
        val input = graph.inputs().single()
        val output = graph.outputs().single()
        return inferPoseContract(
            inputShape = graph.shape(input),
            outputShape = graph.shape(output),
            inputFloat32 = graph.elementType(input) == 0,
            outputFloat32 = graph.elementType(output) == 0,
        ).contract
    }

    @Test
    fun readsTheBundledPoseContract() {
        val graph = TfliteGraph(asset("yolo26n_pose.tflite"))

        assertEquals(PoseContract(640, 8400, 17), contractOf(graph))
        assertEquals(0, graph.dynamicSignatureCount())
    }

    @Test
    fun readsTheDownloadablePoseContract() {
        val graph = TfliteGraph(downloadable("yolo11n_pose.tflite"))

        assertEquals(PoseContract(640, 8400, 17), contractOf(graph))
        assertEquals(0, graph.dynamicSignatureCount())
    }

    @Test
    fun rewritesDynamicSignatureFromTheConcreteShape() {
        val graph = TfliteGraph(downloadable("yolo11n_pose.tflite"))
        val output = graph.outputs().single()
        graph.markSignatureDynamic(output)
        assertTrue(graph.dynamicSignatureCount() > 0)

        assertTrue(graph.staticize() > 0)

        assertEquals(0, graph.dynamicSignatureCount())
        assertArrayEquals(intArrayOf(1, 56, 8400), graph.shapeSignature(output))
    }

    @Test
    fun infersAnchorMajorOutputAndWarnsAboutIt() {
        val guess = inferPoseContract(
            inputShape = intArrayOf(1, 640, 640, 3),
            outputShape = intArrayOf(1, 8400, 56),
            inputFloat32 = true,
            outputFloat32 = true,
        )

        assertEquals(OutputLayout.ANCHOR_MAJOR, guess.contract.outputLayout)
        assertEquals(8400, guess.contract.anchors)
        assertEquals(17, guess.contract.keypoints)
        assertTrue(guess.warnings.any { it.contains("锚点优先") })
    }

    @Test
    fun acceptsAnotherKeypointCountAndWarnsThatCocoIndexingStopsAt17() {
        val guess = inferPoseContract(
            inputShape = intArrayOf(1, 640, 640, 3),
            outputShape = intArrayOf(1, 83, 8400),
            inputFloat32 = true,
            outputFloat32 = true,
        )

        assertEquals(26, guess.contract.keypoints)
        assertTrue(guess.warnings.any { it.contains("17") })
    }

    @Test
    fun rejectsLayoutsTheDecoderCannotRead() {
        // 4 box + 1 class + 80 classes is not 5+3K.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 640, 640, 3), intArrayOf(1, 84, 8400), true, true)
        }
        // Quantized I/O has no normalized write path; the config cannot fix the dtype.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 640, 640, 3), intArrayOf(1, 56, 8400), true, false)
        }
        // NCHW input needs a different preprocessing path.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 3, 640, 640), intArrayOf(1, 56, 8400), true, true)
        }
        // A rank-2 output is a classifier/headless graph, not a 3-D pose head.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 640, 640, 3), intArrayOf(1, 1001), true, true)
        }
    }

    @Test
    fun recognizesOnlyTfliteFiles() {
        assertTrue(PoseModelImport.isTflite(asset("yolo26n_pose.tflite")))
        assertFalse(PoseModelImport.isTflite("plain text".toByteArray()))
    }
}
