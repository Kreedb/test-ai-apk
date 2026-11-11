package com.example.minimalapk.diagnostics

import androidx.test.platform.app.InstrumentationRegistry
import com.example.minimalapk.log.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real APK asset/native CPU smoke; deliberately does not pretend an emulator proves GPU or HTP. */
class BackendComparisonSmokeTest {
    @Test
    fun bundledYoloCpuComparisonLoadsInfersReadsFiniteOutputAndCloses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plan = BackendComparisonPlan(warmups = 0, repetitions = 1, budgetMs = 180_000)
        val report = BackendComparisonLiteRt(context).run(
            BackendComparisonCancellation(budgetMs = plan.budgetMs), plan,
            backends = listOf(ComparisonBackend.CPU),
        )
        report.logLines().forEach { AppLog.i("BackendComparisonSmoke", it) }
        val cpu = report.results.single()
        assertEquals(report.logLines().joinToString("\n"), ComparisonStatus.COMPLETE, cpu.status)
        assertEquals(1, cpu.measured.size)
        assertEquals(8400 * 56, cpu.measured.single().output!!.elements)
        assertTrue(cpu.loadMs!!.isFinite() && cpu.loadMs!! >= 0)
        assertTrue(cpu.closeMs!!.isFinite() && cpu.closeMs!! >= 0)
        assertTrue(cpu.completionMedianMs!!.isFinite() && cpu.completionMedianMs!! >= 0)
        assertTrue(cpu.measured.single().timingsMs.keys.containsAll(listOf("write", "infer", "readback", "validate")))
        assertEquals(64, report.resource.sha256.length)
        assertEquals(64, report.inputSha256.length)
        assertFalse(report.cancelled)
    }
}
