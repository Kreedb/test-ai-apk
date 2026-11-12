package com.example.minimalapk.models

import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.GraphSignature
import com.example.minimalapk.pose.GraphTensor
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

internal enum class InputSource { IMAGE, NUMBERS, TEXT, AUDIO, BINARY }
internal enum class ImageLayout { NHWC, NCHW }
internal enum class OutputRender { VALUES, CLASSIFICATION, IMAGE, MOVENET, SEGMENTATION, BOXES }

internal data class TensorInputConfig(
    val shape: List<Int>,
    val source: InputSource,
    val layout: ImageLayout = ImageLayout.NHWC,
    val mean: Float = 0f,
    val std: Float = 1f,
    val values: String = "0",
    val sampleRate: Int = 16000,
)

internal data class TensorOutputConfig(
    val render: OutputRender = OutputRender.VALUES,
    val scale: Float = 1f,
    val offset: Float = 0f,
    val labels: List<String> = emptyList(),
    val threshold: Float = 0.3f,
    val classesOutput: Int = 1,
    val scoresOutput: Int = 2,
    val countOutput: Int = 3,
)

internal data class ModelConfig(
    val signature: String,
    val inputs: List<TensorInputConfig>,
    val outputs: List<TensorOutputConfig>,
) {
    fun encode(): String {
        val properties = Properties()
        fun put(key: String, value: Any) { properties.setProperty(key, value.toString()) }
        put("version", 1)
        put("signature", signature)
        inputs.forEachIndexed { index, input ->
            val prefix = "input.$index."
            put(prefix + "shape", input.shape.joinToString(","))
            put(prefix + "source", input.source)
            put(prefix + "layout", input.layout)
            put(prefix + "mean", input.mean)
            put(prefix + "std", input.std)
            put(prefix + "values", input.values)
            put(prefix + "sampleRate", input.sampleRate)
        }
        outputs.forEachIndexed { index, output ->
            val prefix = "output.$index."
            put(prefix + "render", output.render)
            put(prefix + "scale", output.scale)
            put(prefix + "offset", output.offset)
            put(prefix + "labels", output.labels.joinToString("\n"))
            put(prefix + "threshold", output.threshold)
            if (output.render == OutputRender.BOXES) {
                put(prefix + "classesOutput", output.classesOutput)
                put(prefix + "scoresOutput", output.scoresOutput)
                put(prefix + "countOutput", output.countOutput)
            }
        }
        return StringWriter().also { properties.store(it, "Model configuration (UTF-8)") }.toString()
    }

    companion object {
        fun infer(graph: GraphSignature): ModelConfig = ModelConfig(
            signature = graph.key,
            inputs = graph.inputs.map { tensor ->
                val shape = tensor.shape.map { it.coerceAtLeast(1) }
                val image = shape.size == 4 && shape[0] == 1 && (shape[3] in listOf(1, 3) || shape[1] in listOf(1, 3))
                TensorInputConfig(
                    shape = shape,
                    source = when {
                        tensor.type == 5 -> InputSource.TEXT
                        image -> InputSource.IMAGE
                        else -> InputSource.NUMBERS
                    },
                    layout = if (image && shape[3] !in listOf(1, 3)) ImageLayout.NCHW else ImageLayout.NHWC,
                    std = if (image && tensor.type == 0) 255f else 1f,
                )
            },
            outputs = graph.outputs.map { tensor ->
                TensorOutputConfig(render = when {
                    tensor.type == 5 -> OutputRender.VALUES
                    tensor.shape == listOf(1, 1, 17, 3) -> OutputRender.MOVENET
                    tensor.shape.size == 2 && tensor.shape[0] == 1 -> OutputRender.CLASSIFICATION
                    else -> OutputRender.VALUES
                })
            },
        )

        fun decode(text: String, description: GraphDescription): ModelConfig {
            require(text.length <= MAX_CONFIG_BYTES) { "配置文件超过 1 MB" }
            val properties = Properties().apply { load(StringReader(text)) }
            val consumed = mutableSetOf<String>()
            fun get(key: String, default: String? = null): String {
                consumed += key
                return properties.getProperty(key) ?: default ?: error("缺少配置项 $key")
            }
            fun number(key: String, default: Float): Float = get(key, default.toString()).toFloat().also {
                require(it.isFinite()) { "$key 必须是有限数值" }
            }
            require(get("version") == "1") { "不支持的配置版本" }
            val signature = get("signature", "")
            val graph = description.signatures.singleOrNull { it.key == signature }
                ?: error("没有签名 '$signature'，可用签名：${description.signatures.map { it.key }}")
            require(graph.inputs.isNotEmpty()) { "此签名没有输入，当前 Interpreter Java API 不支持空输入调用；请选择推理签名" }
            val inputs = graph.inputs.mapIndexed { index, tensor ->
                val prefix = "input.$index."
                val shape = get(prefix + "shape").trim().let { value ->
                    if (value.isEmpty()) emptyList() else value.split(',').map { it.trim().toInt() }
                }
                validateInputShape(shape, tensor)
                TensorInputConfig(
                    shape = shape,
                    source = InputSource.valueOf(get(prefix + "source")),
                    layout = ImageLayout.valueOf(get(prefix + "layout", "NHWC")),
                    mean = number(prefix + "mean", 0f),
                    std = number(prefix + "std", 1f).also { require(it > 0) { "std 必须大于 0" } },
                    values = get(prefix + "values", "0"),
                    sampleRate = get(prefix + "sampleRate", "16000").toInt().also {
                        require(it in 8000..192000) { "sampleRate 必须在 8000–192000 Hz" }
                    },
                ).also { config ->
                    if (config.source == InputSource.IMAGE) imageDimensions(config)
                    if (config.source == InputSource.TEXT) require(tensor.type == 5) {
                        "${tensor.name} 是 ${tensor.typeName}；需要分词的文本模型请提供 token 数值或二进制张量"
                    }
                }
            }
            val outputs = graph.outputs.indices.map { index ->
                val prefix = "output.$index."
                TensorOutputConfig(
                    render = OutputRender.valueOf(get(prefix + "render", "VALUES")),
                    scale = number(prefix + "scale", 1f),
                    offset = number(prefix + "offset", 0f),
                    labels = get(prefix + "labels", "").takeIf { it.isNotEmpty() }?.split('\n') ?: emptyList(),
                    threshold = number(prefix + "threshold", 0.3f).also {
                        require(it in 0f..1f) { "threshold 必须在 0–1" }
                    },
                    classesOutput = get(prefix + "classesOutput", "1").toInt(),
                    scoresOutput = get(prefix + "scoresOutput", "2").toInt(),
                    countOutput = get(prefix + "countOutput", "3").toInt(),
                ).also { config ->
                    if (config.render == OutputRender.BOXES) {
                        require(listOf(config.classesOutput, config.scoresOutput, config.countOutput)
                            .all { it in graph.outputs.indices }) { "检测框关联输出索引越界" }
                    }
                }
            }
            require(inputs.sumOf { tensorElements(it.shape).toLong() } <= MAX_TENSOR_ELEMENTS) { "输入张量合计超过 $MAX_TENSOR_ELEMENTS 个元素" }
            require(properties.stringPropertyNames().all { it in consumed }) {
                "未知配置项：${properties.stringPropertyNames() - consumed}"
            }
            return ModelConfig(signature, inputs, outputs)
        }
    }
}

