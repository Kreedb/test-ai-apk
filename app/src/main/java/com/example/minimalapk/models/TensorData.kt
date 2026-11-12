package com.example.minimalapk.models

import com.example.minimalapk.pose.GraphTensor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round

internal fun imageValues(pixels: IntArray, channels: Int, config: TensorInputConfig): DoubleArray {
    val values = DoubleArray(pixels.size * channels)
    pixels.forEachIndexed { index, pixel ->
        val rgb = intArrayOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255)
        repeat(channels) { channel ->
            val value = if (channels == 1) rgb[0] * .299 + rgb[1] * .587 + rgb[2] * .114 else rgb[channel].toDouble()
            val offset = if (config.layout == ImageLayout.NHWC) index * channels + channel else channel * pixels.size + index
            values[offset] = (value - config.mean) / config.std
        }
    }
    return values
}

internal fun numericValues(text: String, elements: Int): DoubleArray {
    // shortcut: text numbers use double precision, use BINARY for exact integers above 2^53.
    val tokens = text.trim().split(Regex("[\\s,]+"))
    require(tokens.size == 1 || tokens.size == elements) { "需要 1 个填充值或 $elements 个数值，实际 ${tokens.size}" }
    val parsed = tokens.map { token -> token.toDouble().also { require(it.isFinite()) { "输入不能包含 NaN/Infinity" } } }
    return DoubleArray(elements) { parsed[if (parsed.size == 1) 0 else it] }
}

internal fun tensorByteWidth(type: Int): Int = when (type) {
    0, 2 -> 4
    3, 6, 9 -> 1
    4 -> 8
    7 -> 2
    else -> error("运行器暂不支持类型 $type 的数值转换，可保留模型并提供对应运行时")
}

/** Values are real numbers; quantization comes from the model, never a guessed dtype. */
internal fun encodeTensor(values: DoubleArray, tensor: GraphTensor, source: InputSource = InputSource.NUMBERS): ByteBuffer {
    tensor.quantizationProblem?.let { error(it) }
    val buffer = ByteBuffer.allocateDirect(values.size * tensorByteWidth(tensor.type)).order(ByteOrder.nativeOrder())
    fun integer(value: Double, min: Long, max: Long): Long {
        val quantized = tensor.scale > 0f
        val raw = if (quantized) round(value / tensor.scale + tensor.zeroPoint) else value
        require(raw.isFinite()) { "${tensor.name} 输入不是有限数值" }
        if (quantized) return raw.coerceIn(min.toDouble(), max.toDouble()).toLong()
        if (source == InputSource.IMAGE) return round(raw).coerceIn(min.toDouble(), max.toDouble()).toLong()
        require(raw == round(raw) && raw >= min.toDouble() &&
            (if (max == Long.MAX_VALUE) raw < 9223372036854775808.0 else raw <= max.toDouble())) {
            "${tensor.name} 的 ${tensor.typeName} 输入越界或包含小数"
        }
        return raw.toLong()
    }
    values.forEach { value ->
        require(value.isFinite()) { "输入不是有限数值" }
        when (tensor.type) {
            0 -> buffer.putFloat(value.toFloat().also { require(it.isFinite()) { "FLOAT32 输入溢出" } })
            2 -> buffer.putInt(integer(value, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            3 -> buffer.put(integer(value, 0, 255).toByte())
            4 -> buffer.putLong(integer(value, Long.MIN_VALUE, Long.MAX_VALUE))
            6 -> { require(value == 0.0 || value == 1.0) { "BOOL 输入必须为 0 或 1" }; buffer.put(value.toInt().toByte()) }
            7 -> buffer.putShort(integer(value, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort())
            9 -> buffer.put(integer(value, -128, 127).toByte())
        }
    }
    return buffer.apply { rewind() }
}

internal fun decodeTensor(buffer: ByteBuffer, type: Int, scale: Float, zeroPoint: Long): DoubleArray {
    val bytes = buffer.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }
    val width = tensorByteWidth(type)
    require(bytes.remaining() % width == 0 && bytes.remaining() / width <= MAX_TENSOR_ELEMENTS) { "输出张量超过上限或长度不符" }
    return DoubleArray(bytes.remaining() / width) {
        val raw = when (type) {
            0 -> bytes.float.toDouble()
            2 -> bytes.int.toDouble()
            3 -> (bytes.get().toInt() and 255).toDouble()
            4 -> bytes.long.toDouble()
            6 -> if (bytes.get().toInt() == 0) 0.0 else 1.0
            7 -> bytes.short.toDouble()
            9 -> bytes.get().toDouble()
            else -> error("不支持的输出类型 $type")
        }
        if (scale > 0f) (raw - zeroPoint) * scale else raw
    }
}

/** PCM16 WAV avoids codec/permission dependencies; audio input is resampled and padded locally. */
internal fun wavSamples(bytes: ByteArray, sampleRate: Int, count: Int): DoubleArray {
    require(sampleRate in 8000..192000 && count in 1..MAX_TENSOR_ELEMENTS) { "WAV 目标采样率或窗口长度无效" }
    require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "请选择 PCM16 WAV 音频" }
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val riffEnd = 8L + (data.getInt(4).toLong() and 0xffffffffL)
    require(riffEnd == bytes.size.toLong()) { "WAV RIFF 长度不符或文件被截断" }
    var rate = 0
    var channels = 0
    var start = -1
    var length = 0
    var offset = 12
    while (offset <= bytes.size - 8) {
        val size = data.getInt(offset + 4)
        require(size >= 0 && size <= bytes.size - offset - 8) { "WAV 文件被截断" }
        when (String(bytes, offset, 4, Charsets.US_ASCII)) {
            "fmt " -> {
                require(rate == 0) { "WAV 包含重复 fmt 块" }
                require(size >= 16 && data.getShort(offset + 8).toInt() == 1 && data.getShort(offset + 22).toInt() == 16) {
                    "需要未压缩的 16-bit PCM WAV"
                }
                channels = data.getShort(offset + 10).toInt()
                rate = data.getInt(offset + 12)
                require(channels in 1..2 && rate in 8000..192000 && data.getShort(offset + 20).toInt() == channels * 2) {
                    "WAV 需要单声道或双声道、8000–192000 Hz"
                }
                require(data.getInt(offset + 16) == rate * channels * 2) { "WAV byteRate 与 PCM 格式不符" }
            }
            "data" -> {
                require(start < 0) { "WAV 包含多个 data 块，当前仅支持单个 PCM 数据块" }
                start = offset + 8; length = size
            }
        }
        require(size % 2 == 0 || offset + 8L + size < riffEnd) { "WAV 缺少奇数块的填充字节" }
        offset += 8 + size + size % 2
    }
    require(offset == bytes.size) { "WAV 尾部块被截断" }
    require(rate > 0 && channels > 0 && start >= 0 && length >= channels * 2 && length % (channels * 2) == 0) { "WAV 缺少有效音频数据" }
    val frames = length / (channels * 2)
    fun at(frame: Int): Double = (0 until channels).sumOf { channel ->
        data.getShort(start + (frame * channels + channel) * 2) / 32768.0
    } / channels
    return DoubleArray(count) { index ->
        val position = index.toDouble() * rate / sampleRate
        val left = position.toInt()
        if (left >= frames) 0.0 else {
            val fraction = position - left
            at(left) * (1 - fraction) + at(minOf(left + 1, frames - 1)) * fraction
        }
    }
}
