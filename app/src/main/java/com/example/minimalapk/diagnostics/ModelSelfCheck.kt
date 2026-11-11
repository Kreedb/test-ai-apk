package com.example.minimalapk.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import com.example.minimalapk.examples.AudioClassificationRunner
import com.example.minimalapk.examples.BOTTLENECK_SIZE
import com.example.minimalapk.examples.BottleneckExtractor
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.PERSONALIZATION_INPUT
import com.example.minimalapk.examples.SR_INPUT_SIZE
import com.example.minimalapk.examples.SR_OUTPUT_SIZE
import com.example.minimalapk.examples.SuperResolutionRunner
import com.example.minimalapk.examples.rankedSoundLabels
import com.example.minimalapk.examples.SoftmaxHead
import com.example.minimalapk.models.InputSource
import com.example.minimalapk.models.MAX_CONFIG_BYTES
import com.example.minimalapk.models.MAX_TENSOR_ELEMENTS
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.OutputRender
import com.example.minimalapk.models.checkRuntimeCompatibility
import com.example.minimalapk.models.configureInputs
import com.example.minimalapk.models.currentDeviceProfile
import com.example.minimalapk.models.decodeTensor
import com.example.minimalapk.models.execute
import com.example.minimalapk.models.validateInputShape
import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.GraphSignature
import com.example.minimalapk.pose.GraphTensor
import com.example.minimalapk.pose.LiteRtBackend
import com.example.minimalapk.pose.OutputLayout
import com.example.minimalapk.pose.PoseDecoder
import com.example.minimalapk.pose.TfliteGraph
import com.example.minimalapk.pose.validatePoseModelConfig
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.File
import java.io.FileNotFoundException
import java.util.Properties