internal const val MAX_CONFIG_BYTES = 1024 * 1024
internal const val MAX_TENSOR_ELEMENTS = 4 * 1024 * 1024

internal fun tensorElements(shape: List<Int>): Int {
    require(shape.size <= 8) { "运行器最多支持 8 维输入" }
    var count = 1L
    shape.forEach { dimension ->
        require(dimension > 0) { "请把动态输入维度配置为正整数" }
        count *= dimension
        require(count <= MAX_TENSOR_ELEMENTS) { "单个张量最多 $MAX_TENSOR_ELEMENTS 个元素" }
    }
    return count.toInt()
}

internal fun validateInputShape(shape: List<Int>, tensor: GraphTensor) {
    tensorElements(shape)
    require(shape.size == tensor.shape.size) { "${tensor.name} 维数应为 ${tensor.shape.size}" }
    shape.forEachIndexed { index, value ->
        require(tensor.signature.getOrNull(index) == -1 || tensor.shape[index] == value) {
            "${tensor.name} 第 $index 维固定为 ${tensor.shape[index]}，配置不能更改固定维度"
        }
    }
}

internal fun imageDimensions(input: TensorInputConfig): Triple<Int, Int, Int> {
    require(input.shape.size == 4 && input.shape[0] == 1) { "图片输入需要四维、batch=1" }
    val channels = input.shape[if (input.layout == ImageLayout.NHWC) 3 else 1]
    val height = input.shape[if (input.layout == ImageLayout.NHWC) 1 else 2]
    val width = input.shape[if (input.layout == ImageLayout.NHWC) 2 else 3]
    require(channels == 1 || channels == 3) { "图片输入需要 1 或 3 个通道" }
    return Triple(width, height, channels)
}
