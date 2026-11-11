package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUsageSamplerTest {
    private class FakeFiles(
        val files: MutableMap<String, ProbeRead> = mutableMapOf(),
        val directories: Map<String, List<String>> = emptyMap(),
    ) : ProbeFileSystem {
        override fun read(path: String): ProbeRead = files[path] ?: ProbeRead.Missing
        override fun listDirectories(parent: String): List<String> = directories[parent].orEmpty()
    }

    @Test
    fun unknownNpuLoadsNeverBecomePercentagesOrCachedSuccesses() {
        val path = "/sys/class/misc/npu/device/load"
        val files = FakeFiles()
        val sampler = DeviceUsageSampler(files)
        listOf("50", "0", "42%", "2500").forEachIndexed { index, raw ->
            files.files[path] = ProbeRead.Content(raw)
            val usage = sampler.sample(NpuSource.VENDOR_PERCENT, (index + 1) * 1_000_000_000L)
            assertNull(usage.npuPercent)
            assertEquals(NpuProbeState.UNSUPPORTED, usage.npu!!.state)
            assertEquals(raw, usage.npu!!.rawExcerpt)
            assertEquals("raw", usage.npu!!.unit)
        }
        files.files[path] = ProbeRead.Missing
        val missing = sampler.sample(NpuSource.VENDOR_PERCENT, 5_000_000_000L)
        assertEquals(NpuProbeState.MISSING, missing.npu!!.state)
        assertNull(missing.npuPercent)
        files.files[path] = ProbeRead.PermissionDenied
        val denied = sampler.sample(NpuSource.VENDOR_PERCENT, 6_000_000_000L)
        assertEquals(NpuProbeState.PERMISSION_DENIED, denied.npu!!.state)
        assertNull(denied.npuPercent)
    }

    @Test
    fun knownLoadCanBecomeStaleButExpiresAndResetClearsIt() {
        val path = "/sys/kernel/debug/rknpu/load"
        val files = FakeFiles(mutableMapOf(path to ProbeRead.Content("NPU load: Core0: 42%")))
        val sampler = DeviceUsageSampler(files)
        val fresh = sampler.sample(NpuSource.ROCKCHIP, 1_000_000_000L)
        assertEquals(42f, fresh.npuPercent!!, 0.01f)
        assertEquals(NpuProbeState.OK, fresh.npu!!.state)

        files.files[path] = ProbeRead.Missing
        val stale = sampler.sample(NpuSource.ROCKCHIP, 2_000_000_000L)
        assertEquals(NpuProbeState.STALE, stale.npu!!.state)
        assertEquals(42f, stale.npuPercent!!, 0.01f)
        assertEquals(1_000_000_000L, stale.npu!!.timestampNanos)

        val expired = sampler.sample(NpuSource.ROCKCHIP, 10_000_000_000L)
        assertEquals(NpuProbeState.MISSING, expired.npu!!.state)
        assertNull(expired.npuPercent)

        files.files[path] = ProbeRead.Content("NPU load: Core0:  %")
        assertEquals(0f, sampler.sample(NpuSource.ROCKCHIP, 11_000_000_000L).npuPercent!!, 0.01f)
        sampler.reset()
        files.files[path] = ProbeRead.Missing
        assertNull(sampler.sample(NpuSource.ROCKCHIP, 12_000_000_000L).npuPercent)
    }

    @Test
    fun autoPrefersFreshKnownFallbackToAnEarlierStaleReading() {
        val rockchip = "/sys/kernel/debug/rknpu/load"
        val device = "/sys/class/devfreq/npu0"
        val files = FakeFiles(
            mutableMapOf(
                rockchip to ProbeRead.Content("NPU load: Core0: 80%"),
                "$device/load" to ProbeRead.Content("50"),
                "$device/rq_load" to ProbeRead.Content("50"),
                "$device/name" to ProbeRead.Content("exynos-npu"),
            ),
            mapOf("/sys/class/devfreq" to listOf(device)),
        )
        val sampler = DeviceUsageSampler(files)
        assertEquals(80f, sampler.sample(NpuSource.AUTO, 1_000_000_000L).npuPercent!!, 0.01f)
        files.files[rockchip] = ProbeRead.Missing
        val fallback = sampler.sample(NpuSource.AUTO, 2_000_000_000L)
        assertTrue(fallback.npuResults.any { it.scheme == NpuProbeScheme.ROCKCHIP && it.state == NpuProbeState.STALE })
        assertEquals(NpuProbeScheme.SAMSUNG_RQ_LOAD, fallback.npu!!.scheme)
        assertEquals(NpuProbeState.OK, fallback.npu!!.state)
        assertEquals(0.5f, fallback.npuPercent!!, 0.01f)
        assertEquals(fallback.npu!!.value, fallback.npuPercent)
    }

    @Test
    fun qualcommSleepDiagnosticsNeverBecomeNpuPercentages() {
        val files = FakeFiles(mutableMapOf(
            "/sys/kernel/debug/qcom_stats/cdsp" to ProbeRead.Content("Accumulated Duration: 50"),
        ))
        val usage = DeviceUsageSampler(files).sample(NpuSource.QUALCOMM_DIAGNOSTIC, 1)
        assertEquals(NpuProbeState.OK, usage.npu!!.state)
        assertEquals("ticks", usage.npu!!.unit)
        assertNull(usage.npuPercent)
    }

    @Test
    fun parsesPercentWithUnitSuffix() {
        assertEquals(42f, parsePercent("42 %")!!, 0.01f)
    }

    @Test
    fun parsesPercentWithDecimal() {
        assertEquals(37.5f, parsePercent("37.5")!!, 0.01f)
    }

    @Test
    fun rejectsOutOfRangePercent() {
        assertNull(parsePercent("128"))
        assertNull(parsePercent("-4"))
    }

    @Test
    fun rejectsContentWithoutNumber() {
        assertNull(parsePercent("n/a"))
    }

    @Test
    fun parsesQualcommBusyTotalRatio() {
        assertEquals(25f, parseBusyTotal("25 100")!!, 0.01f)
    }

    @Test
    fun rejectsBusyTotalWithoutPositiveTotal() {
        assertNull(parseBusyTotal("0 0"))
        assertNull(parseBusyTotal("busy total"))
        listOf("NaN 100", "1 Infinity", "100 25", "25 100 80").forEach { assertNull(parseBusyTotal(it)) }
        listOf("Core0: 42%", "1000000 Hz", "0x55", "load 42%", "NaN").forEach { assertNull(parsePercent(it)) }
    }

    @Test
    fun computesCpuPercentFromProcessDelta() {
        assertEquals(50f, cpuPercent(cpuDeltaMillis = 50, wallDeltaMillis = 100, cores = 1)!!, 0.01f)
        assertEquals(25f, cpuPercent(cpuDeltaMillis = 50, wallDeltaMillis = 100, cores = 2)!!, 0.01f)
    }

    @Test
    fun rejectsCpuPercentWithoutPositiveDelta() {
        assertNull(cpuPercent(cpuDeltaMillis = 0, wallDeltaMillis = 0, cores = 1))
        assertNull(cpuPercent(cpuDeltaMillis = 10, wallDeltaMillis = -1, cores = 1))
        assertNull(cpuPercent(cpuDeltaMillis = 10, wallDeltaMillis = 100, cores = 0))
    }

    @Test
    fun hiddenMetricsDoNotReadFilesOrCpuAndCpuRestartsWithANewBaseline() {
        val reads = mutableListOf<String>()
        var cpuReads = 0
        var cpu = 100L
        val files = object : ProbeFileSystem {
            override fun read(path: String): ProbeRead { reads += path; return ProbeRead.Missing }
            override fun listDirectories(parent: String): List<String> { reads += parent; return emptyList() }
        }
        val sampler = DeviceUsageSampler(files, { cpuReads++; cpu }, { 4 })
        val cpuOnly = UsageMetrics(cpu = true, gpu = false, npu = false)
        assertNull(sampler.sample(NpuSource.ALL, 1_000_000_000, cpuOnly).cpuPercent)
        cpu = 300
        assertEquals(5f, sampler.sample(NpuSource.ALL, 2_000_000_000, cpuOnly).cpuPercent!!, .001f)
        assertTrue(reads.isEmpty())
        sampler.sample(NpuSource.ALL, 3_000_000_000, UsageMetrics(false, false, false))
        assertEquals(2, cpuReads)
        cpu = 1000
        assertNull(sampler.sample(NpuSource.ALL, 4_000_000_000, cpuOnly).cpuPercent)
    }

    @Test
    fun gpuReportsKnownKgslIntervalButNeverInfersUnknownLoadUnits() {
        val kgsl = "/sys/class/kgsl/kgsl-3d0/gpubusy"
        val unknown = "/sys/class/devfreq/mali0/load"
        val files = FakeFiles(mutableMapOf(kgsl to ProbeRead.Content("25 100\n")),
            mapOf("/sys/class/devfreq" to listOf("/sys/class/devfreq/mali0")))
        val sampler = DeviceUsageSampler(files)
        val gpuOnly = UsageMetrics(false, true, false)
        val known = sampler.sample(NpuSource.AUTO, 1, gpuOnly)
        assertEquals(25f, known.gpuPercent!!, .01f)
        assertEquals(kgsl, known.gpuSource)
        assertTrue(known.gpuDetail!!.contains("驱动采样窗"))
        files.files.remove(kgsl)
        files.files[unknown] = ProbeRead.Content("50\n")
        val raw = sampler.sample(NpuSource.AUTO, 2, gpuOnly)
        assertNull(raw.gpuPercent)
        assertEquals(NpuProbeState.UNSUPPORTED, raw.gpuState)
        assertEquals(unknown, raw.gpuSource)
        assertEquals("50\n", raw.gpuEvidence.single { it.path == unknown }.text)
    }

    @Test
    fun cancelDuringAProbePreventsSubsequentFileIo() {
        var needed = true
        var reads = 0
        val files = object : ProbeFileSystem {
            override fun read(path: String): ProbeRead { reads++; needed = false; return ProbeRead.Missing }
            override fun listDirectories(parent: String): List<String> = error("Cancelled sampling must not scan")
        }
        val result = DeviceUsageSampler(files).sample(NpuSource.ALL, 1, UsageMetrics(false, false, true)) { needed }
        assertEquals(1, reads)
        assertTrue(result.npuResults.flatMap { it.evidence }.any { it.state == NpuProbeState.CANCELLED })
    }

    @Test
    fun disappearingRockchipProcfsValueIsStaleEvenWhenDebugfsRemainsDenied() {
        val debug = "/sys/kernel/debug/rknpu/load"
        val proc = "/proc/rknpu/load"
        val files = FakeFiles(mutableMapOf(debug to ProbeRead.PermissionDenied, proc to ProbeRead.Content("NPU load: 35%\n")))
        val sampler = DeviceUsageSampler(files)
        val onlyNpu = UsageMetrics(false, false, true)
        assertEquals(35f, sampler.sample(NpuSource.ROCKCHIP, 1_000_000_000, onlyNpu).npuPercent!!, .01f)
        files.files.remove(proc)
        val stale = sampler.sample(NpuSource.ROCKCHIP, 2_000_000_000, onlyNpu)
        assertEquals(NpuProbeState.STALE, stale.npu!!.state)
        assertEquals(proc, stale.npu!!.sourcePath)
        assertEquals(35f, stale.npuPercent!!, .01f)
        assertTrue(stale.npuResults.flatMap { it.evidence }.any { it.path == proc && it.state == NpuProbeState.MISSING })
    }
}
