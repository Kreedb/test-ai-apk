package com.example.minimalapk.pose

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class NpuProbesFilesTest {
    @Test
    fun shortReadsAreAssembledToEofAndPreserveWhitespaceExactly() {
        val raw = "  NPU sample\r\n" + "值=123\n".repeat(200) + "  END\n"
        val underlying = ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))
        val input = object : InputStream() {
            override fun read(): Int = underlying.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = underlying.read(bytes, offset, minOf(3, length))
        }
        val result = readProbeContent(input)
        assertEquals(raw, result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun exactLimitIsCompleteButOversizePrefixCannotBeParsedAsSuccessfulLoad() {
        val exact = "x".repeat(MAX_PROBE_BYTES)
        assertFalse(readProbeContent(ByteArrayInputStream(exact.toByteArray())).truncated)
        assertTrue(readProbeContent(ByteArrayInputStream((exact + "x").toByteArray())).truncated)
        val raw = "NPU load: Core0: 12%".padEnd(MAX_PROBE_BYTES, ' ') + "INVALID"
        val files = object : ProbeFileSystem {
            override fun read(path: String): ProbeRead = if (path == "/proc/rknpu/load")
                readProbeContent(ByteArrayInputStream(raw.toByteArray())) else ProbeRead.Missing
            override fun listDirectories(parent: String) = emptyList<String>()
        }
        val result = NpuProbeReader(files, { 0L }).probe(NpuSource.ROCKCHIP, 1).single { it.sourcePath == "/proc/rknpu/load" }
        assertEquals(NpuProbeState.TRUNCATED, result.state)
        assertNull(result.value)
        val evidence = result.evidence.single { it.path == "/proc/rknpu/load" }
        assertEquals(raw.take(MAX_PROBE_BYTES), evidence.text)
        assertEquals(NpuProbeState.TRUNCATED, evidence.state)
    }

    @Test
    fun deadlineAndDirectoryPermissionFailureAreReportableAndStopFurtherReads() {
        var clock = 0L
        var reads = 0
        val files = object : ProbeFileSystem {
            override fun read(path: String): ProbeRead { reads++; clock += 201_000_000L; return ProbeRead.Missing }
            override fun listDirectories(parent: String) = emptyList<String>()
            override fun scanDirectories(parent: String) = ProbeDirectories(state = NpuProbeState.PERMISSION_DENIED)
        }
        val direct = NpuProbeReader(files, { clock }).probe(NpuSource.DEVFREQ, 1).single()
        assertEquals(NpuProbeState.PERMISSION_DENIED, direct.state)
        val all = NpuProbeReader(files, { clock }).probeAll(2)
        assertEquals(1, reads)
        assertTrue(all.flatMap { it.evidence }.any { it.state == NpuProbeState.TIMEOUT })
        assertTrue(all.none { it.state == NpuProbeState.OK })
    }
}
