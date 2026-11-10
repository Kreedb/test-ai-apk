package com.example.minimalapk.pose

/**
 * The subset of the TFLite FlatBuffer schema needed to check one model's I/O contract and to make
 * its shape signatures static. TFLite operators treat a `-1` in `shape_signature` as "unspecified"
 * even when the concrete `shape` is resolved, which makes the graph dynamic and the GPU delegate
 * reject it; rewriting the signature in place (same rank, so no offsets move) fixes that.
 * Mirrors `tools/staticize_tflite_shapes.py`, which does the same for bundled assets at build time.
 *
 * Every tensor/table handle below is an absolute byte offset into [bytes].
 */
internal class TfliteGraph(val bytes: ByteArray) {
    init {
        require(PoseModelImport.isTflite(bytes)) { "不是 TFLite 文件（缺少 TFL3 标识）" }
        require(field(tableAt(0), 0)?.let(::i32) == 3) { "不支持的 TFLite 文件版本" }
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
    fun elementType(tensor: Int): Int = field(tensor, 1)?.let { bytes[it].toInt() and 0xff } ?: 0

    fun describe(): GraphDescription {
        val graphs = subgraphs()
        require(graphs.isNotEmpty()) { "模型没有子图" }
        fun tensors(graph: Int) = tableVector(graph, 0)
        fun describeTensor(tensor: Int, alias: String? = null): GraphTensor {
            val quantization = field(tensor, 4)?.let(::tableAt)
            val scales = quantization?.let { vector(it, 2) }
            val zeros = quantization?.let { vector(it, 3, 8) }
            return GraphTensor(
                name = alias ?: string(tensor, 3),
                shape = shape(tensor).toList(),
                signature = shapeSignature(tensor)?.toList() ?: shape(tensor).toList(),
                type = elementType(tensor),
                scale = scales?.takeIf { it.second == 1 }?.let { Float.fromBits(i32(it.first)) } ?: 0f,
                zeroPoint = zeros?.takeIf { it.second == 1 }?.let {
                    (i32(it.first).toLong() and 0xffffffffL) or (i32(it.first + 4).toLong() shl 32)
                } ?: 0L,
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
        return GraphDescription(listOf(main) + signatures, customOps)
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
        return List(count) { tableAt(start + it * 4) }
    }

    private fun string(table: Int, index: Int): String {
        val (start, count) = vector(table, index, 1) ?: return ""
        return String(bytes, start, count, Charsets.UTF_8)
    }

    /** Absolute position of a table field, or null when the vtable does not define it. */
    private fun field(table: Int, index: Int): Int? {
        val vtable = (table.toLong() - i32(table)).also {
            require(it in 0L..(bytes.size - 4).toLong()) { "TFLite 表越界或文件被截断" }
        }.toInt()
        checkRange(vtable, u16(vtable))
        val fieldOffset = 4 + index * 2
        if (fieldOffset + 2 > u16(vtable)) return null
        val offset = u16(vtable + fieldOffset)
        return if (offset == 0) null else (table + offset).also { checkRange(it, 1) }
    }

    private fun tableAt(position: Int): Int {
        val offset = u32(position)
        require(offset >= 0 && position <= bytes.size - offset) { "TFLite 偏移越界或文件被截断" }
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
) {
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

internal data class GraphDescription(val signatures: List<GraphSignature>, val customOps: List<String>)
