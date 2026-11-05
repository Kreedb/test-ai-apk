package com.example.minimalapk.pose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.min
import kotlin.math.roundToInt

private fun Long.nanosToMillis(): Float = this / 1_000_000f

data class PoseTimings(
    val preprocessMs: Float,
    val inferenceMs: Float,
    val postprocessMs: Float,
) {
    val totalMs: Float get() = preprocessMs + inferenceMs + postprocessMs
}

data class PoseFrame(
    val bitmap: Bitmap,
    val detections: List<PoseDetection>,
    val timings: PoseTimings,
)

class PoseAnalyzer(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val loaded = context.assets.open(MODEL_ASSET).use { asset ->
        loadSession(asset.readBytes())
    }
    private val session = loaded.session
    val executionProvider: String = loaded.provider
    private val inputName = session.inputNames.first()
    private val lock = Any()
    private var closed = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE)
    private val inputData = FloatArray(PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE * 3)

    fun analyze(source: Bitmap, rotationDegrees: Int): PoseFrame = synchronized(lock) {
        check(!closed) { "PoseAnalyzer is closed" }
        val start = System.nanoTime()
        val input = createInput(source, rotationDegrees)
        val features = input.toFloatArray()
        val preprocessed = System.nanoTime()
        val output = runModel(features)
        val inferred = System.nanoTime()
        val detections = PoseDecoder.decode(output)
        val finished = System.nanoTime()
        PoseFrame(
            bitmap = input,
            detections = detections,
            timings = PoseTimings(
                preprocessMs = (preprocessed - start).nanosToMillis(),
                inferenceMs = (inferred - preprocessed).nanosToMillis(),
                postprocessMs = (finished - inferred).nanosToMillis(),
            ),
        )
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { session.close() }
        }
    }

    private fun runModel(data: FloatArray): FloatArray {
        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, PoseNet.INPUT_SIZE.toLong(), PoseNet.INPUT_SIZE.toLong()),
        )
        return tensor.use {
            session.run(mapOf(inputName to it)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                buffer.rewind()
                FloatArray(buffer.remaining()).also(buffer::get)
            }
        }
    }

    private fun createInput(source: Bitmap, rotationDegrees: Int): Bitmap {
        val upright = if (rotationDegrees % 360 == 0) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                true,
            )
        }
        val scale = min(
            PoseNet.INPUT_SIZE.toFloat() / upright.width,
            PoseNet.INPUT_SIZE.toFloat() / upright.height,
        )
        val targetWidth = (upright.width * scale).roundToInt()
        val targetHeight = (upright.height * scale).roundToInt()
        val left = (PoseNet.INPUT_SIZE - targetWidth) / 2
        val top = (PoseNet.INPUT_SIZE - targetHeight) / 2
        val input = createBitmap(PoseNet.INPUT_SIZE, PoseNet.INPUT_SIZE)
        val canvas = Canvas(input)
        val padding = PoseNet.LETTERBOX_COLOR
        canvas.drawColor(Color.rgb(padding, padding, padding))
        canvas.drawBitmap(
            upright,
            null,
            Rect(left, top, left + targetWidth, top + targetHeight),
            paint,
        )
        if (upright !== source) upright.recycle()
        return input
    }

    private fun Bitmap.toFloatArray(): FloatArray {
        val planeSize = PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE
        getPixels(pixels, 0, PoseNet.INPUT_SIZE, 0, 0, PoseNet.INPUT_SIZE, PoseNet.INPUT_SIZE)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            inputData[index] = ((pixel shr 16) and 0xFF) / 255f
            inputData[planeSize + index] = ((pixel shr 8) and 0xFF) / 255f
            inputData[2 * planeSize + index] = (pixel and 0xFF) / 255f
        }
        return inputData
    }

    // Try every EP that ships with onnxruntime-android, strongest first, and keep the first
    // session that builds. NNAPI targets vendor GPU/NPU (API 27+; CPU_DISABLED needs 29+),
    // WebGPU targets the GPU through Vulkan/Dawn, and XNNPACK accelerates CPU kernels.
    // A device may reject an EP, so fall back to a plain CPU session if all of them fail.
    private fun loadSession(bytes: ByteArray): LoadedSession {
        for (backend in backends) {
            if (Build.VERSION.SDK_INT < backend.minSdk) continue
            runCatching {
                OrtSession.SessionOptions().use { options ->
                    backend.add(options)
                    environment.createSession(bytes, options)
                }
            }.onSuccess { return LoadedSession(it, backend.name) }
        }
        val session = OrtSession.SessionOptions().use { environment.createSession(bytes, it) }
        return LoadedSession(session, "CPU")
    }

    private data class LoadedSession(val session: OrtSession, val provider: String)

    private class Backend(
        val name: String,
        val minSdk: Int,
        val add: (OrtSession.SessionOptions) -> Unit,
    )

    companion object {
        const val MODEL_ASSET = "models/yolo26n-pose.onnx"

        private val backends = listOf(
            Backend("NNAPI", Build.VERSION_CODES.O_MR1) { options ->
                options.addNnapi(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        EnumSet.of(NNAPIFlags.CPU_DISABLED)
                    } else {
                        EnumSet.noneOf(NNAPIFlags::class.java)
                    },
                )
            },
            Backend("WebGPU", Build.VERSION_CODES.N) { it.addWebGPU(emptyMap()) },
            // XNNPACK keeps its own thread pool, so disable ORT intra-op spinning and give
            // XNNPACK the CPU cores, per the ORT XNNPACK provider's recommended config.
            Backend("XNNPACK", Build.VERSION_CODES.N) { options ->
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.setIntraOpNumThreads(1)
                options.addXnnpack(
                    mapOf(
                        "intra_op_num_threads" to
                            Runtime.getRuntime().availableProcessors().toString(),
                    ),
                )
            },
        )

        internal fun backendNames(apiLevel: Int): List<String> =
            backends.filter { apiLevel >= it.minSdk }.map { it.name }
    }
}
