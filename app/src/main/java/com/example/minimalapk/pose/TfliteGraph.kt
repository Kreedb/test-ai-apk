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
        val (tensors, _) = vector(subgraph, 0) ?: error("模型没有张量")
        val (indices, count) = vector(subgraph, fieldIndex) ?: return emptyList()
        return (0 until count).map { tableAt(tensors + i32(indices + it * 4) * 4) }
    }

    private fun firstSubgraph(): Int = subgraphs().firstOrNull() ?: error("模型没有子图")

    private fun subgraphs(): List<Int> {
        val (start, count) = vector(u32(0), 2) ?: error("模型没有子图")
        return (0 until count).map { tableAt(start + it * 4) }
    }

    private fun intVector(table: Int, index: Int): IntArray? {
        val (start, count) = vector(table, index) ?: return null
        return IntArray(count) { i32(start + it * 4) }
    }

    /** (element data position, element count) of a vector field. */
    private fun vector(table: Int, index: Int): Pair<Int, Int>? {
        val position = field(table, index) ?: return null
        val vector = position + u32(position)
        return vector + 4 to u32(vector)
    }

    /** Absolute position of a table field, or null when the vtable does not define it. */
    private fun field(table: Int, index: Int): Int? {
        val vtable = table - i32(table)
        val fieldOffset = 4 + index * 2
        if (fieldOffset + 2 > u16(vtable)) return null
        val offset = u16(vtable + fieldOffset)
        return if (offset == 0) null else table + offset
    }

    private fun tableAt(position: Int): Int = position + u32(position)

    private fun u16(position: Int): Int =
        (bytes[position].toInt() and 0xff) or ((bytes[position + 1].toInt() and 0xff) shl 8)

    private fun u32(position: Int): Int =
        (bytes[position].toInt() and 0xff) or
            ((bytes[position + 1].toInt() and 0xff) shl 8) or
            ((bytes[position + 2].toInt() and 0xff) shl 16) or
            ((bytes[position + 3].toInt() and 0xff) shl 24)

    private fun i32(position: Int): Int = u32(position)

    private fun writeI32(position: Int, value: Int) {
        bytes[position] = value.toByte()
        bytes[position + 1] = (value shr 8).toByte()
        bytes[position + 2] = (value shr 16).toByte()
        bytes[position + 3] = (value shr 24).toByte()
    }
}
