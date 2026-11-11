package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuProbesTest {
    private class FakeFiles(
        val files: MutableMap<String, Any> = mutableMapOf(),
        val directories: MutableMap<String, List<String>> = mutableMapOf(),
    ) : ProbeFileSystem {
        val reads = mutableListOf<String>()
        override fun read(path: String): ProbeRead {
            reads += path
            return when (val value = files[path]) {
                null -> ProbeRead.Missing
                is ProbeRead -> value
                is String -> ProbeRead.Content(value)
                else -> error("Unsupported fake value at $path")
            }
        }

        override fun listDirectories(parent: String): List<String> = directories[parent].orEmpty()
    }

    @Test
    fun strictPercentAcceptsWholeFileOnly() {
        assertEquals(42f, parseStrictPercent("42 %")!!, 0.01f)
        assertEquals(37.5f, parseStrictPercent("37.5")!!, 0.01f)
        assertEquals(100f, parseStrictPercent("100%\n")!!, 0.01f)
        assertNull(parseStrictPercent("load 42%"))
        assertNull(parseStrictPercent("128"))
        assertNull(parseStrictPercent("-4"))
        assertNull(parseStrictPercent("n/a"))
    }

    @Test
    fun rockchipParsesEveryCoreAndWhitespaceZero() {
        val two = checkNotNull(parseRockchipLoad("NPU load: Core0: 12%, Core1:  3%"))
        assertEquals(listOf(0, 1), two.coreIndexes)
        assertEquals(listOf(12f, 3f), two.perCore)
        assertEquals(7.5f, two.average, 0.01f)

        val empty = checkNotNull(parseRockchipLoad("NPU load: Core0:  %, Core1:  %"))
        assertEquals(listOf(0f, 0f), empty.perCore)
        assertEquals(0f, empty.average, 0.01f)

        // The core number itself must never be read as the load value.
        assertEquals(listOf(0f), checkNotNull(parseRockchipLoad("NPU load: Core0:   %")).perCore)
        assertNull(parseRockchipLoad("NPU load: Core0"))
        assertNull(parseRockchipLoad("Core0: 12%"))
    }

    @Test
    fun samsungRqLoadUsesHundredthPercentUnits() {
        assertEquals(25f, parseSamsungRqLoad("2500")!!, 0.01f)
        assertEquals(100f, parseSamsungRqLoad("10000")!!, 0.01f)
        assertEquals(0f, parseSamsungRqLoad("0")!!, 0.01f)
        assertNull(parseSamsungRqLoad("10001"))
        assertNull(parseSamsungRqLoad("-1"))
        assertNull(parseSamsungRqLoad("n/a"))
    }

    @Test
    fun vendorSchemeReportsMissingPermissionAndParseStates() {
        val path = "/sys/class/misc/npu/device/load"
        val missing = NpuProbeReader(FakeFiles()).probe(NpuSource.VENDOR_PERCENT, 1).single()
        assertEquals(NpuProbeState.MISSING, missing.state)
        assertNull(missing.value)

        val denied = NpuProbeReader(FakeFiles(mutableMapOf(path to ProbeRead.PermissionDenied)))
            .probe(NpuSource.VENDOR_PERCENT, 1).single()
        assertEquals(NpuProbeState.PERMISSION_DENIED, denied.state)

        val parsed = NpuProbeReader(FakeFiles(mutableMapOf(path to "load 42%")))
            .probe(NpuSource.VENDOR_PERCENT, 1).single()
        assertEquals(NpuProbeState.PARSE_ERROR, parsed.state)
        assertNull(parsed.value)
        assertNotNull(parsed.rawExcerpt)
    }

    @Test
    fun devfreqDiscoveryReadsOnlyKnownLoadAttributes() {
        val root = "/sys/class/devfreq"
        val device = "$root/ff9f0000.npu"
        val files = FakeFiles(
            files = mutableMapOf(
                "$device/name" to "ff9f0000.npu",
                "$device/load" to "42",
                "$device/cur_freq" to "1000000",
                "$device/trans_stat" to "0:1000000:1:0:0",
                "$device/busy" to "1",
            ),
            directories = mutableMapOf(root to listOf(device)),
        )
        val result = NpuProbeReader(files).probe(NpuSource.DEVFREQ, 1).single()
        assertEquals(NpuProbeState.OK, result.state)
        assertEquals(42f, result.value!!, 0.01f)
        assertEquals("$device/load", result.sourcePath)
        assertTrue(files.reads.none { it.endsWith("/trans_stat") || it.endsWith("/cur_freq") ||
            it.endsWith("/busy") || it.endsWith("/address") })
    }

    @Test
    fun rockchipDebugAndProcShareOneParserAndReportPerCore() {
        val path = "/proc/rknpu/load"
        val result = NpuProbeReader(FakeFiles(mutableMapOf(
            path to "NPU load: Core0: 12%, Core1:  %",
        ))).probe(NpuSource.ROCKCHIP, 7).single()
        assertEquals(NpuProbeState.OK, result.state)
        assertEquals(path, result.sourcePath)
        assertEquals(listOf(12f, 0f), result.perCore)
        assertEquals(6f, result.value!!, 0.01f)
        assertEquals("%", result.unit)
        assertEquals("NPU 负载", result.scope)
    }

    @Test
    fun samsungDistinguishesQueueLoadPolicyAndFrameLoad() {
        val root = "/sys/class/devfreq"
        val device = "$root/npu0"
        val files = FakeFiles(
            files = mutableMapOf(
                "$device/name" to "exynos-npu",
                "$device/rq_load" to "2500",
                "$device/load" to "80",
                "$device/fps_load" to "42",
                "$device/load_policy" to "3",
            ),
            directories = mutableMapOf(root to listOf(device)),
        )
        val result = NpuProbeReader(files).probe(NpuSource.SAMSUNG_RQ_LOAD, 3).single()
        assertEquals(NpuProbeState.OK, result.state)
        assertEquals(25f, result.value!!, 0.01f)
        assertEquals("NPU 请求队列忙碌率", result.scope)
        assertEquals(
            listOf("rq_load", "load（受 load_policy 影响）", "fps_load（执行时间/目标帧时间）"),
            result.readings.map { it.label },
        )
        assertTrue(result.detail!!.contains("load_policy=3"))
        assertTrue(result.detail!!.contains("不并入 rq_load"))
    }

    @Test
    fun qualcommDiagnosticsAreSleepTicksNotUtilization() {
        val cdsp = "/sys/kernel/debug/qcom_stats/cdsp"
        val cdsp1 = "/sys/kernel/debug/qcom_stats/cdsp1"
        val files = FakeFiles(mutableMapOf(
            cdsp to "Count: 12\nLast Entered: 1\nAccumulated Duration: 98765\n",
            cdsp1 to ProbeRead.PermissionDenied,
        ))
        val results = NpuProbeReader(files).probe(NpuSource.QUALCOMM_DIAGNOSTIC, 4)
        assertEquals(2, results.size)
        val readable = results.first { it.sourcePath == cdsp }
        assertEquals(NpuProbeState.OK, readable.state)
        assertNull(readable.value)
        assertEquals("ticks", readable.unit)
        assertTrue(readable.scope.contains("非利用率"))
        assertTrue(readable.rawExcerpt!!.contains("Accumulated Duration"))
        assertEquals(NpuProbeState.PERMISSION_DENIED, results.first { it.sourcePath == cdsp1 }.state)
    }

    @Test
    fun counterDeltaIsExplicitlyBlockedInsteadOfFakingAFifthProbe() {
        val result = NpuProbeReader(FakeFiles()).probeAll(9)
            .first { it.scheme == NpuProbeScheme.COUNTER_DELTA }
        assertEquals(NpuProbeState.UNSUPPORTED, result.state)
        assertNull(result.value)
        assertTrue(result.detail!!.contains("1/1"))
    }

    @Test
    fun samplerMarksLastGoodValueStaleThenLetsItExpire() {
        val path = "/sys/class/misc/npu/device/load"
        val files = FakeFiles(mutableMapOf(path to "42%"))
        val sampler = DeviceUsageSampler(files)
        val fresh = sampler.sample(NpuSource.VENDOR_PERCENT, 1_000_000_000L)
        assertEquals(42f, fresh.npuPercent!!, 0.01f)
        assertEquals(NpuProbeState.OK, fresh.npuResults.single().state)

        files.files[path] = ProbeRead.Missing
        val stale = sampler.sample(NpuSource.VENDOR_PERCENT, 2_000_000_000L)
        assertEquals(NpuProbeState.STALE, stale.npuResults.single().state)
        assertEquals(42f, stale.npuPercent!!, 0.01f)

        val expired = sampler.sample(NpuSource.VENDOR_PERCENT, 10_000_000_000L)
        assertEquals(NpuProbeState.MISSING, expired.npuResults.single().state)
        assertNull(expired.npuPercent)
    }
}
