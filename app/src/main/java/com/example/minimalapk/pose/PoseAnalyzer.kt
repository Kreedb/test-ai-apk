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
import com.didi.aoe.runtime.ncnn.Interpreter
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

class PoseAnalyzer(
    context: Context,
    backend: InferenceBackend = InferenceBackend.AUTO,
) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val loaded = if (backend == InferenceBackend.NCNN) {
        LoadedEngine(NcnnEngine(context.applicationContext), InferenceBackend.NCNN.label)
    } else {
        context.assets.open(MODEL_ASSET).use { asset ->
            loadSession(asset.readBytes(), backend)
        }
    }
    private val engine = loaded.engine
    val executionProvider: String = loaded.provider
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
        val output = engine.run(features)
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
            runCatching { engine.close() }
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
    private fun loadSession(bytes: ByteArray, backend: InferenceBackend): LoadedEngine {
        for (candidate in backendPlan(backend, Build.VERSION.SDK_INT)) {
            if (candidate == InferenceBackend.CPU) {
                return LoadedEngine(createSession(bytes, null), InferenceBackend.CPU.label)
            }
            val spec = backends.first { it.backend == candidate }
            check(Build.VERSION.SDK_INT >= spec.minSdk) {
                "${candidate.label} 需要 Android ${spec.minSdk}+"
            }
            val session = runCatching { createSession(bytes, spec) }.getOrNull()
            if (session != null) return LoadedEngine(session, candidate.label)
        }
        if (backend == InferenceBackend.AUTO) {
            return LoadedEngine(createSession(bytes, null), InferenceBackend.CPU.label)
        }
        error("${backend.label} 后端创建失败")
    }

    private fun createSession(bytes: ByteArray, backend: Backend?): Engine {
        val session = OrtSession.SessionOptions().use { options ->
            backend?.add?.invoke(options)
            environment.createSession(bytes, options)
        }
        return OrtEngine(environment, session)
    }

    private data class LoadedEngine(val engine: Engine, val provider: String)

    private class Backend(
        val backend: InferenceBackend,
        val minSdk: Int,
        val add: (OrtSession.SessionOptions) -> Unit,
    )

    companion object {
        const val MODEL_ASSET = "models/yolo26n-pose.onnx"
        const val NCNN_DIR = "models"
        const val NCNN_PARAM_FILE = "yolo26n-pose.param"
        const val NCNN_MODEL_FILE = "yolo26n-pose.bin"

        fun hasNcnnModel(context: Context): Boolean {
            val files = context.assets.list(NCNN_DIR) ?: return false
            return NCNN_PARAM_FILE in files && NCNN_MODEL_FILE in files
        }

        private val backends = listOf(
            Backend(InferenceBackend.NNAPI, Build.VERSION_CODES.O_MR1) { options ->
                options.addNnapi(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        EnumSet.of(NNAPIFlags.CPU_DISABLED)
                    } else {
                        EnumSet.noneOf(NNAPIFlags::class.java)
                    },
                )
            },
            Backend(InferenceBackend.WEBGPU, Build.VERSION_CODES.N) { it.addWebGPU(emptyMap()) },
            // XNNPACK keeps its own thread pool, so disable ORT intra-op spinning and give
            // XNNPACK the CPU cores, per the ORT XNNPACK provider's recommended config.
            Backend(InferenceBackend.XNNPACK, Build.VERSION_CODES.N) { options ->
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
            backends.filter { apiLevel >= it.minSdk }.map { it.backend.label }

        internal fun backendPlan(
            backend: InferenceBackend,
            apiLevel: Int,
        ): List<InferenceBackend> = when (backend) {
            InferenceBackend.AUTO ->
                backends.filter { apiLevel >= it.minSdk }.map { it.backend } + InferenceBackend.CPU
            InferenceBackend.NCNN -> emptyList()
            else -> listOf(backend)
        }
    }
}

private interface Engine : AutoCloseable {
    fun run(data: FloatArray): FloatArray
}

private class OrtEngine(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : Engine {
    private val inputName = session.inputNames.first()

    override fun run(data: FloatArray): FloatArray {
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

    override fun close() {
        runCatching { session.close() }
    }
}

// AoE's ncnn runtime loads ncnn param/bin files, not the ONNX model used by the other backends.
// It only accepts a model whose output has the same 300x57 layout as the ONNX model; a model
// exported with `yolo export format=ncnn` may need extra NMS handling before it qualifies.
private class NcnnEngine(context: Context) : Engine {
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(INPUT_BYTES).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(OUTPUT_BYTES).order(ByteOrder.nativeOrder())

    init {
        check(PoseAnalyzer.hasNcnnModel(context)) {
            "缺少 ncnn 模型 ${PoseAnalyzer.NCNN_PARAM_FILE}/${PoseAnalyzer.NCNN_MODEL_FILE}，请先导出并放入 assets/${PoseAnalyzer.NCNN_DIR}"
        }
        interpreter = runCatching {
            Interpreter(
                Interpreter.Options().setNumberThreads(Runtime.getRuntime().availableProcessors()),
            )
        }.getOrElse {
            throw IllegalStateException("NCNN 后端不可用：当前 ABI 没有对应的原生库", it)
        }
        try {
            interpreter.loadModelAndParam(
                context.assets,
                PoseAnalyzer.NCNN_DIR,
                PoseAnalyzer.NCNN_MODEL_FILE,
                PoseAnalyzer.NCNN_PARAM_FILE,
                1,
                1,
                0,
                0,
            )
            check(interpreter.isLoadModelSuccess) { "ncnn 模型加载失败" }
            val bytes = runCatching { interpreter.getOutputTensor(0).numBytes() }.getOrDefault(0)
            check(bytes == 0 || bytes == OUTPUT_BYTES) { OUTPUT_MISMATCH }
        } catch (e: Throwable) {
            runCatching { interpreter.close() }
            throw e
        }
    }

    override fun run(data: FloatArray): FloatArray {
        input.clear()
        input.asFloatBuffer().put(data)
        input.rewind()
        output.clear()
        try {
            interpreter.run(input, output)
        } catch (e: BufferOverflowException) {
            throw IllegalStateException(OUTPUT_MISMATCH, e)
        }
        val bytes = runCatching { interpreter.getOutputTensor(0).numBytes() }.getOrDefault(0)
        check(bytes == OUTPUT_BYTES) { OUTPUT_MISMATCH }
        output.rewind()
        return FloatArray(OUTPUT_FLOATS).also { output.asFloatBuffer().get(it) }
    }

    override fun close() {
        runCatching { interpreter.close() }
    }

    private companion object {
        const val OUTPUT_MISMATCH = "NCNN 模型输出布局不兼容：需要 300×57 的浮点输出"
        val INPUT_BYTES = PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE * 3 * Float.SIZE_BYTES
        val OUTPUT_FLOATS = PoseNet.DETECTION_COUNT * PoseNet.VALUES_PER_DETECTION
        val OUTPUT_BYTES = OUTPUT_FLOATS * Float.SIZE_BYTES
    }
}
