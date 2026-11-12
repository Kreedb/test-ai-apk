package com.example.minimalapk.pose

/**
 * The subset of the TFLite FlatBuffer schema needed to check one model's I/O contract and to make
 * its shape signatures static. TFLite operators treat a `-1` in `shape_signature` as "unspecified"
 * even when the concrete `shape` is resolved, which makes the graph dynamic and the GPU delegate
 * reject it; rewriting the signature in place (same rank, so no offsets move) fixes that.
 * Imported models are fixed here; the bundled asset is committed already static.
 *
 * Every tensor/table handle below is an absolute byte offset into [bytes].
 */
internal class TfliteGraph(val bytes: ByteArray) {
    init {
        require(PoseModelImport.isTflite(bytes)) { "不是 TFLite 文件（缺少 TFL3 标识）" }
        require(field(tableAt(0), 0)?.let(::i32) == 3) { "不支持的 TFLite 文件版本" }
        validateStructure()
    }

    fun inputs(): List<Int> = tensorReferences(1)

    fun outputs(): List<Int> = tensorReferences(2)

    fun shape(tensor: Int): IntArray = intVector(tensor, 0) ?: IntArray(0)

    fun shapeSignature(tensor: Int): IntArray? = intVector(tensor, 7)

    /** Test hook: leaves a signature dimension dynamic the way onnx2tf does. */
    internal fun markSignatureDynamic(tensor: Int) {
        val (values, _) = vector(tensor, 7) ?: return
        writeI32(values, -1)
    }

    /** TFLite's element type; the FlatBuffer omits the FLOAT32 default, so absent means 0. */
    fun elementType(tensor: Int): Int = field(tensor, 1, 1)?.let { bytes[it].toInt() and 0xff } ?: 0

    fun describe(): GraphDescription {
        val graphs = subgraphs()
        require(graphs.isNotEmpty()) { "模型没有子图" }
        fun tensors(graph: Int) = tableVector(graph, 0)
        fun describeTensor(tensor: Int, alias: String? = null): GraphTensor {
            val quantization = field(tensor, 4)?.let(::tableAt)
            val scales = quantization?.let { vector(it, 2) }?.let { (start, count) ->
                List(count) { Float.fromBits(i32(start + it * 4)) }
            }.orEmpty()
            val zeros = quantization?.let { vector(it, 3, 8) }?.let { (start, count) ->
                List(count) { i64(start + it * 8) }
            }.orEmpty()
            return GraphTensor(
                name = alias ?: string(tensor, 3),
                shape = shape(tensor).toList(),
                signature = shapeSignature(tensor)?.toList() ?: shape(tensor).toList(),
                type = elementType(tensor),
                scale = scales.singleOrNull() ?: if (scales.isEmpty()) 0f else Float.NaN,
                zeroPoint = zeros.singleOrNull() ?: 0L,
                scales = scales,
                zeroPoints = zeros,
                quantizedDimension = quantization?.let { field(it, 6)?.let(::i32) } ?: 0,
                quantizationDetailsType = quantization?.let { field(it, 4, 1)?.let { position -> bytes[position].toInt() and 0xff } } ?: 0,
            )
        }
        fun mapped(graph: Int, fieldIndex: Int): List<GraphTensor> {
            val tables = tensors(graph)
            return (intVector(graph, fieldIndex) ?: IntArray(0)).map { index ->
                require(index in tables.indices) { "TFLite 张量索引越界" }
                describeTensor(tables[index])
            }
        }
        val main = GraphSignature("", 0, mapped(graphs[0], 1), mapped(graphs[0], 2))
        val signatures = tableVector(tableAt(0), 7).map { signature ->
            val index = field(signature, 4)?.let(::i32) ?: 0
            require(index in graphs.indices) { "TFLite 签名子图索引越界" }
            val tables = tensors(graphs[index])
            fun named(fieldIndex: Int) = tableVector(signature, fieldIndex).map { mapping ->
                val tensorIndex = field(mapping, 1)?.let(::i32) ?: 0
                require(tensorIndex in tables.indices) { "TFLite 签名张量索引越界" }
                describeTensor(tables[tensorIndex], string(mapping, 0))
            }
            GraphSignature(string(signature, 2), index, named(0), named(1))
        }
        val customOps = tableVector(tableAt(0), 1).mapNotNull { code ->
            string(code, 1).takeIf { it.isNotBlank() }
        }.distinct()
        val operators = tableVector(tableAt(0), 1).map { code ->
            val custom = string(code, 1)
            val builtin = field(code, 3)?.let(::i32) ?: field(code, 0, 1)?.let { bytes[it].toInt() and 0xff } ?: 0
            "${if (custom.isNotEmpty()) "CUSTOM:$custom" else "BUILTIN:$builtin"} v${field(code, 2)?.let(::i32) ?: 1}"
        }
        return GraphDescription(listOf(main) + signatures, customOps, operators)
    }

