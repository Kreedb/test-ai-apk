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

    private fun contractOf(graph: TfliteGraph): PoseContract {
        val input = graph.inputs().single()
        val output = graph.outputs().single()
        return inferPoseContract(
            inputShape = graph.shape(input),
            outputShape = graph.shape(output),
            inputFloat32 = graph.elementType(input) == 0,
            outputFloat32 = graph.elementType(output) == 0,
        )
    }

    @Test
    fun readsBothBundledPoseContracts() {
        for (name in listOf("yolo26n_pose.tflite", "yolo11n_pose.tflite")) {
            val graph = TfliteGraph(asset(name))

            assertEquals(PoseContract(640, 8400, 17), contractOf(graph))
            assertEquals(0, graph.dynamicSignatureCount())
        }
    }

    @Test
    fun rewritesDynamicSignatureFromTheConcreteShape() {
        val graph = TfliteGraph(asset("yolo11n_pose.tflite"))
        val output = graph.outputs().single()
        graph.markSignatureDynamic(output)
        assertTrue(graph.dynamicSignatureCount() > 0)

        assertTrue(graph.staticize() > 0)

        assertEquals(0, graph.dynamicSignatureCount())
        assertArrayEquals(intArrayOf(1, 56, 8400), graph.shapeSignature(output))
    }

    @Test
    fun rejectsLayoutsTheDecoderCannotRead() {
        // Anchor-major [1, N, C]: same element count, wrong order.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 640, 640, 3), intArrayOf(1, 8400, 56), true, true)
        }
        // Detection model: 4 box + 1 class + 80 classes is not 5+3K.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 640, 640, 3), intArrayOf(1, 84, 8400), true, true)
        }
        // 26 keypoints would decode, but skeleton edges and measurements index COCO 17.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 640, 640, 3), intArrayOf(1, 83, 8400), true, true)
        }
        // Quantized I/O has no normalized write path.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 640, 640, 3), intArrayOf(1, 56, 8400), true, false)
        }
        // NCHW input.
        assertThrows(IllegalArgumentException::class.java) {
            inferPoseContract(intArrayOf(1, 3, 640, 640), intArrayOf(1, 56, 8400), true, true)
        }
    }

    @Test
    fun recognizesOnlyTfliteFiles() {
        assertTrue(PoseModelImport.isTflite(asset("yolo26n_pose.tflite")))
        assertFalse(PoseModelImport.isTflite("plain text".toByteArray()))
    }
}
