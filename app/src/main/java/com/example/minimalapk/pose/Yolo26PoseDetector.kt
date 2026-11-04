package com.example.minimalapk.pose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import java.nio.FloatBuffer

private const val MODEL_ASSET = "yolo26-pose.onnx"
private const val INPUT_SIZE = 640
internal const val DETECTION_COUNT = 300
internal const val OUTPUT_COLUMNS = 57
internal const val KEYPOINT_COUNT = 17
internal const val DEFAULT_SCORE_THRESHOLD = 0.25f

/**
 * Runs the end-to-end YOLO26-pose ONNX export: input [1, 3, 640, 640] normalized
 * RGB, output [1, 300, 57] with xyxy box, score, class and 17 (x, y, confidence)
 * triplets, all normalized to 0..1.
 */
class Yolo26PoseDetector(context: Context) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val environment = OrtEnvironment.getEnvironment()
    private val lock = Any()
    private var session: OrtSession? = null

    fun detect(bitmap: Bitmap): PosePerson? = synchronized(lock) {
        val activeSession = session ?: createSession().also { session = it }
        val input = bitmap.toFloatInput()
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            activeSession.run(mapOf(activeSession.inputNames.first() to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val dims = output.info.shape
                require(
                    dims.size == 3 &&
                        dims[0] == 1L &&
                        dims[1] == DETECTION_COUNT.toLong() &&
                        dims[2] == OUTPUT_COLUMNS.toLong(),
                ) {
                    "YOLO26-pose 输出形状不支持：${dims.contentToString()}，" +
                        "请使用 end2end 导出的 ONNX 模型（[1, 300, 57]）"
                }
                val values = FloatArray(DETECTION_COUNT * OUTPUT_COLUMNS)
                val buffer = output.floatBuffer
                buffer.rewind()
                buffer.get(values)
                return decodeDetections(values, bitmap.width, bitmap.height)
            }
        }
    }

    private fun createSession(): OrtSession {
        val model = try {
            assets.open(MODEL_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException(
                "缺少模型资源 $MODEL_ASSET，请把 YOLO26-pose ONNX 模型放入 app/src/main/assets/ 后重新构建",
                e,
            )
        }
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(model, options)
        }
    }

    override fun close() {
        synchronized(lock) {
            session?.close()
            session = null
        }
    }
}

internal fun decodeDetections(
    output: FloatArray,
    sourceWidth: Int,
    sourceHeight: Int,
    scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
): PosePerson? {
    var best: PosePerson? = null
    var bestScore = scoreThreshold
    for (index in 0 until DETECTION_COUNT) {
        val base = index * OUTPUT_COLUMNS
        val score = output[base + 4]
        if (score < bestScore || output[base + 5] != 0f) continue
        bestScore = score
        best = PosePerson(
            boxLeft = output[base] * sourceWidth,
            boxTop = output[base + 1] * sourceHeight,
            boxRight = output[base + 2] * sourceWidth,
            boxBottom = output[base + 3] * sourceHeight,
            score = score,
            keypoints = List(KEYPOINT_COUNT) { keypoint ->
                val offset = base + 6 + keypoint * 3
                Keypoint(
                    x = output[offset] * sourceWidth,
                    y = output[offset + 1] * sourceHeight,
                    // The export divides the whole keypoint tensor, confidence included, by 640.
                    confidence = (output[offset + 2] * INPUT_SIZE).coerceIn(0f, 1f),
                )
            },
        )
    }
    return best
}

private fun Bitmap.toFloatInput(): FloatArray {
    val scaled = scale(INPUT_SIZE, INPUT_SIZE)
    val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
    if (scaled !== this) scaled.recycle()

    val planeSize = INPUT_SIZE * INPUT_SIZE
    val input = FloatArray(3 * planeSize)
    for (index in pixels.indices) {
        val color = pixels[index]
        input[index] = ((color shr 16) and 0xFF) / 255f
        input[planeSize + index] = ((color shr 8) and 0xFF) / 255f
        input[2 * planeSize + index] = (color and 0xFF) / 255f
    }
    return input
}