    /** Validate storage references, not operator availability or a second implementation of every
     * options union. Unknown/custom ops remain importable; their native support is checked at run. */
    private fun validateStructure() {
        val root = tableAt(0)
        val graphs = tableVector(root, 2)
        require(graphs.isNotEmpty()) { "模型没有子图" }
        val buffers = tableVector(root, 4)
        require(buffers.isNotEmpty()) { "模型没有 buffer 表" }
        val codes = tableVector(root, 1)
        fun bufferIndex(index: Int) { require(index in buffers.indices) { "TFLite buffer 索引越界" } }
        fun externalRange(table: Int, offsetField: Int, sizeField: Int) {
            val offset = field(table, offsetField, 8)?.let(::i64) ?: 0L
            val size = field(table, sizeField, 8)?.let(::i64) ?: 0L
            require(offset >= 0 && size >= 0 && (offset <= 1L && size == 0L ||
                offset > 1L && offset <= bytes.size.toLong() && size <= bytes.size.toLong() - offset)) {
                "TFLite 外部 buffer/options 越界或文件被截断"
            }
        }
        buffers.forEach { buffer -> vector(buffer, 0, 1); externalRange(buffer, 1, 2) }
        codes.forEach { code ->
            field(code, 0, 1); field(code, 2); field(code, 3); string(code, 1)
        }
        string(root, 3)
        intVector(root, 5)?.forEach(::bufferIndex)
        tableVector(root, 6).forEach { metadata ->
            string(metadata, 0)
            bufferIndex(field(metadata, 1)?.let(::i32) ?: 0)
        }
        graphs.forEach { graph ->
            string(graph, 4)
            val tensors = tableVector(graph, 0)
            fun references(table: Int, index: Int, optional: Boolean = false): List<Int> =
                (intVector(table, index) ?: IntArray(0)).toList().also { refs ->
                    require(refs.all { it in tensors.indices || optional && it == -1 }) { "TFLite 张量引用越界" }
                }
            references(graph, 1); references(graph, 2)
            tensors.forEach { tensor ->
                shape(tensor); shapeSignature(tensor); elementType(tensor); string(tensor, 3)
                val bufferId = field(tensor, 2)?.let(::i32) ?: 0
                bufferIndex(bufferId)
                val payload = vector(buffers[bufferId], 0, 1)?.second?.toLong()
                    ?.takeIf { it > 0 } ?: field(buffers[bufferId], 2, 8)?.let(::i64) ?: 0L
                val width = when (elementType(tensor)) {
                    0, 2, 15 -> 4; 1, 7, 16 -> 2; 3, 6, 9 -> 1; 4, 10, 12 -> 8; else -> null
                }
                // Sparse/STRING/packed/unknown encodings have their own payload contracts.
                // For ordinary dense constants, a valid vector pointing at too few bytes is still
                // a truncated model, even though its vector header itself fits in the file.
                if (payload > 0 && width != null && field(tensor, 6) == null && shape(tensor).all { it >= 0 }) {
                    var required = width.toLong()
                    shape(tensor).forEach { size ->
                        require(size == 0 || required <= bytes.size.toLong() / size) { "TFLite 常量张量超过文件范围" }
                        required *= size
                    }
                    require(payload >= required) { "TFLite 常量 buffer 被截断" }
                }
                field(tensor, 4)?.let(::tableAt)?.let { quantization ->
                    (0..2).forEach { vector(quantization, it) }
                    vector(quantization, 3, 8)
                    field(quantization, 4, 1)
                    field(quantization, 5)?.let(::tableAt)?.let(::checkTable)
                    field(quantization, 6)
                }
                field(tensor, 6)?.let(::tableAt)?.let(::checkTable)
                tableVector(tensor, 9)
            }
            tableVector(graph, 3).forEach { operator ->
                require((field(operator, 0)?.let(::i32) ?: 0) in codes.indices) { "TFLite 算子代码索引越界" }
                references(operator, 1, optional = true)
                references(operator, 2, optional = true)
                references(operator, 8, optional = true)
                vector(operator, 5, 1); vector(operator, 7, 1)
                externalRange(operator, 9, 10)
                field(operator, 12)?.let(::tableAt)?.let(::checkTable)
                val options = field(operator, 4)?.let(::tableAt)
                options?.let(::checkTable)
                val type = field(operator, 3, 1)?.let { bytes[it].toInt() and 0xff } ?: 0
                // IF/WHILE/CALL_ONCE/CALL contain subgraph indices, not kernel semantics.
                val graphFields = when (type) { 92, 93 -> 0..1; 16, 103 -> 0..0; else -> IntRange.EMPTY }
                if (!graphFields.isEmpty()) {
                    require(options != null) { "TFLite 控制流缺少子图配置" }
                    graphFields.forEach { index ->
                        require((field(options, index)?.let(::i32) ?: 0) in graphs.indices) { "TFLite 控制流子图索引越界" }
                    }
                }
            }
        }
        val keys = mutableSetOf<String>()
        tableVector(root, 7).forEach { signature ->
            val key = string(signature, 2)
            require(key.isNotBlank() && keys.add(key)) { "TFLite 签名名称为空或重复" }
            val graphIndex = field(signature, 4)?.let(::i32) ?: 0
            require(graphIndex in graphs.indices) { "TFLite 签名子图索引越界" }
            val tensors = tableVector(graphs[graphIndex], 0)
            for (index in 0..1) {
                val names = mutableSetOf<String>()
                tableVector(signature, index).forEach { mapping ->
                    require(names.add(string(mapping, 0)) && string(mapping, 0).isNotBlank()) { "TFLite 签名张量名称为空或重复" }
                    require((field(mapping, 1)?.let(::i32) ?: 0) in tensors.indices) { "TFLite 签名张量索引越界" }
                }
            }
        }
    }

