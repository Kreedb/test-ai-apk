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
    // Only NPU passes an Environment; CompiledModel does not own a caller-provided one.
    private val environment: Environment? = null,
) : PoseBackend {
    // The TFLite graph takes NHWC float RGB; onnx2tf emits the pose head as [1, 56, 8400].
    private val inputValues = FloatArray(PoseNet.INPUT_SIZE * PoseNet.INPUT_SIZE * 3)

    override fun analyze(pixels: IntArray): FloatArray {
        argbToRgbFloat(pixels, inputValues)
        input.writeFloat(inputValues)
        model.run(listOf(input), listOf(output))
        return transposeOutput(output.readFloat())
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
            val options = if (useGpu) {
                CompiledModel.Options(Accelerator.GPU)
            } else {
                CompiledModel.Options(Accelerator.CPU)
            }
            val model = CompiledModel.create(assets, PoseAnalyzer.TFLITE_MODEL_ASSET, options)
            return createBackend(model, if (useGpu) "LiteRT (GPU)" else "LiteRT (CPU)")
        }

        /**
         * Requests the Qualcomm Hexagon NPU with no GPU fallback. LiteRT 2.3.2 rewrites a
         * single-NPU request to NPU+CPU, so a failed HTP compile can still run on CPU; the QNN
         * log at DEBUG level is the source of truth. JIT compilation happens synchronously and
         * is cached in context.cacheDir, so only the first open pays for it.
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

internal fun transposeOutput(raw: FloatArray): FloatArray {
    require(raw.size == PosePostprocess.ANCHOR_COUNT * PosePostprocess.VALUES_PER_ANCHOR) {
        "Unexpected LiteRT output size: ${raw.size}"
    }
    val output = FloatArray(raw.size)
    for (anchor in 0 until PosePostprocess.ANCHOR_COUNT) {
        for (value in 0 until PosePostprocess.VALUES_PER_ANCHOR) {
            output[anchor * PosePostprocess.VALUES_PER_ANCHOR + value] =
                raw[value * PosePostprocess.ANCHOR_COUNT + anchor]
        }
    }
    return output
}
