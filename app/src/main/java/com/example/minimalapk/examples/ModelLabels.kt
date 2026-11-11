package com.example.minimalapk.examples

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * TFLite metadata ZIPs start after the FlatBuffer, not at byte zero. Android's ZipFile rejects
 * that layout on some releases. Resolve the bounded central directory instead of scanning model
 * weights for a PK signature, then read only the explicitly named resource. Nothing is extracted.
 */
internal object ModelLabels {
    internal const val MAX_LABEL_BYTES = 1024 * 1024
    private const val MAX_ENTRIES = 64
    private const val MAX_LABELS = 10_000

    fun read(file: File, entryName: String): List<String> = RandomAccessFile(file, "r").use { input ->
        val tailSize = minOf(input.length(), 22L + 65535L).toInt()
        val tail = input.readAt(input.length() - tailSize, tailSize)
        val end = (tail.size - 22 downTo 0).firstOrNull { offset ->
            tail.u32(offset) == 0x06054b50L && offset + 22 + tail.u16(offset + 20) == tail.size
        } ?: error("模型未附带标签 ZIP：$entryName")
        require(tail.u16(end + 4) == 0 && tail.u16(end + 6) == 0) { "不支持分卷标签 ZIP" }
        val count = tail.u16(end + 10)
        require(count in 1..MAX_ENTRIES && count == tail.u16(end + 8)) { "标签 ZIP 条目数无效" }
        val directorySize = tail.u32(end + 12)
        require(directorySize in 46L..MAX_LABEL_BYTES.toLong()) { "标签 ZIP 目录过大或无效" }
        val directoryStart = input.length() - tailSize + end - directorySize
        val storedDirectoryStart = tail.u32(end + 16)
        // Both absolute offsets (metadata packer) and a separately appended ZIP are supported.
        val prefix = directoryStart - storedDirectoryStart
        require(directoryStart >= 0 && prefix >= 0 && storedDirectoryStart != 0xffffffffL) {
            "标签 ZIP 目录偏移无效"
        }
        val directory = input.readAt(directoryStart, directorySize.toInt())
        val names = mutableSetOf<String>()
        var selected: Entry? = null
        var cursor = 0
        repeat(count) {
            require(cursor <= directory.size - 46 && directory.u32(cursor) == 0x02014b50L) {
                "标签 ZIP 目录损坏"
            }
            val nameSize = directory.u16(cursor + 28)
            val next = cursor + 46 + nameSize + directory.u16(cursor + 30) + directory.u16(cursor + 32)
            require(nameSize in 1..1024 && next <= directory.size && directory.u16(cursor + 34) == 0) {
                "标签 ZIP 条目无效"
            }
            val name = utf8(directory.copyOfRange(cursor + 46, cursor + 46 + nameSize))
            require(!name.startsWith('/') && '\\' !in name && '\u0000' !in name &&
                name.removeSuffix("/").split('/').none { it.isEmpty() || it == "." || it == ".." } && names.add(name)) {
                "标签 ZIP 文件名无效或重复"
            }
            if (name == entryName) {
                val compressed = directory.u32(cursor + 20)
                val size = directory.u32(cursor + 24)
                val offset = directory.u32(cursor + 42)
                require(compressed in 1L..MAX_LABEL_BYTES.toLong() && size in 1L..MAX_LABEL_BYTES.toLong() &&
                    offset != 0xffffffffL) { "标签资源超过 1 MB 或为空" }
                selected = Entry(
                    name, directory.u16(cursor + 8), directory.u16(cursor + 10),
                    directory.u32(cursor + 16), compressed.toInt(), size.toInt(), prefix + offset,
                )
            }
            cursor = next
        }
        require(cursor == directory.size) { "标签 ZIP 目录长度不符" }
        val entry = selected ?: error("模型缺少标签资源：$entryName")
        require(entry.flags and 0x080e.inv() == 0 && entry.method in listOf(0, 8)) {
            "不支持加密或此压缩格式的标签 ZIP"
        }
        require(entry.offset >= prefix && entry.offset <= directoryStart - 30) { "标签 ZIP 本地偏移无效" }
        val header = input.readAt(entry.offset, 30)
        require(header.u32(0) == 0x04034b50L && header.u16(6) == entry.flags && header.u16(8) == entry.method) {
            "标签 ZIP 本地头不符"
        }
        val nameSize = header.u16(26)
        val dataStart = entry.offset + 30 + nameSize + header.u16(28)
        require(nameSize in 1..1024 && dataStart <= directoryStart - entry.compressedSize &&
            utf8(input.readAt(entry.offset + 30, nameSize)) == entry.name) { "标签 ZIP 数据范围或文件名不符" }
        if (entry.flags and 8 == 0) {
            require(header.u32(14) == entry.crc && header.u32(18) == entry.compressedSize.toLong() &&
                header.u32(22) == entry.size.toLong()) { "标签 ZIP 本地长度或校验值不符" }
        }
        val compressed = input.readAt(dataStart, entry.compressedSize)
        val bytes = if (entry.method == 0) compressed else inflate(compressed, entry.size)
        require(bytes.size == entry.size && CRC32().apply { update(bytes) }.value == entry.crc) {
            "标签 ZIP 长度或 CRC 校验失败"
        }
        parse(entryName, utf8(bytes))
    }

    /** Commas belong to TXT labels, including YAMNet's "Child speech, kid speaking". */
    internal fun parse(name: String, text: String): List<String> {
        require(name.endsWith(".txt", ignoreCase = true)) { "标签资源必须是逐行 TXT 文件" }
        require(text.length <= MAX_LABEL_BYTES) { "标签资源超过 1 MB" }
        val content = text.removePrefix("\uFEFF").trimEnd('\r', '\n')
        val labels = content.lineSequence().take(MAX_LABELS + 1).toList()
        require(labels.size in 1..MAX_LABELS && labels.all {
            it.isNotBlank() && '\u0000' !in it && '\n' !in it && '\r' !in it
        }) { "标签为空或格式无效" }
        return labels
    }

    private data class Entry(
        val name: String, val flags: Int, val method: Int, val crc: Long,
        val compressedSize: Int, val size: Int, val offset: Long,
    )

    private fun inflate(compressed: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            return InflaterInputStream(ByteArrayInputStream(compressed), inflater).use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    require(read <= expected - output.size()) { "标签解压后超过声明的大小" }
                    output.write(buffer, 0, read)
                }
                require(inflater.finished() && inflater.bytesRead == compressed.size.toLong()) { "标签压缩数据不完整" }
                output.toByteArray()
            }
        } finally {
            inflater.end()
        }
    }

    private fun RandomAccessFile.readAt(offset: Long, size: Int): ByteArray {
        require(offset >= 0 && size >= 0 && offset <= length() - size) { "标签 ZIP 读取越界" }
        seek(offset)
        return ByteArray(size).also { readFully(it) }
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)

    private fun ByteArray.u32(offset: Int): Long =
        u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

}