    fun dynamicSignatureCount(): Int = tensorTables().count { hasDynamicSignature(it) }

    /** Rewrites every `-1` signature from the concrete shape; returns the patched tensor count. */
    fun staticize(): Int {
        var patched = 0
        for (tensor in tensorTables()) {
            val (values, count) = vector(tensor, 7) ?: continue
            if (!hasDynamicSignature(tensor)) continue
            val shape = shape(tensor)
            // A dimension with no concrete value cannot be made static by a signature rewrite;
            // the caller reports this and asks for a re-export with fixed input shapes.
            require(count == shape.size && -1 !in shape) {
                "模型含未解析的动态维度，请用固定输入尺寸重新导出"
            }
            for (index in 0 until count) {
                writeI32(values + index * 4, shape[index])
            }
            patched++
        }
        return patched
    }

    private fun hasDynamicSignature(tensor: Int): Boolean {
        val (values, count) = vector(tensor, 7) ?: return false
        for (index in 0 until count) {
            if (i32(values + index * 4) == -1) return true
        }
        return false
    }

    /** Every subgraph's tensors: a control-flow model can keep dynamic signatures past the first. */
    private fun tensorTables(): List<Int> = subgraphs().flatMap { subgraph ->
        val (tensors, count) = vector(subgraph, 0) ?: return@flatMap emptyList()
        (0 until count).map { tableAt(tensors + it * 4) }
    }

