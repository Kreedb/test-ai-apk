package com.example.minimalapk.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.scale
import com.example.minimalapk.examples.decodeImage
import com.example.minimalapk.pose.GraphTensor
import com.example.minimalapk.pose.ModelDownload
import com.example.minimalapk.pose.PoseNet
import com.example.minimalapk.pose.readCapped
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.lang.reflect.Array as ReflectArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal data class ModelRunResult(val text: String, val images: List<Pair<String, Bitmap>>)

internal object ConfiguredModelRunner {
    fun run(context: Context, model: ConfiguredModel, config: ModelConfig, files: Map<Int, Uri>): ModelRunResult {
        val description = ConfiguredModelStore.describe(model)
        val graph = description.signatures.single { it.key == config.signature }
        val images = mutableMapOf<Int, Bitmap>()
        val inputBuffers = graph.inputs.mapIndexed { index, tensor ->
            val input = config.inputs[index]
            val count = tensorElements(input.shape)
            fun readFile(): ByteArray = context.contentResolver.openInputStream(
                files[index] ?: error("请为输入 $index（${tensor.name}）选择文件"),
            )?.use { it.readCapped(ModelDownload.MAX_BYTES) } ?: error("无法读取输入 $index")
            when (input.source) {
                InputSource.TEXT -> stringArray(input.values, input.shape)
                InputSource.BINARY -> {
                    val bytes = readFile()
                    require(bytes.size == count * tensorByteWidth(tensor.type)) { "输入 $index 二进制长度应为 ${count * tensorByteWidth(tensor.type)} 字节" }
                    ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
                }
                else -> {
                    val values = when (input.source) {
                        InputSource.IMAGE -> {
                            val (width, height, channels) = imageDimensions(input)
                            val bitmap = decodeImage(context, files[index] ?: error("请为输入 $index 选择图片")) ?: error("无法读取图片")
                            val resized = bitmap.scale(width, height)
                            images[index] = resized
                            val pixels = IntArray(width * height)
                            resized.getPixels(pixels, 0, width, 0, 0, width, height)
                            imageValues(pixels, channels, input)
                        }
                        InputSource.AUDIO -> wavSamples(readFile(), input.sampleRate, count)
                        else -> numericValues(input.values, count)
                    }
                    encodeTensor(values, tensor, input.source)
                }
            }
        }
        Interpreter(model.file, Interpreter.Options().setNumThreads(2)).use { interpreter ->
            if (config.signature.isEmpty()) {
                config.inputs.forEachIndexed { index, input -> interpreter.resizeInput(index, input.shape.toIntArray(), true) }
                interpreter.runForMultipleInputsOutputs(inputBuffers.toTypedArray(), emptyMap())
            } else {
                val named = graph.inputs.mapIndexed { index, tensor ->
                    val input = config.inputs[index]
                    val data = inputBuffers[index]
                    // Signature inputs resize from Java arrays; buffers alone retain the old shape.
                    val value = if (data is ByteBuffer && input.shape != tensor.shape) {
                        shapedArray(data, tensor.type, input.shape)
                    } else data
                    tensor.name to value
                }.toMap()
                interpreter.runSignature(named, emptyMap(), config.signature)
            }
            val outputs = graph.outputs.mapIndexed { index, tensor ->
                val actual = if (config.signature.isEmpty()) interpreter.getOutputTensor(index)
                    else interpreter.getOutputTensorFromSignature(tensor.name, config.signature)
                tensorOutput(actual, tensor)
            }
            val rendered = mutableListOf<Pair<String, Bitmap>>()
            val report = outputs.mapIndexed { index, output ->
                val options = config.outputs[index]
                val header = "输出 $index · ${graph.outputs[index].name} ${output.shape}"
                val values = output.values
                when (options.render) {
                    OutputRender.IMAGE, OutputRender.SEGMENTATION -> {
                        rendered += header to outputBitmap(output.shape, values, options)
                        header
                    }
                    OutputRender.MOVENET -> {
                        require(output.shape == listOf(1, 1, 17, 3)) { "MoveNet 输出需要 [1,1,17,3]" }
                        rendered += header to drawMoveNet(images.values.firstOrNull() ?: error("MoveNet 需要图片输入"), values, options.threshold)
                        "$header\n平均关键点置信度：${(0 until 17).sumOf { values[it * 3 + 2] } / 17}"
                    }
                    OutputRender.BOXES -> {
                        val source = images.values.firstOrNull() ?: error("检测框需要图片输入")
                        rendered += header to drawBoxes(source, values, outputs, options)
                        header
                    }
                    OutputRender.CLASSIFICATION -> "$header\n" + values.indices.sortedByDescending { values[it] }.take(5).joinToString("\n") {
                        "${options.labels.getOrNull(it) ?: "#$it"}：${values[it]}"
                    }
                    else -> "$header\n${output.text ?: values.take(64).joinToString()}${if (values.size > 64) " …（共 ${values.size} 个）" else ""}"
                }
            }.joinToString("\n\n")
            return ModelRunResult(report, rendered)
        }
    }
}

