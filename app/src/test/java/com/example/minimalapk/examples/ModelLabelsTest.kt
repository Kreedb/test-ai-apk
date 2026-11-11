package com.example.minimalapk.examples

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelLabelsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun readsStoredMetadataWithAbsoluteOffsetsAfterTheFlatbuffer() {
        // Same layout as the pinned models; Android ZipFile checks byte zero (0x1c, not PK).
        val file = archive("labels.txt", "background\ntench\ngoldfish\n", absolute = true, stored = true)
        assertEquals(0x1c, file.readBytes()[0].toInt())
        assertEquals(listOf("background", "tench", "goldfish"), ModelLabels.read(file, "labels.txt"))
    }

    @Test
    fun readsDeflatedRelativeOffsetsWithoutScanningFalseLocalSignaturesInWeights() {
        val file = archive("yamnet_label_list.txt", "Speech\nChild speech, kid speaking\nNarration, monologue\n")
        assertEquals(
            listOf("Speech", "Child speech, kid speaking", "Narration, monologue"),
            ModelLabels.read(file, "yamnet_label_list.txt"),
        )
        assertThrows(IllegalStateException::class.java) { ModelLabels.read(file, "other.txt") }
    }

    @Test
    fun unsupportedLabelFormatsCannotShiftClassIndices() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelLabels.parse("yamnet.csv", "index,mid,display_name\n0,/m/test,Speech")
        }
    }

    @Test
    fun preservesCocoHolesAndRejectsEmptyRowsInsteadOfRenumberingClasses() {
        assertEquals(listOf("person", "???", "car"), ModelLabels.parse("labelmap.txt", "\uFEFFperson\r\n???\r\ncar\r\n"))
        assertThrows(IllegalArgumentException::class.java) { ModelLabels.parse("labels.txt", "person\n\ncar\n") }
    }

    @Test
    fun corruptCrcAndLocalNameCannotBeAccepted() {
        val crc = archive("labels.txt", "Speech\n", stored = true)
        val bytes = crc.readBytes()
        val local = bytes.signature(0x04034b50, start = PREFIX_SIZE)
        val nameSize = bytes.u16(local + 26)
        val data = local + 30 + nameSize + bytes.u16(local + 28)
        bytes[data] = (bytes[data].toInt() xor 1).toByte()
        crc.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { ModelLabels.read(crc, "labels.txt") }

        val mismatch = archive("labels.txt", "Speech\n")
        val changed = mismatch.readBytes()
        changed[changed.signature(0x04034b50, start = PREFIX_SIZE) + 30] = 'x'.code.toByte()
        mismatch.writeBytes(changed)
        assertThrows(IllegalArgumentException::class.java) { ModelLabels.read(mismatch, "labels.txt") }
    }

    @Test
    fun oversizedAndDishonestlySizedDeflateAreBoundedBeforeExpansion() {
        val oversized = archive("labels.txt", "Speech\n")
        val bytes = oversized.readBytes()
        bytes.put32(bytes.signature(0x02014b50) + 24, ModelLabels.MAX_LABEL_BYTES + 1)
        oversized.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { ModelLabels.read(oversized, "labels.txt") }

        val bomb = archive("labels.txt", "a".repeat(200_000))
        val dishonest = bomb.readBytes()
        dishonest.put32(dishonest.signature(0x02014b50) + 24, 2)
        bomb.writeBytes(dishonest)
        assertThrows(IllegalArgumentException::class.java) { ModelLabels.read(bomb, "labels.txt") }
    }

    @Test
    fun maliciousDirectoryOffsetsCountsAndPathsAreRejected() {
        val offset = archive("labels.txt", "Speech\n")
        val bytes = offset.readBytes()
        bytes.put32(bytes.signature(0x02014b50) + 42, Int.MAX_VALUE)
        offset.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { ModelLabels.read(offset, "labels.txt") }

        val count = archive("labels.txt", "Speech\n")
        val many = count.readBytes()
        val end = many.signature(0x06054b50)
        many[end + 8] = 100
        many[end + 10] = 100
        count.writeBytes(many)
        assertThrows(IllegalArgumentException::class.java) { ModelLabels.read(count, "labels.txt") }

        val traversal = archive("../labels.txt", "Speech\n")
        assertThrows(IllegalArgumentException::class.java) { ModelLabels.read(traversal, "../labels.txt") }
        val noArchive = folder.newFile().apply { writeBytes(ByteArray(16)) }
        assertThrows(IllegalStateException::class.java) { ModelLabels.read(noArchive, "labels.txt") }
    }

    private fun archive(name: String, text: String, absolute: Boolean = false, stored: Boolean = false): File {
        val bytes = text.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry = ZipEntry(name)
            if (stored) {
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
            }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("not a label map".toByteArray())
            zip.closeEntry()
        }
        val archive = out.toByteArray()
        if (absolute) {
            var start = 0
            repeat(2) {
                val central = archive.signature(0x02014b50, start)
                archive.put32(central + 42, archive.u32(central + 42) + PREFIX_SIZE)
                start = central + 46
            }
            val end = archive.signature(0x06054b50)
            archive.put32(end + 16, archive.u32(end + 16) + PREFIX_SIZE)
        }
        val prefix = ByteArray(PREFIX_SIZE).also {
            it[0] = 0x1c
            "TFL3".toByteArray().copyInto(it, 4)
            it.put32(16, 0x04034b50) // A fake PK in weights must never be treated as the archive.
        }
        return folder.newFile().apply { writeBytes(prefix + archive) }
    }

    private fun ByteArray.signature(value: Int, start: Int = 0): Int =
        (start..size - 4).first { u32(it) == value }

    private fun ByteArray.u16(offset: Int) = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(offset: Int) = u16(offset) or (u16(offset + 2) shl 16)
    private fun ByteArray.put32(offset: Int, value: Int) {
        repeat(4) { this[offset + it] = (value ushr (8 * it)).toByte() }
    }

    private companion object { const val PREFIX_SIZE = 64 }
}