    private fun tensorReferences(fieldIndex: Int): List<Int> {
        val subgraph = firstSubgraph()
        val (tensors, tensorCount) = vector(subgraph, 0) ?: error("模型没有张量")
        val (indices, count) = vector(subgraph, fieldIndex) ?: return emptyList()
        return (0 until count).map {
            val index = i32(indices + it * 4)
            require(index in 0 until tensorCount) { "TFLite 张量索引越界" }
            tableAt(tensors + index * 4)
        }
    }

    private fun firstSubgraph(): Int = subgraphs().firstOrNull() ?: error("模型没有子图")

    private fun subgraphs(): List<Int> {
        val (start, count) = vector(tableAt(0), 2) ?: error("模型没有子图")
        return (0 until count).map { tableAt(start + it * 4) }
    }

    private fun intVector(table: Int, index: Int): IntArray? {
        val (start, count) = vector(table, index) ?: return null
        return IntArray(count) { i32(start + it * 4) }
    }

    /** (element data position, element count) of a vector field. */
    private fun vector(table: Int, index: Int, width: Int = 4): Pair<Int, Int>? {
        val position = field(table, index) ?: return null
        val vector = tableAt(position)
        val count = u32(vector)
        require(count >= 0 && count <= (bytes.size - vector - 4) / width) { "TFLite 向量越界或文件被截断" }
        return vector + 4 to count
    }

    private fun tableVector(table: Int, index: Int): List<Int> {
        val (start, count) = vector(table, index) ?: return emptyList()
        return List(count) { tableAt(start + it * 4).also(::checkTable) }
    }

    private fun string(table: Int, index: Int): String {
        val (start, count) = vector(table, index, 1) ?: return ""
        checkRange(start, count + 1)
        require(bytes[start + count] == 0.toByte()) { "TFLite 字符串被截断" }
        return String(bytes, start, count, Charsets.UTF_8)
    }

    /** Absolute position of a table field, or null when the vtable does not define it. */
    private fun checkTable(table: Int): Int {
        val vtable = (table.toLong() - i32(table)).also {
            require(it in 0L..(bytes.size - 4).toLong()) { "TFLite 表越界或文件被截断" }
        }.toInt()
        val size = u16(vtable)
        require(size >= 4 && size % 2 == 0 && u16(vtable + 2) >= 4) { "TFLite 表长度无效" }
        checkRange(vtable, size)
        checkRange(table, u16(vtable + 2))
        return vtable
    }

    private fun field(table: Int, index: Int, width: Int = 4): Int? {
        val vtable = checkTable(table)
        val fieldOffset = 4 + index * 2
        if (fieldOffset + 2 > u16(vtable)) return null
        val offset = u16(vtable + fieldOffset)
        if (offset == 0) return null
        require(offset >= 4 && offset + width <= u16(vtable + 2)) { "TFLite 字段越界" }
        return (table + offset).also { checkRange(it, width) }
    }

    private fun tableAt(position: Int): Int {
        val offset = u32(position)
        require(offset > 0 && position <= bytes.size - offset) { "TFLite 偏移越界或文件被截断" }
        return (position + offset).also { checkRange(it, 4) }
    }

    private fun checkRange(position: Int, length: Int) {
        require(position >= 0 && length >= 0 && position <= bytes.size - length) {
            "TFLite 数据越界或文件被截断"
        }
    }

