package com.example.minimalapk.diagnostics

import android.content.Context
import android.os.Build
import com.example.minimalapk.BuildConfig
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.TfliteGraph
import com.example.minimalapk.pose.validatePoseModelConfig
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer

/** Pinned to the bundled YOLO26n case: users cannot accidentally benchmark a different model. */
internal class BackendComparisonLiteRt(private val context: Context) {
    private val spec = ModelStore.builtIn.single { it.id == "yolo26n_pose" }
    private val asset = checkNotNull(spec.assetPath)

    fun run(
        cancellation: BackendComparisonCancellation,
        plan: BackendComparisonPlan = BackendComparisonPlan(),
        backends: List<ComparisonBackend> = ComparisonBackend.entries,
        progress: (String, BackendComparisonReport?) -> Unit = { _, _ -> },
    ): BackendComparisonReport {
        cancellation.checkpoint()
        progress("核对内置 YOLO26n 与固定输入", null)
        val digest = context.assets.open(asset).use { checkDigest(it, cancellation::checkpoint) }
        require(digest.bytes in 1..64L * 1024 * 1024) { "内置模型超出比较器 64 MiB 预算" }
        cancellation.checkpoint()
        val graph = TfliteGraph(context.assets.open(asset).use { it.readBytes() }).describe()
        validatePoseModelConfig(spec, graph)
        val signature = graph.signatures.first { it.key.isEmpty() }
        val resource = BackendComparisonResource(spec.name, spec.source, digest.bytes, digest.sha256,
            signature.inputs.single().shape, signature.outputs.single().shape)
        val input = comparisonInput(spec.inputSize)
        val inputHash = comparisonInputHash(input)
        cancellation.checkpoint()
        val device = currentComparisonDevice()
        return BackendComparisonRunner().run(resource, input, inputHash, device.description, plan,
            cancellation, device::unavailable, { Session(it) }, backends, progress)
    }

    /** Every handle is acquired only in load(), so the runner owns even partially failed loads. */
    private inner class Session(private val backend: ComparisonBackend) : BackendComparisonSession {
        private var environment: Environment? = null
        private var model: CompiledModel? = null
        private val inputs = mutableListOf<TensorBuffer>()
        private val outputs = mutableListOf<TensorBuffer>()
        private var closed = false
        override var details: String = "runtime=LiteRT 2.3.2 CompiledModel"
            private set

        override fun load() {
            check(!closed && environment == null)
            val env = if (backend == ComparisonBackend.NPU) {
                Environment.create(context, BuiltinNpuAcceleratorProvider(context))
            } else Environment.create()
            environment = env
            val available = env.getAvailableAccelerators()
            details += " available=$available (availability is not executed-subgraph evidence)"
            val required = when (backend) {
                ComparisonBackend.CPU -> Accelerator.CPU
                ComparisonBackend.GPU -> Accelerator.GPU
                ComparisonBackend.NPU -> Accelerator.NPU
            }
            if (required !in available) throw BackendComparisonUnavailable("运行时未声明 $required 可用：$available")
            val options = when (backend) {
                ComparisonBackend.CPU -> CompiledModel.Options(Accelerator.CPU)
                // Same policy as the pose runner: partial delegation is allowed and reported unknown.
                ComparisonBackend.GPU -> CompiledModel.Options(Accelerator.GPU, Accelerator.CPU)
                ComparisonBackend.NPU -> CompiledModel.Options(Accelerator.NPU).apply {
                    qualcommOptions = CompiledModel.QualcommOptions(
                        logLevel = if (BuildConfig.DEBUG) CompiledModel.QualcommOptions.LogLevel.DEBUG
                            else CompiledModel.QualcommOptions.LogLevel.WARN,
                        htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.HIGH_PERFORMANCE,
                    )
                }
            }
            // One explicit candidate per row. A failed GPU compile remains FAILED, not a CPU result
            // relabelled GPU. The ordinary pose pipeline's optional OpenGL retry is not timed here.
            details += " gpuBackend=${if (backend == ComparisonBackend.GPU) "AUTOMATIC" else "n/a"}" +
                " qnnPerformance=${if (backend == ComparisonBackend.NPU) "HIGH_PERFORMANCE" else "n/a"}"
            val compiled = CompiledModel.create(context.assets, asset, options, env)
            model = compiled
            inputs += compiled.createInputBuffers()
            outputs += compiled.createOutputBuffers()
            require(inputs.size == 1 && outputs.size == 1) { "内置模型运行时 I/O 数量变化" }
        }

        override fun write(values: FloatArray) = inputs.single().writeFloat(values)
        override fun infer() = checkNotNull(model).run(inputs, outputs)
        override fun readback(): FloatArray = outputs.single().readFloat()

        override fun close() {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            fun release(operation: () -> Unit) {
                try { operation() } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
                }
            }
            outputs.forEach { release { it.close() } }
            inputs.forEach { release { it.close() } }
            model?.let { release { it.close() } }
            environment?.let { release { it.close() } }
            outputs.clear()
            inputs.clear()
            model = null
            environment = null
            failure?.let { throw it }
        }
    }
}

internal fun currentComparisonDevice(): BackendComparisonDevice {
    val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
    return BackendComparisonDevice(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(), manufacturer,
        "${Build.MANUFACTURER}/${Build.MODEL} API=${Build.VERSION.SDK_INT} ABI=${Build.SUPPORTED_ABIS.toList()} " +
            "SoC=$manufacturer/$soc app=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}")
}
