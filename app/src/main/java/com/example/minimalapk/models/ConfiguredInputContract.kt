package com.example.minimalapk.models

import com.example.minimalapk.pose.GraphSignature
import com.example.minimalapk.pose.GraphTensor

internal enum class ConfiguredInputMode { CAMERA, PCM, ON_DEMAND }

/** AUDIO is an explicit user contract: mono, normalized raw waveform, never inferred features. */
internal data class ConfiguredPcmContract(val samples: Int, val sampleRate: Int)

internal data class ConfiguredInputContract(
    val mode: ConfiguredInputMode,
    val description: String,
    val pcm: ConfiguredPcmContract? = null,
)

internal fun configuredInputContract(config: ModelConfig, graph: GraphSignature): ConfiguredInputContract {
    configuredExecutionProblem(config, graph)?.let {
        return ConfiguredInputContract(ConfiguredInputMode.ON_DEMAND, "未就绪：$it；原模型和配置已保留，请调整配置或更换兼容运行时")
    }
    if (config.inputs.isNotEmpty() && config.inputs.all { it.source == InputSource.IMAGE }) {
        return ConfiguredInputContract(ConfiguredInputMode.CAMERA, "相机图片实时运行；也可选择图片单次运行")
    }
    val input = config.inputs.singleOrNull()
    if (input?.source == InputSource.AUDIO) {
        val problem = configuredWaveformProblem(input, graph.inputs.single())
            ?: when {
                input.sampleRate > 48_000 -> "共享麦克风仅支持配置为 8000–48000 Hz；此采样率请使用 WAV 文件"
                tensorElements(input.shape) > minOf(480_000, input.sampleRate * 30) -> "麦克风窗口最多 30 秒 / 480000 个采样；请使用 WAV 文件"
                config.outputs.any { it.render == OutputRender.BOXES || it.render == OutputRender.MOVENET } -> "此输出渲染需要图片输入"
                else -> null
            }
        if (problem == null) {
            val pcm = ConfiguredPcmContract(tensorElements(input.shape), input.sampleRate)
            return ConfiguredInputContract(ConfiguredInputMode.PCM,
                "共享麦克风：${pcm.sampleRate} Hz，${pcm.samples} 个单声道原始采样/窗口；也可选择 PCM16 WAV", pcm)
        }
        return ConfiguredInputContract(ConfiguredInputMode.ON_DEMAND, "麦克风未就绪：$problem；请检查配置后单次运行")
    }
    val reason = if (config.inputs.size > 1 && config.inputs.any { it.source == InputSource.AUDIO || it.source == InputSource.IMAGE }) {
        "混合输入不自动拼接相机/麦克风；请为每个输入选择文件或填写值"
    } else "请在模型配置填写 TEXT/NUMBERS 或选择文件后运行"
    return ConfiguredInputContract(ConfiguredInputMode.ON_DEMAND, "按需运行：$reason；保存并启用后结果显示在相机")
}

/** File facts plus this runner's actual protocol, not a claim that arbitrary operators will run. */
internal fun configuredExecutionProblem(config: ModelConfig, graph: GraphSignature): String? = runCatching {
    (graph.inputs + graph.outputs).forEach { tensor -> tensor.quantizationProblem?.let { error(it) } }
    require(config.inputs.size == graph.inputs.size && config.outputs.size == graph.outputs.size) { "配置与签名的输入/输出数量不符" }
    require(config.inputs.isNotEmpty()) { "当前 LiteRT Java API 不支持空输入签名" }
    val numericTypes = setOf(0, 2, 3, 4, 6, 7, 9)
    config.inputs.forEachIndexed { index, input ->
        val tensor = graph.inputs[index]
        validateInputShape(input.shape, tensor)
        if (input.source == InputSource.AUDIO) configuredWaveformProblem(input, tensor)?.let { error(it) }
        if (input.source == InputSource.TEXT) {
            require(tensor.type == 5) { "输入 $index 不是 STRING；tokenizer 不会自动生成，请提供 NUMBERS/BINARY token 张量" }
        } else {
            require(tensor.type in numericTypes) { "输入 $index 的 ${tensor.typeName} 不支持 ${input.source} 转换" }
        }
        if (input.source == InputSource.IMAGE) imageDimensions(input)
    }
    require(config.inputs.sumOf { tensorElements(it.shape).toLong() } <= MAX_TENSOR_ELEMENTS) { "输入张量合计超过 $MAX_TENSOR_ELEMENTS 个元素" }
    config.outputs.forEachIndexed { index, output ->
        val tensor = graph.outputs[index]
        require(tensor.type in numericTypes || tensor.type == 5) { "输出 $index 的 ${tensor.typeName} 尚不支持解码" }
        require(tensor.type != 5 || output.render == OutputRender.VALUES) { "STRING 输出 $index 需要 VALUES 显示，不能当作数值/图片" }
        if (output.render in listOf(OutputRender.MOVENET, OutputRender.BOXES)) {
            require(config.inputs.any { it.source == InputSource.IMAGE }) { "${output.render} 输出需要 IMAGE 输入；其它输入请使用 VALUES 等显示" }
        }
        if (output.render == OutputRender.BOXES) {
            require(listOf(output.classesOutput, output.scoresOutput, output.countOutput).all { it in graph.outputs.indices }) { "检测框关联输出索引越界" }
        }
        if (output.render == OutputRender.MOVENET) {
            require(tensor.shape.size == 4 && listOf(1, 1, 17, 3).withIndex().all { (axis, dimension) ->
                tensor.shape[axis] == dimension || tensor.signature.getOrNull(axis) == -1
            }) { "MoveNet 输出需要 [1,1,17,3]" }
        }
        if (output.render in listOf(OutputRender.IMAGE, OutputRender.SEGMENTATION)) {
            require(tensor.shape.size == 4 && (tensor.shape[0] == 1 || tensor.signature.getOrNull(0) == -1)) { "图片/分割输出需要 [1,H,W,C]" }
            if (output.render == OutputRender.IMAGE) require(tensor.shape[3] in listOf(1, 3) || tensor.signature.getOrNull(3) == -1) { "图片输出需要 1 或 3 个通道" }
        }
    }
}.exceptionOrNull()?.message

/** Shared by WAV and microphone paths so a spectrogram-shaped tensor is never fed raw samples. */
internal fun configuredWaveformProblem(input: TensorInputConfig, tensor: GraphTensor): String? {
    val shape = input.shape
    val waveform = shape.size == 1 || (shape.size == 2 && shape[0] == 1) ||
        (shape.size == 3 && shape[0] == 1 && shape[2] == 1)
    return when {
        !waveform || shape.any { it <= 0 } -> "AUDIO 需要单声道原始波形 [N]、[1,N] 或 [1,N,1]；不生成 mel/频谱特征，请提供 NUMBERS/BINARY 张量"
        tensor.type != 0 && !(tensor.type in listOf(3, 7, 9) && tensor.scale.isFinite() && tensor.scale > 0) ->
            "AUDIO 需要 FLOAT32 或带有效量化 scale 的 UINT8/INT8/INT16；输入值固定为 PCM / 32768，不猜测整数振幅约定"
        input.mean != 0f || input.std != 1f -> "AUDIO 使用 [-1,1] 原始波形，mean/std 必须为 0/1；其它预处理请提供 NUMBERS/BINARY 张量"
        input.sampleRate !in 8_000..192_000 -> "请按模型说明配置 8000–192000 Hz 采样率"
        else -> null
    }
}