    private fun u16(position: Int): Int {
        checkRange(position, 2)
        return (bytes[position].toInt() and 0xff) or ((bytes[position + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(position: Int): Int {
        checkRange(position, 4)
        return (bytes[position].toInt() and 0xff) or
            ((bytes[position + 1].toInt() and 0xff) shl 8) or
            ((bytes[position + 2].toInt() and 0xff) shl 16) or
            ((bytes[position + 3].toInt() and 0xff) shl 24)
    }

    private fun i32(position: Int): Int = u32(position)

    private fun i64(position: Int): Long =
        (i32(position).toLong() and 0xffffffffL) or (i32(position + 4).toLong() shl 32)

    private fun writeI32(position: Int, value: Int) {
        checkRange(position, 4)
        bytes[position] = value.toByte()
        bytes[position + 1] = (value shr 8).toByte()
        bytes[position + 2] = (value shr 16).toByte()
        bytes[position + 3] = (value shr 24).toByte()
    }
}

internal data class GraphTensor(
    val name: String,
    val shape: List<Int>,
    val signature: List<Int>,
    val type: Int,
    val scale: Float = 0f,
    val zeroPoint: Long = 0,
    val scales: List<Float> = if (scale != 0f) listOf(scale) else emptyList(),
    val zeroPoints: List<Long> = if (scale != 0f) listOf(zeroPoint) else emptyList(),
    val quantizedDimension: Int = 0,
    val quantizationDetailsType: Int = 0,
) {
    val quantizationSummary: String get() = "details_type=$quantizationDetailsType scale=$scales zero-point=$zeroPoints quantized_dimension=$quantizedDimension" +
        if (quantizationDetailsType == 0) "" else "（details 非 NONE，scale/zero-point 按模型 schema 忽略）"
    val quantizationProblem: String? get() = when {
        quantizationDetailsType != 0 -> "$name 使用非仿射量化 details_type=$quantizationDetailsType；当前通用转换器不支持该量化方案，不能使用其 scale/zero-point，请使用兼容运行时；原模型可保留"
        scales.size > 1 || zeroPoints.size > 1 -> "$name 使用逐轴量化（$quantizationSummary），当前通用转换器仅支持逐张量量化；请使用兼容运行时或重新导出，原模型可保留"
        scales.size != zeroPoints.size || scales.any { !it.isFinite() || it <= 0f } -> "$name 的量化 scale/zero-point 无效；请检查模型导出"
        else -> null
    }
    val typeName: String get() = when (type) {
        0 -> "FLOAT32"
        1 -> "FLOAT16"
        2 -> "INT32"
        3 -> "UINT8"
        4 -> "INT64"
        5 -> "STRING"
        6 -> "BOOL"
        7 -> "INT16"
        9 -> "INT8"
        else -> "TYPE_$type"
    }
}

internal data class GraphSignature(
    val key: String,
    val subgraph: Int,
    val inputs: List<GraphTensor>,
    val outputs: List<GraphTensor>,
)

internal data class GraphDescription(val signatures: List<GraphSignature>, val customOps: List<String>, val operators: List<String> = emptyList())

/** Compact file-backed facts only: tensor names, shapes/signatures, dtype and quantization.
 * Normalization, box meaning, labels, tokenizer and sample rate are deliberately not inferred here.
 */
internal fun GraphDescription.knownSummary(): String = buildString {
    append("签名 ")
    append(signatures.joinToString { it.key.ifEmpty { "主子图" } })
    signatures.forEach { signature ->
        append("；${signature.key.ifEmpty { "主子图" }} 输入 ")
        append(signature.inputs.joinToString { tensor ->
            "${tensor.name} ${tensor.typeName} 形状${tensor.shape} 签名${tensor.signature} " +
                "量化 ${tensor.quantizationSummary}"
        }.ifEmpty { "无" })
        append(" 输出 ")
        append(signature.outputs.joinToString { tensor ->
            "${tensor.name} ${tensor.typeName} 形状${tensor.shape} 签名${tensor.signature} 量化 ${tensor.quantizationSummary}"
        }.ifEmpty { "无" })
    }
    if (customOps.isNotEmpty()) append("；自定义算子 ${customOps.joinToString()}")
    if (operators.isNotEmpty()) append("；算子代码/版本 ${operators.joinToString()}")
}
