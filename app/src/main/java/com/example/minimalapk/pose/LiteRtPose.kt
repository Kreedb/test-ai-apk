package com.example.minimalapk.pose

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import com.example.minimalapk.BuildConfig
import com.example.minimalapk.log.AppLog
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer

internal class LiteRtBackend private constructor(
    private val model: CompiledModel,
    private val input: TensorBuffer,
    private val output: TensorBuffer,
    override val name: String,
    // GPU and NPU pass an Environment; CompiledModel does not own a caller-provided one.
    private val environment: Environment? = null,
) : PoseBackend {
    // The TFLite graph takes NHWC float RGB; onnx2tf emits the pose head as [1, 56, 8400].
    private val inputValues = FloatArray(PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE * 3)

    override fun analyze(pixels: IntArray): FloatArray {
        argbToRgbFloat(pixels, inputValues)
        input.writeFloat(inputValues)
        model.run(listOf(input), listOf(output))
        // Value-major [1, 56, 8400]; PosePostprocess reads it in place instead of transposing.
        return output.readFloat()
    }

    override fun close() {
        input.close()
        output.close()
        model.close()
        environment?.close()
    }

    companion object {
        private const val TAG = "LiteRtPose"

        fun open(assets: AssetManager, useGpu: Boolean): LiteRtBackend {
            if (!useGpu) {
                val model = CompiledModel.create(
                    assets,
                    PoseAnalyzer.TFLITE_MODEL_ASSET,
                    CompiledModel.Options(Accelerator.CPU),
                )
                return createBackend(model, "LiteRT (CPU)")
            }
            // The bundled MLDrift accelerator warns that OpenCL is not well supported on Adreno
            // 8xx with older drivers (kernel compile errors). It ships an OpenGL backend, so retry
            // there before giving up on the GPU.
            return try {
                openGpu(assets, backend = null, name = "LiteRT (GPU)")
            } catch (e: Exception) {
                AppLog.w(TAG, "LiteRT GPU 默认后端加载失败，改用 OpenGL 重试")
                openGpu(assets, CompiledModel.GpuOptions.Backend.OPENGL, "LiteRT (GPU/GL)")
            }
        }

        private fun openGpu(
            assets: AssetManager,
            backend: CompiledModel.GpuOptions.Backend?,
            name: String,
        ): LiteRtBackend {
            val started = System.nanoTime()
            // AUTOMATIC picks MLDrift's OpenCL backend wherever the device exposes OpenCL.
            val requested = backend?.name ?: "AUTOMATIC"
            // Request CPU too: a GPU-only request makes LiteRT reject the whole model when the
            // GPU delegate leaves any op undelegated ("Some ops are not accelerated"). With CPU
            // in the set those ops run on CPU and the rest still runs on the GPU.
            val options = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                if (backend != null) gpuOptions = CompiledModel.GpuOptions(backend = backend)
            }
            // CompiledModel does not own a caller-provided environment, so a failed compile must
            // release it before the OpenGL retry instead of leaking the failed GPU context.
            val environment = Environment.create()
            try {
                // Diagnostic only: a failed read must not abort the load.
                val available = runCatching {
                    environment.getAvailableAccelerators().joinToString()
                }.getOrElse { "读取失败（${it.message}）" }
                AppLog.i(TAG, "$name 加载开始：请求后端=$requested，可用加速器=$available")
                val model = CompiledModel.create(
                    assets,
                    PoseAnalyzer.TFLITE_MODEL_ASSET,
                    options,
                    environment,
                )
                val result = createBackend(model, name, environment)
                AppLog.i(TAG, "$name 加载成功：${elapsedMs(started)} ms，请求后端=$requested")
                return result
            } catch (e: Throwable) {
                // createBackend may have closed it already; JniHandle.close is idempotent.
                environment.close()
                AppLog.w(TAG, "$name 加载失败：${elapsedMs(started)} ms，请求后端=$requested", e)
                throw e
            }
        }

        /**
         * Requests the Qualcomm Hexagon NPU with no GPU fallback. LiteRT 2.3.2 rewrites a
         * single-NPU request to NPU+CPU, so a failed HTP compile can still run on CPU. This is an
         * accepted trade-off, not an oversight: the Kotlin API exposes only
         * [Environment.getAvailableAccelerators] (support, not the executed subgraph), and
         * [CompiledModel.QualcommOptions.Profiling] writes to the QNN logcat stream rather than
         * returning a result, so the probe cannot tell a CPU fallback from real HTP execution and
         * accepts the compiled model either way. The SM8850 gate makes a failed compile unlikely;
         * when it happens, the QNN log at DEBUG level is the source of truth. JIT compilation
         * happens synchronously and is cached in context.cacheDir, so only the first open pays.
         */
        fun openNpu(context: Context): LiteRtBackend {
            val started = System.nanoTime()
            AppLog.i(
                TAG,
                "NPU 初始化开始：model=${PoseAnalyzer.TFLITE_MODEL_ASSET}，" +
                    "SDK=${Build.VERSION.SDK_INT}，ABI=${Build.SUPPORTED_ABIS.joinToString()}，" +
                    "型号=${Build.MODEL}，JIT 编译可能较慢",
            )
            // The context overload enables LiteRT's compiler cache in cacheDir; the provider
            // points LiteRT at nativeLibraryDir, where useLegacyPackaging extracted the QNN libs.
            val environment = Environment.create(context, BuiltinNpuAcceleratorProvider(context))
            try {
                AppLog.i(
                    TAG,
                    "NPU 环境就绪（${elapsedMs(started)} ms）：" +
                        "可用加速器=${environment.getAvailableAccelerators().joinToString()}",
                )
                val options = CompiledModel.Options(Accelerator.NPU).apply {
                    qualcommOptions = CompiledModel.QualcommOptions(
                        // QNN logs go to logcat; keep them verbose for debug builds only.
                        logLevel = if (BuildConfig.DEBUG) {
                            CompiledModel.QualcommOptions.LogLevel.DEBUG
                        } else {
                            CompiledModel.QualcommOptions.LogLevel.WARN
                        },
                        htpPerformanceMode =
                            CompiledModel.QualcommOptions.HtpPerformanceMode.HIGH_PERFORMANCE,
                    )
                }
                val compileStarted = System.nanoTime()
                val model = CompiledModel.create(
                    context.assets,
                    PoseAnalyzer.TFLITE_MODEL_ASSET,
                    options,
                    environment,
                )
                AppLog.i(
                    TAG,
                    "NPU JIT 编译完成：${elapsedMs(compileStarted)} ms" +
                        "（LiteRT 可能在 HTP 编译失败时回退 CPU，详见 QNN 日志）",
                )
                return createBackend(model, "LiteRT (NPU)", environment)
            } catch (e: Throwable) {
                // CompiledModel.create did not take ownership of the environment on failure.
                environment.close()
                AppLog.w(TAG, "NPU 初始化失败：${elapsedMs(started)} ms（${e.message}）")
                throw e
            }
        }

        private fun createBackend(
            model: CompiledModel,
            name: String,
            environment: Environment? = null,
        ): LiteRtBackend {
            val input = try {
                model.createInputBuffers().first()
            } catch (e: Throwable) {
                model.close()
                environment?.close()
                throw e
            }
            val output = try {
                model.createOutputBuffers().first()
            } catch (e: Throwable) {
                input.close()
                model.close()
                environment?.close()
                throw e
            }
            return LiteRtBackend(model, input, output, name, environment)
        }

        private fun elapsedMs(started: Long): Int =
            ((System.nanoTime() - started) / 1_000_000L).toInt()
    }
}

/** Converts ARGB pixels to the interleaved NHWC RGB floats expected by the TFLite model. */
internal fun argbToRgbFloat(pixels: IntArray, out: FloatArray) {
    require(out.size >= pixels.size * 3) { "Output buffer is too small" }
    for (index in pixels.indices) {
        val pixel = pixels[index]
        out[index * 3] = ((pixel shr 16) and 0xff) / 255f
        out[index * 3 + 1] = ((pixel shr 8) and 0xff) / 255f
        out[index * 3 + 2] = (pixel and 0xff) / 255f
    }
}