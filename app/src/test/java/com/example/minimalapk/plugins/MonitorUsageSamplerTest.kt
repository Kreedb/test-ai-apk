package com.example.minimalapk.plugins

import com.example.minimalapk.pose.DeviceUsage
import com.example.minimalapk.pose.DeviceUsageSource
import com.example.minimalapk.pose.NpuSource
import com.example.minimalapk.pose.UsageMetrics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MonitorUsageSamplerTest {
    @Test
    fun onlyLatestRequestSurvivesAndResetInvalidatesPendingAndCachedSamples() {
        val tasks = ArrayDeque<Runnable>()
        val requested = mutableListOf<NpuSource>()
        var resets = 0
        val source = object : DeviceUsageSource {
            override fun sample(source: NpuSource, nowNanos: Long, metrics: UsageMetrics, stillNeeded: () -> Boolean): DeviceUsage {
                requested += source
                return DeviceUsage(cpuPercent = 20f, sampledAtNanos = nowNanos)
            }
            override fun reset() { resets++ }
        }
        MonitorUsageSampler(source, { 123L }, Executor { tasks += it }).use { worker ->
            repeat(50) { worker.sample(NpuSource.ROCKCHIP, 0) }
            worker.sample(NpuSource.ALL, 0)
            assertEquals(1, tasks.size)
            tasks.removeFirst().run()
            assertEquals(listOf(NpuSource.ALL), requested)
            assertEquals(20f, worker.sample(NpuSource.AUTO, 0).cpuPercent!!, .01f)
            worker.reset()
            assertNull(worker.sample(NpuSource.SAMSUNG_RQ_LOAD, 0).cpuPercent)
            tasks.removeFirst().run()
            assertEquals(listOf(NpuSource.ALL, NpuSource.SAMSUNG_RQ_LOAD), requested)
            assertEquals(1, resets)
        }
        tasks.removeFirst().run()
        assertEquals(2, resets)
    }

    @Test
    fun blockedReadCannotBlockResetOrCloseAndLateResultCannotReturn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resetAfterRead = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val source = object : DeviceUsageSource {
            override fun sample(source: NpuSource, nowNanos: Long, metrics: UsageMetrics, stillNeeded: () -> Boolean): DeviceUsage {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                assertFalse("Stop must cancel subsequent filesystem reads", stillNeeded())
                return DeviceUsage(cpuPercent = 99f)
            }
            override fun reset() { resetAfterRead.countDown() }
        }
        val worker = MonitorUsageSampler(source, executor = executor)
        try {
            assertNull(worker.sample(NpuSource.ALL, 0).cpuPercent)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // These return while the driver read is still held; no wall-clock race assertion.
            worker.reset()
            assertNull(worker.sample(NpuSource.AUTO, 0).cpuPercent)
            worker.close()
            assertEquals(1L, release.count)
            release.countDown()
            assertTrue(resetAfterRead.await(2, TimeUnit.SECONDS))
            assertNull(worker.sample(NpuSource.AUTO, 0).cpuPercent)
        } finally {
            release.countDown()
            worker.close()
            executor.shutdownNow()
        }
    }
}
