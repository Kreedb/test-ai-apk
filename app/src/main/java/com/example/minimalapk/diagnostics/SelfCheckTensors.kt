package com.example.minimalapk.diagnostics

import com.example.minimalapk.models.InputSource
import com.example.minimalapk.models.MAX_TENSOR_ELEMENTS
import com.example.minimalapk.models.TensorInputConfig
import com.example.minimalapk.models.decodeTensor
import com.example.minimalapk.models.encodeTensor
import com.example.minimalapk.models.imageDimensions
import com.example.minimalapk.models.imageValues
import com.example.minimalapk.models.numericValues
import com.example.minimalapk.models.tensorByteWidth
import com.example.minimalapk.pose.GraphTensor
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.lang.reflect.Array as ReflectArray
import kotlin.math.PI
import kotlin.math.sin

internal data class CheckDigest(val bytes: Long, val sha256: String)

/** Hash even large installed catalog files without materializing their weights in the Java heap. */
internal fun checkDigest(input: InputStream, checkpoint: () -> Unit = {}): CheckDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        checkpoint()
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) {
            digest.update(buffer, 0, count)
            total += count
        }
    }
    return CheckDigest(total, digest.digest().joinToString("") { "%02x".format(it) })
}

internal fun checkTensorType(type: Int) {
    if (type !in setOf(0, 2, 3, 4, 5, 6, 7, 9)) {
        throw CheckSkipped("当前输入/输出转换器不支持张量类型 $type；需要对应的运行器，未声称推理通过")
    }
}

internal fun checkInputElements(shape: List<Int>): Int {
    if (shape.size > 8 || shape.any { it <= 0 }) {
        throw CheckSkipped("输入形状 $shape 未解析或超过 8 维；请配置正整数动态尺寸")
    }
    var count = 1L
    for (dimension in shape) {
        if (count > MAX_TENSOR_ELEMENTS / dimension.toLong()) {
            throw CheckSkipped("输入 $shape 超过自检 $MAX_TENSOR_ELEMENTS 元素预算；需专用分块/大模型运行器")
        }
        count *= dimension
    }
    return count.toInt()
}

internal fun syntheticPixels(width: Int, height: Int): IntArray {
    checkInputElements(listOf(height, width, 3))
    return IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        (0xff shl 24) or (((x * 17 + y * 3) and 255) shl 16) or
            (((x * 5 + y * 11) and 255) shl 8) or ((x + y * 7) and 255)
    }
}

internal data class SyntheticInput(
    val value: Any,
    val pixels: IntArray? = null,
    val width: Int = 0,
    val height: Int = 0,
)

/** Uses the same image normalization/quantization and numeric conversion as the app runners. */
internal fun syntheticInput(tensor: GraphTensor, input: TensorInputConfig): SyntheticInput {
    checkTensorType(tensor.type)
    val count = checkInputElements(input.shape)
    if (input.source == InputSource.TEXT) {
        require(tensor.type == 5) { "TEXT 输入需要 STRING 张量" }
        return SyntheticInput(syntheticStrings(input.shape))
    }
    if (tensor.type == 5) throw CheckSkipped("STRING 张量需要 TEXT 配置；不能把字符串当数值缓冲区")
    if (input.source == InputSource.BINARY) {
        return SyntheticInput(ByteBuffer.allocateDirect(count * tensorByteWidth(tensor.type))
            .order(ByteOrder.nativeOrder()))
    }
    if (input.source == InputSource.IMAGE) {
        val (width, height, channels) = imageDimensions(input)
        val pixels = syntheticPixels(width, height)
        return SyntheticInput(encodeTensor(imageValues(pixels, channels, input), tensor, input.source),
            pixels, width, height)
    }
    val values = when (input.source) {
        InputSource.AUDIO -> DoubleArray(count) { 0.01 * sin(2 * PI * 440 * it / input.sampleRate) }
        else -> numericValues(input.values, count)
    }
    return SyntheticInput(encodeTensor(values, tensor, input.source))
}

