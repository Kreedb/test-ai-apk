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
    fun genericLoadAttributesKeepRawEvidenceWithoutInferringUnitsOrScope() {
        val device = "/sys/class/devfreq/npu0"
        val sources = listOf(
            NpuSource.VENDOR_PERCENT to "/sys/class/misc/npu/device/load",
            NpuSource.VENDOR_PERCENT to "/sys/devices/platform/npu/load",
            NpuSource.DEVFREQ to "$device/load",
            NpuSource.DEVFREQ to "$device/npu_load",
        )
        for ((source, path) in sources) {
            // Even 0, a 0..100 scalar, or an explicit % cannot establish what this driver measures.
            listOf("0", "50", "37.5", "100", "2500", "0.01", "42 %", "load 42%", "50 ticks", "n/a").forEach { raw ->
                val files = FakeFiles(
                    mutableMapOf(path to raw), mutableMapOf("/sys/class/devfreq" to listOf(device)),
                )
                val result = NpuProbeReader(files).probe(source, 7).single()
                assertEquals("$path=$raw", NpuProbeState.UNSUPPORTED, result.state)
                assertNull(result.value)
                assertEquals("raw", result.unit)
                assertEquals(path, result.sourcePath)
                assertEquals(raw, result.rawExcerpt)
                assertEquals(7L, result.timestampNanos)
                assertTrue(result.detail!!.contains("单位/作用域未确认"))
                assertTrue(result.detail!!.contains("驱动契约"))
                assertTrue(result.readings.isEmpty())
            }
        }
    }

    @Test
    fun invalidRawContentsAndReadFailuresAreDistinctFromUnknownUnits() {
        val device = "/sys/class/devfreq/npu0"
        val sources = listOf(
            NpuSource.VENDOR_PERCENT to "/sys/class/misc/npu/device/load",
            NpuSource.DEVFREQ to "$device/npu_load",
        )
        for ((source, path) in sources) {
            listOf("", " \n", "NaN", "Infinity", "-Infinity").forEach { raw ->
                val files = FakeFiles(
                    mutableMapOf(path to raw), mutableMapOf("/sys/class/devfreq" to listOf(device)),
                )
                val result = NpuProbeReader(files).probe(source, 1).single()
                assertEquals("$path=$raw", NpuProbeState.PARSE_ERROR, result.state)
                assertNull(result.value)
                assertEquals("raw", result.unit)
                assertNotNull(result.detail)
            }
            val files = FakeFiles(
                mutableMapOf(path to ProbeRead.Failure("读取失败 EIO")),
                mutableMapOf("/sys/class/devfreq" to listOf(device)),
            )
            val failure = NpuProbeReader(files).probe(source, 2).single()
            assertEquals(NpuProbeState.PARSE_ERROR, failure.state)
            assertEquals("读取失败 EIO", failure.detail)
            assertEquals(path, failure.sourcePath)
            assertNull(failure.value)
        }
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
        assertEquals(37.5f, checkNotNull(parseRockchipLoad("NPU load: Core0: 37.5%\n")).average, 0.01f)
        assertEquals(100f, checkNotNull(parseRockchipLoad("NPU load: Core0: 100%")).average, 0.01f)
        assertNull(parseRockchipLoad("NPU load: Core0: 128%"))
        assertNull(parseRockchipLoad("NPU load: Core0: -4%"))
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
    fun genericSchemesReportMissingAndDeniedWithoutInventingZero() {
        val device = "/sys/class/devfreq/npu0"
        val sources = listOf(
            NpuSource.VENDOR_PERCENT to "/sys/class/misc/npu/device/load",
            NpuSource.DEVFREQ to "$device/load",
        )
        for ((source, path) in sources) {
            val files = FakeFiles(directories = mutableMapOf("/sys/class/devfreq" to listOf(device)))
            val reader = NpuProbeReader(files)
            val missing = reader.probe(source, 1).single()
            assertEquals(NpuProbeState.MISSING, missing.state)
            assertEquals(path, missing.sourcePath)
            assertEquals("raw", missing.unit)
            assertNull(missing.value)
            files.files[path] = ProbeRead.PermissionDenied
            val denied = reader.probe(source, 2).single()
            assertEquals(NpuProbeState.PERMISSION_DENIED, denied.state)
            assertEquals(path, denied.sourcePath)
            assertNull(denied.value)
            assertNull(denied.rawExcerpt)
        }
    }

    @Test
    fun unsupportedAttributeDoesNotHideOtherRawAttributesOrPermissionFailures() {
        val root = "/sys/class/devfreq"
        val first = "$root/npu0"
        val second = "$root/npu1"
        val files = FakeFiles(
            mutableMapOf(
                "$first/load" to "50",
                "$first/npu_load" to "2500",
                "$second/load" to ProbeRead.PermissionDenied,
            ),
            mutableMapOf(root to listOf(first, second)),
        )
        val results = NpuProbeReader(files).probe(NpuSource.DEVFREQ, 1)
        assertEquals(listOf("$first/load", "$first/npu_load", "$second/load"), results.map { it.sourcePath })
        assertEquals(listOf(NpuProbeState.UNSUPPORTED, NpuProbeState.UNSUPPORTED, NpuProbeState.PERMISSION_DENIED),
            results.map { it.state })
        assertEquals(listOf("50", "2500", null), results.map { it.rawExcerpt })
        assertTrue(results.all { it.value == null && it.unit == "raw" })
    }

    @Test
    fun devfreqDiscoveryDoesNotTurnScalarLoadOrFrequencyCountersIntoPercentages() {
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
        assertEquals(NpuProbeState.UNSUPPORTED, result.state)
        assertNull(result.value)
        assertEquals("42", result.rawExcerpt)
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
                "$device/rq_load" to "50",
                "$device/load" to "50",
                "$device/fps_load" to "42",
                "$device/load_policy" to "3",
            ),
            directories = mutableMapOf(root to listOf(device)),
        )
        val result = NpuProbeReader(files).probe(NpuSource.SAMSUNG_RQ_LOAD, 3).single()
        assertEquals(NpuProbeState.OK, result.state)
        assertEquals(0.5f, result.value!!, 0.01f)
        assertEquals("NPU 请求队列忙碌率", result.scope)
        assertEquals(
            listOf("rq_load", "load（受 load_policy 影响）", "fps_load（执行时间/目标帧时间）"),
            result.readings.map { it.label },
        )
        assertTrue(result.detail!!.contains("load_policy=3"))
        assertTrue(result.detail!!.contains("不并入 rq_load"))
        val policyLoad = result.readings.single { it.label.startsWith("load（") }
        assertEquals(50f, policyLoad.value, 0.01f)
        assertEquals("raw", policyLoad.unit)
        val generic = NpuProbeReader(files).probe(NpuSource.DEVFREQ, 3).single()
        assertEquals(NpuProbeState.UNSUPPORTED, generic.state)
        assertEquals("50", generic.rawExcerpt)
        assertNull(generic.value)
    }

    @Test
    fun autoFindsKnownSamsungQueueLoadWithoutMistakingPolicyLoadForPercent() {
        val device = "/sys/class/misc/npu"
        val files = FakeFiles(
            mutableMapOf(
                "$device/device/load" to "50",
                "$device/device/rq_load" to "50",
                "$device/device/load_policy" to "3",
            ),
            mutableMapOf("/sys/class/misc" to listOf(device)),
        )
        val reader = NpuProbeReader(files)
        assertEquals(NpuProbeState.UNSUPPORTED, reader.probe(NpuSource.VENDOR_PERCENT, 1).single().state)
        val known = reader.probe(NpuSource.AUTO, 1).single { it.state == NpuProbeState.OK }
        assertEquals(NpuProbeScheme.SAMSUNG_RQ_LOAD, known.scheme)
        assertEquals("$device/device/rq_load", known.sourcePath)
        assertEquals(0.5f, known.value!!, 0.01f)
        assertEquals("NPU 请求队列忙碌率", known.scope)
    }

    @Test
    fun autoContinuesPastUnsupportedSchemesAndKeepsUnknownValuesDiagnosticOnly() {
        val files = FakeFiles(mutableMapOf("/sys/class/misc/npu/device/load" to "50"))
        val reader = NpuProbeReader(files)
        val results = reader.probe(NpuSource.AUTO, 1)
        assertEquals(
            listOf(NpuProbeScheme.ROCKCHIP, NpuProbeScheme.SAMSUNG_RQ_LOAD,
                NpuProbeScheme.VENDOR_PERCENT, NpuProbeScheme.DEVFREQ),
            results.map { it.scheme },
        )
        assertTrue(results.none { it.state == NpuProbeState.OK || it.value != null })
        assertEquals("50", results.single { it.scheme == NpuProbeScheme.VENDOR_PERCENT }.rawExcerpt)

        // A subsequent documented Rockchip source is selected normally, including a real idle 0%.
        files.files["/proc/rknpu/load"] = "NPU load: Core0:  %"
        val known = reader.probe(NpuSource.AUTO, 2).single { it.state == NpuProbeState.OK }
        assertEquals(NpuProbeScheme.ROCKCHIP, known.scheme)
        assertEquals(0f, known.value!!, 0.01f)
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
}