/** One target, fresh native handles, closed on this worker before the next target starts. */
internal class ModelSelfCheck(
    private val context: Context,
    private val target: CheckTarget,
    private val checkpoint: () -> Unit,
    private val log: (String) -> Unit,
    private val onStage: (String) -> Unit,
) {
    var stage: String = "readiness"
        private set
    val timings = linkedMapOf<String, Long>()
    var inferenceRan: Boolean = false
        private set
    var validatedOutputs: Int = 0
        private set
    private var metadataLabels = emptyList<String>()
    private var knownExample = target.example
    private var interpretation = ""

    private fun <T> step(name: String, block: () -> T): T {
        checkpoint()
        stage = name
        onStage(name)
        log("stage=$name begin")
        val started = System.nanoTime()
        return try { block() } finally {
            val ms = (System.nanoTime() - started) / 1_000_000
            timings[name] = (timings[name] ?: 0) + ms
        }
    }

    fun run(): CheckResult {
        log("model=${target.name} source=${target.source} kind=${target.kind} " +
            "backend=CPU expectedBytes=${target.expectedBytes} expectedSha256=${target.expectedHash}")
        target.inventoryError?.let { throw it }
        if (target.kind == CheckKind.REFERENCE) throw CheckSkipped(target.note)
        val file = target.file
        if (target.note.isNotEmpty()) log("note=${target.note}")
        target.set?.let { set ->
            val root = File(context.filesDir, "model_assets")
            val missing = set.files.filter {
                val local = File(File(root, set.id), it.name)
                !local.isFile || local.length() != it.bytes
            }.map { it.name }
            // These are declaration checks, not probes. GPU/NPU are deliberately untested here;
            // a GPU-declared .tflite may still have runnable CPU kernels.
            val compatibility = checkRuntimeCompatibility(set, currentDeviceProfile(false, false))
            log("set=${set.id} filesReadyBySize=${set.isReadyIn(root)} missingOrWrongSize=$missing " +
                "declaredBackend=${set.backend} cpuOnlyCheckProfileReasons=${compatibility.reasons}; " +
                "GPU/NPU 未探测；本次只验证独立 CPU 模型组件，不代表整个场景可运行")
        }
        if (file != null && !file.isFile) {
            throw CheckSkipped("MISSING ${file.absolutePath}；请在模型/资源设置中下载或重新导入，本次未下载")
        }
        val digest = step("hash") {
            val digest = try { openInput().use { checkDigest(it, checkpoint) } }
            catch (error: FileNotFoundException) {
                if (file?.isFile == true) throw error
                throw CheckSkipped("MISSING ${target.source}；请重新安装/导入模型")
            }
            log("bytes=${digest.bytes} sha256=${digest.sha256}")
            require(digest.bytes > 0) { "模型/资源文件为空" }
            target.expectedBytes?.let { require(digest.bytes == it) {
                "文件大小不符：期望 $it，实际 ${digest.bytes}；请重新下载此资源"
            } }
            target.expectedHash?.let { require(digest.sha256 == it) {
                "SHA-256 不符：期望 $it，实际 ${digest.sha256}；请重新下载/导入，未改动现有文件"
            } }
            digest
        }
        if (target.kind == CheckKind.RESOURCE && file?.name == "personalization-head.bin") {
            step("checkpoint-load+inference") {
                val head = SoftmaxHead(BOTTLENECK_SIZE)
                head.restore(file)
                val scores = head.predict(FloatArray(BOTTLENECK_SIZE) { (it % 13) / 13f })
                inferenceRan = true
                require(scores.size == 4 && scores.all { it.isFinite() && it in 0f..1f }) { "本机分类头输出无效" }
                require(kotlin.math.abs(scores.sum() - 1f) < 0.0001f) { "本机分类头概率之和无效" }
                validatedOutputs = 1
                log("personalization head classes=${scores.size} finite=true named=${head.profile != null}; 存档只读")
            }
            return CheckResult(target.id, CheckStatus.PASS, "validated", "本机分类头只读恢复与合成特征推理通过；未训练或保存",
                timings.toMap(), inferenceRan, validatedOutputs)
        }
        if (knownExample == null) knownExample = selfCheckExamples.firstOrNull { it.sha256 == digest.sha256 }
        knownExample?.let { log("recognizedExample=${it.id} verifiedSha256=${digest.sha256}") }
        val remote = com.example.minimalapk.pose.ModelCatalog.models.firstOrNull { it.sha256 == digest.sha256 }
        if (remote != null) log("recognizedDownloadablePose=${remote.name}; 原始目录 SHA-256 匹配")
        else if (target.pose?.imported == true) log("downloadablePoseHashMatch=false；导入静态化可能改变 hash，本地 I/O 单独验证")
        if (file != null && !file.extension.equals("tflite", true)) {
            throw CheckSkipped(if (file.extension.equals("litertlm", true)) {
                "资源大小/hash 已核验，但 APK 未集成 .litertlm 运行时；未执行推理"
            } else {
                "辅助资源大小/hash 已读取${if (target.expectedHash == null) "（无参考 hash）" else "并核验"}" +
                    "（${file.extension}），不是独立可推理模型；未执行推理。${target.note}"
            })
        }
        step("format") {
            val magic = ByteArray(8)
            openInput().use { input ->
                var read = 0
                while (read < magic.size) {
                    val count = input.read(magic, read, magic.size - read)
                    require(count > 0) { "TFLite 文件头被截断" }
                    read += count
                }
            }
            require(String(magic, 4, 4, Charsets.US_ASCII) == "TFL3") { "不是 TFLite 模型（缺少 TFL3 标识）" }
        }
        step("memory") {
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
            if (memory.lowMemory || memory.availMem > 0 && digest.bytes > memory.availMem / 4) {
                throw CheckSkipped("内存预算不足：文件 ${digest.bytes} bytes，可用 ${memory.availMem} bytes；" +
                    "自检保守预留至少 4×权重大小，关闭其他应用或使用更小模型后重试")
            }
            log("availableMemoryBytes=${memory.availMem}; 中间张量内存仍取决于模型，非性能基准")
        }
        val configText = target.configured?.let { model ->
            step("config") {
                if (!model.configFile.isFile) throw CheckSkipped(
                    "MISSING_CONFIG ${model.configFile.name}；请在自定义模型中保存输入/输出配置后重试")
                require(model.configFile.length() <= MAX_CONFIG_BYTES) { "配置超过 1 MiB" }
                model.configFile.readText().also { text ->
                    log("configFile=${model.configFile.name} configSha256=" +
                        text.byteInputStream().use { checkDigest(it).sha256 })
                }
            }
        }
        // Inspect small graphs before allocating native tensors. Large catalog weights stay mmap-
        // backed in Interpreter; runtime tensors below provide their complete selected I/O contract.
        val description = step("inspect") {
            val size = digest.bytes
            if (size <= 64L * 1024 * 1024) {
                TfliteGraph(openInput().use { it.readBytes() }).describe().also { graph ->
                    log("signatures=${graph.signatures.map { it.key.ifEmpty { "<main>" } }} customOps=${graph.customOps}")
                }
            } else {
                log("largeModelBytes=$size；使用文件映射和运行时 I/O，不把权重读入 ByteArray")
                null
            }
        }
        val requestedSignature = configText?.let {
            Properties().apply { load(it.reader()) }.getProperty("signature", "")
        }
        val personalization = knownExample == ExampleCatalog.personalization
        description?.let { graph ->
            val key = if (target.pose != null) "" else chooseCheckSignature(
                graph.signatures.map { it.key }.filter { it.isNotEmpty() }, requestedSignature, personalization)
            val selected = graph.signatures.singleOrNull { it.key == key }
                ?: error("配置签名 '$key' 不存在")
            if (!personalization && graph.customOps.any {
                Regex("Save|Restore|WriteFile", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }) throw CheckSkipped("模型含文件持久化算子 ${graph.customOps}，不能确认此入口无副作用，未执行")
            checkSignature(selected, personalization, graph.signatures.map { it.key })
            logGraph(selected)
            (selected.inputs + selected.outputs).forEach { checkTensorType(it.type) }
            selected.inputs.filter { tensor -> tensor.shape.all { it > 0 } }
                .forEach { checkInputElements(it.shape) }
            selected.outputs.filter { tensor -> tensor.shape.isNotEmpty() && tensor.shape.all { it > 0 } }
                .forEach { checkInputElements(it.shape) }
            if (configText == null) selected.inputs.forEach { checkInputElements(it.shape) }
            else step("config") { logConfig(ModelConfig.decode(configText, graph)) }
        }
        if (target.pose != null) {
            if (description == null) throw CheckSkipped("姿态模型超过检查器的 64 MiB 结构解析预算；需要扩展只读模型检查器")
            checkPose(description)
        } else {
            knownExample?.let { example ->
                metadataLabels = step("labels") {
                    ExampleDownload.labels(checkNotNull(target.file), example).also { log("metadataLabels=${it.size}") }
                }
            }
            checkInterpreter(configText, description, requestedSignature, personalization)
            if (target.kind == CheckKind.EXAMPLE) checkExamplePipeline()
            if (target.configured != null && knownExample == ExampleCatalog.audio) checkAudioPipeline()
        }
        checkpoint()
        return CheckResult(target.id, CheckStatus.PASS, "validated",
            "独立 CPU 合成输入推理及输出校验通过；${coverageDetail()}；" +
                "不代表真实场景准确率、GPU/NPU 或多组件端到端通过",
            timings.toMap(), inferenceRan, validatedOutputs)
    }

    private fun openInput() = target.file?.inputStream()
        ?: context.assets.open(checkNotNull(target.assetPath))

    private fun checkSignature(graph: GraphSignature, personalization: Boolean, keys: List<String>) {
        interpretation = "selectedSignature=${graph.key.ifEmpty { "<main>" }} otherSignaturesNotInvoked=" +
            keys.filter { it != graph.key }
        log(interpretation)
        signatureSkipReason(graph.key, graph.inputs.map { it.name }, personalization)?.let { throw CheckSkipped(it) }
        if (graph.key.isEmpty() && keys.any { signatureSkipReason(it, emptyList(), false) != null }) {
            throw CheckSkipped("主子图与训练/状态签名共存，无法证明主子图无副作用；请选择明确的推理签名")
        }
        if (graph.inputs.isEmpty()) throw CheckSkipped("当前 Interpreter Java API 不支持无输入签名")
        if (graph.outputs.isEmpty()) throw CheckSkipped("签名没有可验证输出，不能判定推理 PASS")
    }

    private fun checkInterpreter(
        configText: String?,
        description: GraphDescription?,
        requestedSignature: String?,
        personalization: Boolean,
    ) {
        val modelFile = checkNotNull(target.file)
        val options = Interpreter.Options().setNumThreads(2).setUseXNNPACK(false)
        // LiteRT caches output shapes in Tensor wrappers. Inspect large models on a separate
        // interpreter so the execution interpreter first reads its outputs after invoke.
        val inspected = description ?: step("inspect-runtime") {
            val inspector = Interpreter(modelFile, options)
            try {
                val key = chooseCheckSignature(inspector.signatureKeys.toList(), requestedSignature, personalization)
                GraphDescription(listOf(runtimeGraph(inspector, key)), emptyList())
            } finally { stepClose { inspector.close() } }
        }
        val interpreter = step("load") {
            Interpreter(modelFile, options)
        }
        try {
            val keys = interpreter.signatureKeys.toList()
            val key = chooseCheckSignature(keys, requestedSignature, personalization)
            val graph = inspected.signatures.single { it.key == key }
            checkSignature(graph, personalization, keys)
            val config = step("config") {
                val configured = if (configText != null) {
                    ModelConfig.decode(configText, inspected)
                } else exampleConfig(ModelConfig.infer(graph))
                val inputCount = if (key.isEmpty()) interpreter.inputTensorCount else interpreter.getSignatureInputs(key).size
                val outputCount = if (key.isEmpty()) interpreter.outputTensorCount else interpreter.getSignatureOutputs(key).size
                require(configured.inputs.size == inputCount && configured.outputs.size == outputCount) {
                    "配置 I/O 数量与运行时不符"
                }
                configured.inputs.forEachIndexed { index, input ->
                    checkInputElements(input.shape)
                    validateInputShape(input.shape, graph.inputs[index])
                }
                if (configured.inputs.sumOf { checkInputElements(it.shape).toLong() } > MAX_TENSOR_ELEMENTS) {
                    throw CheckSkipped("输入元素合计超过 $MAX_TENSOR_ELEMENTS 自检预算")
                }
                log("configSource=${if (configText != null) "stored" else if (knownExample != null) "example contract" else "inferred tensor smoke"}")
                if (description == null || configText == null) logConfig(configured)
                configured
            }
            (graph.inputs + graph.outputs).forEach { checkTensorType(it.type) }
            graph.outputs.filter { it.shape.isNotEmpty() && it.shape.all { size -> size > 0 } }
                .forEach { checkInputElements(it.shape) }
            logGraph(graph)
            val images = mutableMapOf<Int, Bitmap>()
            val rendered = mutableListOf<Bitmap>()
            try {
                val inputs = step("prepare") {
                    configureInputs(interpreter, config)
                    Array<Any>(graph.inputs.size) { index ->
                        val synthetic = syntheticInput(graph.inputs[index], config.inputs[index])
                        synthetic.pixels?.let { pixels ->
                            images[index] = Bitmap.createBitmap(pixels, synthetic.width, synthetic.height, Bitmap.Config.ARGB_8888)
                        }
                        synthetic.value
                    }
                }
                log("syntheticInputShape=${config.inputs.map { it.shape }} inputs=${config.inputs.map { input ->
                    when (input.source) {
                        InputSource.IMAGE -> "deterministic RGB pattern; configured normalization/layout"
                        InputSource.AUDIO -> "440Hz sine amplitude=0.01 rate=${input.sampleRate}"
                        InputSource.TEXT -> "self check"
                        InputSource.NUMBERS -> "configured numeric values; configuration hash logged"
                        InputSource.BINARY -> "zero-filled raw tensor (user file not opened)"
                    }
                }}")
                // Shared app execution handles multi-input and signature resizing with shaped Java
                // arrays. It also exercises configured image/box/segmentation rendering.
                step("inference+render") {
                    try {
                        val result = execute(interpreter, config, graph, inputs, images)
                        inferenceRan = true
                        rendered += result.images.map { it.second }
                    } finally {
                        // Rendering can fail after a successful invoke. Preserve that distinction
                        // without ever treating a failed render/validation as PASS.
                        val nativeNs = interpreter.lastNativeInferenceDurationNanoseconds
                        if (nativeNs != null) inferenceRan = true
                        log("nativeInferenceNs=$nativeNs")
                    }
                }
                step("validate") {
                    var nonemptyOutputs = 0
                    graph.outputs.forEachIndexed { index, expected ->
                        checkpoint()
                        val output = if (key.isEmpty()) interpreter.getOutputTensor(index)
                        else interpreter.getOutputTensorFromSignature(expected.name, key)
                        require(output.dataType().toString() == expected.typeName) {
                            "输出 ${expected.name} 类型不符：声明 ${expected.typeName} 实际 ${output.dataType()}"
                        }
                        val quantization = output.quantizationParams()
                        log("output[$index] name=${expected.name} actualShape=${output.shape().toList()} " +
                            "actualType=${output.dataType()} bytes=${output.numBytes()} " +
                            "quantization=${quantization.scale}/${quantization.zeroPoint}")
                        val stats = checkOutputBuffer(expected, output.shape().toList(), output.asReadOnlyBuffer(),
                            quantization.scale, quantization.zeroPoint.toLong(), checkpoint)
                        checkKnownOutput(index, output.shape().toList(), key)
                        if (output.numElements() > 0) nonemptyOutputs++
                        if (knownExample == ExampleCatalog.audio && index == 0) {
                            val labels = config.outputs[index].labels.ifEmpty { metadataLabels }
                            rankedSoundLabels(labels, decodeTensor(output.asReadOnlyBuffer(), expected.type,
                                quantization.scale, quantization.zeroPoint.toLong()))
                        }
                        log("output[$index] name=${expected.name} $stats")
                        validatedOutputs++
                    }
                    if (nonemptyOutputs == 0) throw CheckSkipped("推理已调用，但全部输出为空，不能验证数值有效性")
                }
            } finally {
                (images.values + rendered).distinct().forEach { it.recycle() }
            }
        } finally {
            // Never close native handles on the UI thread, including after lifecycle cancellation.
            stepClose { interpreter.close() }
        }
    }

    private fun runtimeGraph(interpreter: Interpreter, key: String): GraphSignature {
        fun Tensor.description(alias: String = name()): GraphTensor {
            val type = when (dataType().toString()) {
                "FLOAT32" -> 0; "INT32" -> 2; "UINT8" -> 3; "INT64" -> 4
                "STRING" -> 5; "BOOL" -> 6; "INT16" -> 7; "INT8" -> 9
                else -> throw CheckSkipped("运行时张量 $alias 类型 ${dataType()} 尚无合成输入/输出转换器")
            }
            val quantization = quantizationParams()
            return GraphTensor(alias, shape().toList(), shapeSignature().toList(), type,
                quantization.scale, quantization.zeroPoint.toLong())
        }
        return if (key.isEmpty()) {
            GraphSignature("", 0,
                List(interpreter.inputTensorCount) { interpreter.getInputTensor(it).description() },
                List(interpreter.outputTensorCount) { interpreter.getOutputTensor(it).description() })
        } else {
            require(key in interpreter.signatureKeys) { "配置签名 '$key' 不存在：${interpreter.signatureKeys.toList()}" }
            GraphSignature(key, 0,
                interpreter.getSignatureInputs(key).map { interpreter.getInputTensorFromSignature(it, key).description(it) },
                interpreter.getSignatureOutputs(key).map { interpreter.getOutputTensorFromSignature(it, key).description(it) })
        }
    }

    private fun exampleConfig(inferred: ModelConfig): ModelConfig {
        val example = knownExample ?: return inferred
        val labels = metadataLabels
        return inferred.copy(
            inputs = inferred.inputs.map { input -> when (example) {
                ExampleCatalog.classification, ExampleCatalog.detection, ExampleCatalog.segmentation -> input.copy(mean = 127.5f, std = 127.5f)
                ExampleCatalog.superResolution -> input.copy(mean = 0f, std = 1f)
                ExampleCatalog.audio -> input.copy(source = InputSource.AUDIO)
                else -> input
            } },
            outputs = inferred.outputs.mapIndexed { index, output -> when {
                example == ExampleCatalog.detection && index == 0 -> output.copy(render = OutputRender.BOXES, labels = labels)
                example == ExampleCatalog.detection -> output.copy(render = OutputRender.VALUES)
                example == ExampleCatalog.segmentation -> output.copy(render = OutputRender.SEGMENTATION)
                example == ExampleCatalog.superResolution -> output.copy(render = OutputRender.IMAGE)
                else -> output.copy(labels = labels)
            } },
        )
    }

    private fun checkKnownOutput(index: Int, shape: List<Int>, signature: String) {
        if (index != 0) return
        val expected = when (knownExample) {
            ExampleCatalog.moveNet -> listOf(1, 1, 17, 3)
            ExampleCatalog.superResolution -> listOf(1, SR_OUTPUT_SIZE, SR_OUTPUT_SIZE, 3)
            ExampleCatalog.personalization -> if (signature == "load") listOf(1, BOTTLENECK_SIZE) else null
            else -> null
        }
        if (expected != null) require(shape == expected) { "示例输出应为 $expected，实际 $shape" }
    }

    private fun checkPose(description: GraphDescription) {
        val spec = checkNotNull(target.pose)
        val graph = description.signatures.first()
        step("config") {
            validatePoseModelConfig(spec, description)
            require(graph.inputs.size == 1 && graph.outputs.size == 1) { "姿态管线要求单输入/单输出" }
            val input = graph.inputs.single()
            val output = graph.outputs.single()
            val expectedInput = listOf(1, spec.inputSize, spec.inputSize, 3)
            val expectedOutput = if (spec.outputLayout == OutputLayout.VALUE_MAJOR) {
                listOf(1, spec.valuesPerAnchor, spec.anchors)
            } else listOf(1, spec.anchors, spec.valuesPerAnchor)
            checkInputElements(expectedInput)
            checkInputElements(expectedOutput)
            require(input.type == 0 && output.type == 0 && input.shape == expectedInput && output.shape == expectedOutput) {
                "姿态配置/模型 I/O 不符：需要 $expectedInput → $expectedOutput FLOAT32；请修正输入尺寸、锚点、关键点或布局"
            }
            log("poseConfig=$spec")
            logGraph(graph)
        }
        val pixels = step("prepare") { syntheticPixels(spec.inputSize, spec.inputSize) }
        val backend = step("load") { LiteRtBackend.open(context, spec, useGpu = false) }
        try {
            val output = step("inference") { backend.analyze(pixels).also { inferenceRan = true } }
            step("validate+decode") {
                require(output.size == spec.anchors * spec.valuesPerAnchor) { "姿态输出元素数量不符" }
                require(output.all { it.isFinite() }) { "姿态输出包含 NaN/Infinity" }
                val decoded = PoseDecoder.decode(output, spec)
                require(decoded.all { pose ->
                    listOf(pose.left, pose.top, pose.right, pose.bottom, pose.score).all { it.isFinite() } &&
                        pose.keypoints.all { it.x.isFinite() && it.y.isFinite() && it.confidence.isFinite() }
                }) { "姿态解码产生非有限数值" }
                validatedOutputs = 1
                log("backend=${backend.name} outputShape=${graph.outputs.single().shape} elements=${output.size} " +
                    "finite=true detections=${decoded.size}; 合成图允许零检测")
            }
        } finally { stepClose { backend.close() } }
    }

    private fun checkExamplePipeline() {
        val example = knownExample
        if (example != ExampleCatalog.superResolution && example != ExampleCatalog.personalization) return
        val size = if (example == ExampleCatalog.superResolution) SR_INPUT_SIZE else PERSONALIZATION_INPUT
        val source = Bitmap.createBitmap(syntheticPixels(size, size), size, size, Bitmap.Config.ARGB_8888)
        try {
            step("example-pipeline") {
                if (example == ExampleCatalog.superResolution) {
                    SuperResolutionRunner(checkNotNull(target.file)).use { runner ->
                        val image = runner.run(source)
                        try { require(image.width == SR_OUTPUT_SIZE && image.height == SR_OUTPUT_SIZE) { "超分管线输出尺寸错误" } }
                        finally { image.recycle() }
                    }
                    log("SuperResolutionRunner CPU 50→200 单块调用通过，原始张量已逐元素验证；相机分块拼接未验证")
                } else {
                    BottleneckExtractor(checkNotNull(target.file)).use { extractor ->
                        val features = extractor.extract(source)
                        require(features.size == BOTTLENECK_SIZE && features.all { it.isFinite() }) { "load 特征无效" }
                    }
                    log("BottleneckExtractor load 调用通过；train/save/restore 与用户分类头存档未调用")
                }
            }
        } finally { source.recycle() }
    }

    private fun checkAudioPipeline() {
        step("audio-pipeline") {
            AudioClassificationRunner(checkNotNull(target.configured)).use { runner ->
                checkInputElements(listOf(runner.windowSamples))
                val scores = runner.classify(FloatArray(runner.windowSamples) { index ->
                    (0.01 * kotlin.math.sin(2 * Math.PI * 440 * index / runner.sampleRate)).toFloat()
                })
                require(scores.isNotEmpty() && scores.all { it.score.isFinite() && it.label.isNotBlank() }) {
                    "声音识别管线输出/标签无效"
                }
                log("AudioClassificationRunner samples=${runner.windowSamples} rate=${runner.sampleRate} " +
                    "classes=${scores.size} finite=true")
            }
        }
    }

    private fun logGraph(graph: GraphSignature) {
        log("IO signature=${graph.key.ifEmpty { "<main>" }} inputs=${graph.inputs} outputs=${graph.outputs}")
    }

    fun coverageDetail(): String = interpretation.ifEmpty { "selectedSignature=not reached" }

    private fun logConfig(config: ModelConfig) {
        // Input contents/labels can be private; log their hashes/counts, not user text.
        log("signature=${config.signature.ifEmpty { "<main>" }} " +
            "inputs=${config.inputs.map { it.copy(values = "<synthetic/configured numeric>") }} " +
            "outputs=${config.outputs.map { it.copy(labels = listOf("count=${it.labels.size}")) }}")
    }

    private fun stepClose(close: () -> Unit) {
        // Cleanup must run even when checkpoint() would throw for a cancelled run.
        val started = System.nanoTime()
        try { close() } catch (error: Throwable) {
            stage = "close"
            throw error
        } finally { timings["close"] = (System.nanoTime() - started) / 1_000_000 }
    }
}