private fun syntheticStrings(shape: List<Int>): Any {
    if (shape.isEmpty()) return "self check"
    val result = ReflectArray.newInstance(String::class.java, *shape.toIntArray())
    fun fill(target: Any, depth: Int) {
        repeat(shape[depth]) { index ->
            if (depth == shape.lastIndex) ReflectArray.set(target, index, "self check")
            else fill(checkNotNull(ReflectArray.get(target, index)), depth + 1)
        }
    }
    fill(result, 0)
    return result
}

internal fun checkOutputShape(expected: GraphTensor, actual: List<Int>): Int {
    require(actual.size <= 8 && actual.all { it >= 0 }) { "${expected.name} 输出形状未解析：$actual" }
    // Empty FlatBuffer output shapes occur in SSD and are not proof of scalar output.
    if (expected.shape.isNotEmpty()) {
        require(actual.size == expected.shape.size) {
            "${expected.name} 输出维数不符：声明 ${expected.shape}，实际 $actual"
        }
        actual.forEachIndexed { index, dimension ->
            if (expected.signature.getOrNull(index) != -1 && expected.shape[index] > 0) {
                require(dimension == expected.shape[index]) {
                    "${expected.name} 输出形状不符：声明 ${expected.shape}，实际 $actual"
                }
            }
        }
    }
    if (0 in actual) return 0
    return checkInputElements(actual)
}

/** Validate every numeric element, not a preview; quantized outputs use runtime scale/zero-point. */
internal fun checkOutputBuffer(
    expected: GraphTensor,
    actualShape: List<Int>,
    buffer: ByteBuffer,
    scale: Float = expected.scale,
    zeroPoint: Long = expected.zeroPoint,
    checkpoint: () -> Unit = {},
): String {
    checkTensorType(expected.type)
    val count = checkOutputShape(expected, actualShape)
    val bytes = buffer.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }
    if (bytes.remaining() > 64 * 1024 * 1024) throw CheckSkipped("输出缓冲区超过自检 64 MiB 预算")
    if (expected.type == 5) {
        require(bytes.remaining() >= 8) { "${expected.name} STRING 输出头被截断" }
        val strings = bytes.getInt(0)
        require(strings == count && (strings.toLong() + 2) * 4 <= bytes.limit()) { "STRING 输出计数/偏移表无效" }
        val headerBytes = (strings + 2) * 4
        require(bytes.getInt(4) == headerBytes && bytes.getInt((strings + 1) * 4) == bytes.limit()) {
            "STRING 输出数据起止偏移无效"
        }
        repeat(strings) { index ->
            if (index % 4096 == 0) checkpoint()
            val start = bytes.getInt((index + 1) * 4)
            val end = bytes.getInt((index + 2) * 4)
            require(start >= headerBytes && end >= start && end <= bytes.limit()) { "STRING 输出偏移越界" }
        }
        return "shape=$actualShape type=STRING elements=$strings offsets=valid bytes=${bytes.limit()}"
    }
    require(bytes.remaining().toLong() == count.toLong() * tensorByteWidth(expected.type)) {
        "${expected.name} 输出字节数 ${bytes.remaining()} 与形状 $actualShape / ${expected.typeName} 不匹配"
    }
    require(scale.isFinite() && scale >= 0) { "输出量化比例无效：$scale" }
    val values = decodeTensor(bytes, expected.type, scale, zeroPoint)
    var min = Double.POSITIVE_INFINITY
    var max = Double.NEGATIVE_INFINITY
    values.forEachIndexed { index, value ->
        if (index % 4096 == 0) checkpoint()
        require(value.isFinite()) { "${expected.name} 输出[$index]=$value（NaN/Infinity）" }
        min = minOf(min, value)
        max = maxOf(max, value)
    }
    return "shape=$actualShape type=${expected.typeName} elements=${values.size} finite=true " +
        "range=${if (values.isEmpty()) "empty" else "$min..$max"} quantization=$scale/$zeroPoint"
}