private data class TensorOutput(val shape: List<Int>, val values: DoubleArray, val text: String? = null)

private fun tensorOutput(tensor: Tensor, description: GraphTensor): TensorOutput {
    val buffer = tensor.asReadOnlyBuffer()
    require(buffer.remaining() <= ModelDownload.MAX_BYTES) { "输出张量超过 64 MB" }
    if (description.type == 5) {
        val bytes = buffer.duplicate().order(ByteOrder.nativeOrder())
        require(bytes.remaining() >= 8) { "STRING 输出无效" }
        val count = bytes.getInt(0)
        require(count in 0..MAX_TENSOR_ELEMENTS && (count.toLong() + 2) * 4 <= bytes.limit()) { "STRING 输出偏移无效" }
        val text = (0 until minOf(count, 64)).joinToString("\n") { index ->
            val start = bytes.getInt((index + 1) * 4)
            val end = bytes.getInt((index + 2) * 4)
            require(start >= (count + 2) * 4 && end >= start && end <= bytes.limit()) { "STRING 输出越界" }
            val data = ByteArray(minOf(end - start, 4096))
            bytes.position(start); bytes.get(data)
            data.toString(Charsets.UTF_8)
        }
        return TensorOutput(tensor.shape().toList(), doubleArrayOf(), text)
    }
    val quantization = tensor.quantizationParams()
    return TensorOutput(tensor.shape().toList(), decodeTensor(buffer, description.type, quantization.scale, quantization.zeroPoint.toLong()))
}

internal fun shapedArray(buffer: ByteBuffer, type: Int, shape: List<Int>): Any {
    val component = when (type) {
        0 -> Float::class.javaPrimitiveType!!
        2 -> Int::class.javaPrimitiveType!!
        3, 9 -> Byte::class.javaPrimitiveType!!
        4 -> Long::class.javaPrimitiveType!!
        6 -> Boolean::class.javaPrimitiveType!!
        7 -> Short::class.javaPrimitiveType!!
        else -> error("签名不支持此输入类型 $type")
    }
    val bytes = buffer.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }
    fun value(): Any = when (type) {
        0 -> bytes.float
        2 -> bytes.int
        3, 9 -> bytes.get()
        4 -> bytes.long
        6 -> bytes.get().toInt() != 0
        else -> bytes.short
    }
    if (shape.isEmpty()) return value()
    val array = ReflectArray.newInstance(component, *shape.toIntArray())
    fun fill(target: Any, depth: Int) {
        repeat(shape[depth]) { index ->
            if (depth == shape.lastIndex) ReflectArray.set(target, index, value())
            else fill(checkNotNull(ReflectArray.get(target, index)), depth + 1)
        }
    }
    fill(array, 0)
    return array
}

