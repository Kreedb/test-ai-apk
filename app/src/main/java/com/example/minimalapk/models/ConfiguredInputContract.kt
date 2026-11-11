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