private fun stringArray(text: String, shape: List<Int>): Any {
    if (shape.isEmpty()) return text
    val values = text.split('\n')
    require(values.size == 1 || values.size == tensorElements(shape)) { "TEXT 按行填写，每个张量元素一行，或一行填充全部" }
    val array = ReflectArray.newInstance(String::class.java, *shape.toIntArray())
    var position = 0
    fun fill(target: Any, depth: Int) {
        repeat(shape[depth]) { index ->
            if (depth == shape.lastIndex) ReflectArray.set(target, index, values[if (values.size == 1) 0 else position++])
            else fill(checkNotNull(ReflectArray.get(target, index)), depth + 1)
        }
    }
    fill(array, 0)
    return array
}

private fun outputBitmap(shape: List<Int>, values: DoubleArray, options: TensorOutputConfig): Bitmap {
    require(shape.size == 4 && shape[0] == 1 && shape.all { it > 0 }) { "图片/分割输出需要 [1,H,W,C]" }
    val height = shape[1]
    val width = shape[2]
    val channels = shape[3]
    require(tensorElements(shape) == values.size) { "输出尺寸不匹配" }
    val pixels = IntArray(width * height) { index ->
        if (options.render == OutputRender.SEGMENTATION) {
            val label = (0 until channels).maxBy { values[index * channels + it] }
            if (label == 0) 0xff000000.toInt() else 0xff000000.toInt() or ((label * 0x3779b9) and 0xffffff)
        } else {
            require(channels == 1 || channels == 3) { "图片输出需要 1 或 3 个通道" }
            fun channel(c: Int): Int {
                val value = values[index * channels + minOf(c, channels - 1)] * options.scale + options.offset
                require(value.isFinite()) { "图片输出包含 NaN/Infinity" }
                return value.coerceIn(0.0, 255.0).roundToInt()
            }
            0xff000000.toInt() or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun drawMoveNet(source: Bitmap, values: DoubleArray, threshold: Float): Bitmap {
    require(values.size == 51 && values.all { it.isFinite() }) { "MoveNet 关键点无效" }
    val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff00ff00.toInt(); strokeWidth = maxOf(1f, bitmap.width / 100f) }
    fun x(index: Int) = (values[index * 3 + 1] * bitmap.width).toFloat()
    fun y(index: Int) = (values[index * 3] * bitmap.height).toFloat()
    PoseNet.skeletonEdges.forEach { (a, b) ->
        if (values[a * 3 + 2] >= threshold && values[b * 3 + 2] >= threshold) canvas.drawLine(x(a), y(a), x(b), y(b), paint)
    }
    repeat(17) { index ->
        if (values[index * 3 + 2] >= threshold) canvas.drawCircle(x(index), y(index), paint.strokeWidth * 1.5f, paint)
    }
    return bitmap
}

private fun drawBoxes(source: Bitmap, boxes: DoubleArray, outputs: List<TensorOutput>, options: TensorOutputConfig): Bitmap {
    val classes = outputs[options.classesOutput].values
    val scores = outputs[options.scoresOutput].values
    val count = outputs[options.countOutput].values.firstOrNull()?.toInt() ?: 0
    require(count >= 0 && count <= boxes.size / 4 && count <= classes.size && count <= scores.size) { "检测框输出数量不匹配" }
    val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff00ff00.toInt(); strokeWidth = maxOf(1f, bitmap.width / 100f); textSize = bitmap.width / 20f }
    repeat(count) { index ->
        if (scores[index] < options.threshold) return@repeat
        val offset = index * 4
        require((0..3).all { boxes[offset + it].isFinite() }) { "检测框包含无效坐标" }
        val rect = RectF((boxes[offset + 1] * bitmap.width).toFloat(), (boxes[offset] * bitmap.height).toFloat(),
            (boxes[offset + 3] * bitmap.width).toFloat(), (boxes[offset + 2] * bitmap.height).toFloat())
        paint.style = Paint.Style.STROKE
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.FILL
        canvas.drawText(options.labels.getOrNull(classes[index].toInt()) ?: "#${classes[index].toInt()}", rect.left, maxOf(paint.textSize, rect.top), paint)
    }
    return bitmap
}
