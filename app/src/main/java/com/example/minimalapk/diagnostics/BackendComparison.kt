package com.example.minimalapk.diagnostics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

internal enum class ComparisonBackend(val request: String, val execution: String) {
    CPU("CPU", "CPU（仅请求 CPU）"),
    GPU("GPU+CPU", "未知（GPU/CPU 分区不可见）"),
    NPU("NPU", "未知（LiteRT 可回退 CPU）"),
}

internal enum class ComparisonStatus(val label: String) {
    COMPLETE("完成"), FAILED("失败"), SKIPPED("跳过"), CANCELLED("取消"),
}

/** Fixed small experiment, not a catalog benchmark. Bounds apply even to test/diagnostic callers. */
internal data class BackendComparisonPlan(
    val warmups: Int = 1,
    val repetitions: Int = 3,
    val budgetMs: Long = 120_000,
) {
    init {
        require(warmups in 0..2 && repetitions in 1..5)
        require(budgetMs in 1..180_000)
    }
}

internal data class BackendComparisonResource(
    val name: String,
    val source: String,
    val bytes: Long,
    val sha256: String,
    val inputShape: List<Int>,
    val outputShape: List<Int>,
)

internal data class BackendComparisonDevice(
    val sdk: Int,
    val abis: List<String>,
    val socManufacturer: String,
    val description: String,
) {
    fun unavailable(backend: ComparisonBackend): String? {
        if (sdk < 24) return "LiteRT 需要 API 24+，当前 $sdk"
        if (abis.none { it == "arm64-v8a" || it == "x86_64" }) return "APK 未包含此 ABI：$abis"
        if (backend == ComparisonBackend.NPU) {
            if (sdk < 31) return "QNN HTP 需要 Android 12 / API 31+，当前 $sdk"
            if ("arm64-v8a" !in abis) return "QNN HTP 仅支持 arm64-v8a，当前 $abis"
            if (socManufacturer.trim().lowercase(Locale.ROOT) !in setOf("qualcomm", "qti")) {
                return "APK 仅带 Qualcomm HTP；当前 SoC 厂商 ${socManufacturer.ifBlank { "未知" }}"
            }
        }
        return null
    }
}

internal data class BackendComparisonOutput(val elements: Int, val minimum: Float, val maximum: Float)

internal data class BackendComparisonIteration(
    val number: Int,
    val warmup: Boolean,
    val timingsMs: Map<String, Double>,
    /** Present only after shape/count and every output value have been checked. */
    val output: BackendComparisonOutput?,
)

internal data class BackendComparisonResult(
    val backend: ComparisonBackend,
    val status: ComparisonStatus,
    val stage: String,
    val detail: String,
    val loadMs: Double? = null,
    val closeMs: Double? = null,
    val iterations: List<BackendComparisonIteration> = emptyList(),
    val runtimeDetails: String = "未加载",
    val closeError: String? = null,
) {
    val measured: List<BackendComparisonIteration> get() = iterations.filter { !it.warmup && it.output != null }
    val warmed: Int get() = iterations.count { it.warmup && it.output != null }
    fun median(stage: String): Double? = comparisonMedian(measured.mapNotNull { it.timingsMs[stage] })
    /** Paired invocation-to-blocking-readback wall time; includes the boundary between the calls. */
    val completionMedianMs: Double? get() = comparisonMedian(measured.map {
        it.timingsMs.getValue("infer+readback")
    })
}

internal data class BackendComparisonReport(
    val resource: BackendComparisonResource,
    val inputSha256: String,
    val plan: BackendComparisonPlan,
    val device: String,
    val results: List<BackendComparisonResult>,
    val elapsedMs: Double,
    val cancelled: Boolean,
) {
    fun compactLines(): List<String> = listOf(
        "后端比较${if (cancelled) "（取消）" else ""} ${resource.name} · " +
            "预热${plan.warmups}/测${plan.repetitions}（推/回取中位，加载/关闭各1次；当前应用负载） · " +
            "模型#${resource.sha256.take(8)} 输入#${inputSha256.take(8)} · 详细数据见日志",
    ) + results.map { result ->
        val prefix = "请求${result.backend.request} ${result.status.label} ${result.measured.size}/${plan.repetitions}"
        if (result.status == ComparisonStatus.COMPLETE) {
            "$prefix · 加载${comparisonMs(result.loadMs)} / 推${comparisonMs(result.median("infer"))}" +
                " / 回${comparisonMs(result.median("readback"))} / 关${comparisonMs(result.closeMs)} ms" +
                " · 实际${result.backend.execution}"
        } else {
            "$prefix · ${result.stage}: ${result.detail.take(130)}" +
                (result.closeError?.let { "；释放失败：${it.take(40)}" } ?: "")
        }
    }

    fun logLines(): List<String> = buildList {
        add("model=${resource.name} source=${resource.source} bytes=${resource.bytes} sha256=${resource.sha256}")
        add("input=rgb-pattern-v1 NHWC RGB FLOAT32 [0,1] shape=${resource.inputShape} " +
            "sha256Float32LE=$inputSha256 outputShape=${resource.outputShape}")
        add("device=$device warmups=${plan.warmups} repetitions=${plan.repetitions} " +
            "budgetMs=${plan.budgetMs} backendLoopMs=${comparisonMs(elapsedMs)} cancelled=$cancelled")
        add("serial=true sameInput=true; load/write/infer/readback/validate/close are host wall times; " +
            "infer may enqueue GPU work, readback waits for completion; warmups excluded from medians; " +
            "resource hashing/fixed-input preparation precede backendLoopMs and are excluded; " +
            "no FPS conversion, no hardware ranking; compilation caches/other app work may affect timings; " +
            "budget/cancel checked between native calls, never closes an in-flight call from another thread")
        for (result in results) {
            add("requested=${result.backend.request} status=${result.status} stage=${result.stage} " +
                "actual=${if (result.measured.isEmpty()) "unproven" else result.backend.execution} " +
                "loadMs=${comparisonMs(result.loadMs)} closeMs=${comparisonMs(result.closeMs)} " +
                "warmupsCompleted=${result.warmed} repetitionsCompleted=${result.measured.size} " +
                "invokeAndReadbackMedianMs=${comparisonMs(result.completionMedianMs)} " +
                "detail=${result.detail} closeError=${result.closeError} ${result.runtimeDetails}")
            result.iterations.forEach { iteration ->
                add("requested=${result.backend.request} ${if (iteration.warmup) "warmup" else "measured"}" +
                    "=${iteration.number} timingsMs=${iteration.timingsMs} " +
                    "outputValidated=${iteration.output != null} output=${iteration.output}")
            }
        }
    }
}

/** A session is cheap until load(); even partially failed loads are closed by the same serial loop. */
internal interface BackendComparisonSession : AutoCloseable {
    val details: String
    fun load()
    /** Borrowed immutable values: every backend/iteration receives this same fixed FloatArray. */
    fun write(values: FloatArray)
    fun infer()
    fun readback(): FloatArray
}

internal class BackendComparisonUnavailable(message: String) : Exception(message)

internal class BackendComparisonCancellation(
    private val stillNeeded: () -> Boolean = { true },
    private val clock: () -> Long = System::nanoTime,
    private val budgetMs: Long = 120_000,
) {
    private val started = clock()
    @Volatile var reason: String? = null
        private set

    @Synchronized fun cancel(message: String) { if (reason == null) reason = message }

    fun checkpoint() {
        if (!stillNeeded()) cancel("页面/插件已停止")
        if (clock() - started >= budgetMs * 1_000_000L) cancel("达到 ${budgetMs} ms 协作式预算")
        reason?.let { throw CancellationException(it) }
    }
}

/** Synchronous on exactly one worker: close completes before the next backend can be constructed. */
internal class BackendComparisonRunner(private val clock: () -> Long = System::nanoTime) {
    fun run(
        resource: BackendComparisonResource,
        input: FloatArray,
        inputSha256: String,
        device: String,
        plan: BackendComparisonPlan,
        cancellation: BackendComparisonCancellation,
        unavailable: (ComparisonBackend) -> String?,
        create: (ComparisonBackend) -> BackendComparisonSession,
        backends: List<ComparisonBackend> = ComparisonBackend.entries,
        progress: (String, BackendComparisonReport?) -> Unit = { _, _ -> },
    ): BackendComparisonReport {
        require(backends.isNotEmpty() && backends.distinct().size == backends.size)
        require(input.isNotEmpty() && input.all { it.isFinite() })
        require(resource.inputShape.fold(1L) { count, size -> count * size } == input.size.toLong())
        val outputCount = resource.outputShape.fold(1L) { count, size -> count * size }
        require(outputCount in 1..1_000_000)
        val started = clock()
        val results = mutableListOf<BackendComparisonResult>()
        fun report() = BackendComparisonReport(resource, inputSha256, plan, device,
            results.toList(), elapsed(started), cancellation.reason != null)
        for (backend in backends) {
            progress("${backend.request} · 准备", report())
            results += compare(backend, input, outputCount.toInt(), plan, cancellation, unavailable, create) {
                progress("${backend.request} · $it", report())
            }
            progress("${backend.request} · ${results.last().status.label}", report())
        }
        return report()
    }

    private fun compare(
        backend: ComparisonBackend,
        input: FloatArray,
        expectedOutput: Int,
        plan: BackendComparisonPlan,
        cancellation: BackendComparisonCancellation,
        unavailable: (ComparisonBackend) -> String?,
        create: (ComparisonBackend) -> BackendComparisonSession,
        progress: (String) -> Unit,
    ): BackendComparisonResult {
        var session: BackendComparisonSession? = null
        var stage = "eligibility"
        var status = ComparisonStatus.COMPLETE
        var detail = "同模型同输入；输出数量及有限数值已验证（不代表任务准确率）"
        var loadMs: Double? = null
        var closeMs: Double? = null
        var closeError: String? = null
        val iterations = mutableListOf<BackendComparisonIteration>()
        try {
            cancellation.checkpoint()
            unavailable(backend)?.let { throw BackendComparisonUnavailable(it) }
            stage = "load"
            progress(stage)
            val loadStarted = clock()
            try {
                session = create(backend)
                session.load()
            } finally { loadMs = elapsed(loadStarted) }
            cancellation.checkpoint()
            val opened = checkNotNull(session)
            repeat(plan.warmups + plan.repetitions) { index ->
                cancellation.checkpoint()
                val warmup = index < plan.warmups
                val number = if (warmup) index + 1 else index - plan.warmups + 1
                val timings = linkedMapOf<String, Double>()
                var output: BackendComparisonOutput? = null
                fun <T> step(name: String, announce: Boolean = true, operation: () -> T): T {
                    stage = name
                    if (announce) progress("${if (warmup) "预热" else "测量"}$number · $name")
                    val began = clock()
                    return try { operation() } finally { timings[name] = elapsed(began) }
                }
                try {
                    step("write") { opened.write(input) }
                    cancellation.checkpoint()
                    progress("${if (warmup) "预热" else "测量"}$number · infer/readback")
                    val invoked = clock()
                    val values = try {
                        step("infer", announce = false) { opened.infer() }
                        // No UI/progress callback between enqueue and blocking read: otherwise GPU
                        // work during publication would disappear from the reported completion time.
                        // Always drain/read before honoring cancellation after run() returned.
                        step("readback", announce = false) { opened.readback() }
                    } finally { timings["infer+readback"] = elapsed(invoked) }
                    cancellation.checkpoint()
                    output = step("validate") { validateComparisonOutput(values, expectedOutput) }
                } finally {
                    iterations += BackendComparisonIteration(number, warmup, timings.toMap(), output)
                }
            }
            cancellation.checkpoint()
            stage = "complete"
        } catch (cancelled: CancellationException) {
            cancellation.cancel(cancelled.message ?: "已取消")
            status = ComparisonStatus.CANCELLED
            detail = cancellation.reason ?: "已取消"
        } catch (skipped: BackendComparisonUnavailable) {
            status = ComparisonStatus.SKIPPED
            detail = skipped.message ?: "此后端不可用"
        } catch (failure: Throwable) {
            status = ComparisonStatus.FAILED
            detail = comparisonFailure(failure)
            if (failure is OutOfMemoryError) cancellation.cancel("内存不足，剩余后端未执行")
        } finally {
            session?.let { owned ->
                runCatching { progress("close") }
                val began = clock()
                try { owned.close() } catch (failure: Throwable) {
                    closeError = comparisonFailure(failure)
                    cancellation.cancel("资源释放失败，剩余后端未执行")
                    // A cleanup error must never appear as a successful comparison.
                    if (status == ComparisonStatus.COMPLETE) {
                        status = ComparisonStatus.FAILED
                        stage = "close"
                        detail = closeError
                    }
                } finally { closeMs = elapsed(began) }
            }
        }
        return BackendComparisonResult(backend, status, stage, detail, loadMs, closeMs,
            iterations.toList(), session?.details ?: "未加载", closeError)
    }

    private fun elapsed(start: Long): Double = (clock() - start).coerceAtLeast(0) / 1e6
}

internal fun validateComparisonOutput(values: FloatArray, expected: Int): BackendComparisonOutput {
    require(values.size == expected && expected > 0) { "输出数量不符：期望 $expected，实际 ${values.size}" }
    var low = Float.POSITIVE_INFINITY
    var high = Float.NEGATIVE_INFINITY
    for (value in values) {
        require(value.isFinite()) { "输出包含 NaN/Infinity" }
        low = minOf(low, value)
        high = maxOf(high, value)
    }
    return BackendComparisonOutput(values.size, low, high)
}

internal fun comparisonInput(size: Int): FloatArray {
    require(size in 1..640)
    val values = FloatArray(size * size * 3)
    for (pixel in 0 until size * size) {
        val x = pixel % size
        val y = pixel / size
        values[pixel * 3] = ((x * 17 + y * 3) and 255) * (1f / 255f)
        values[pixel * 3 + 1] = ((x * 5 + y * 11) and 255) * (1f / 255f)
        values[pixel * 3 + 2] = ((x + y * 7) and 255) * (1f / 255f)
    }
    return values
}

/** Explicit little-endian float bytes make the fingerprint stable across Android/JVM ABIs. */
internal fun comparisonInputHash(values: FloatArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val block = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
    for (value in values) {
        require(value.isFinite())
        if (block.remaining() < 4) {
            digest.update(block.array(), 0, block.position())
            block.clear()
        }
        block.putFloat(value)
    }
    digest.update(block.array(), 0, block.position())
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun comparisonMedian(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    require(values.all { it.isFinite() && it >= 0 })
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1] / 2 + sorted[middle] / 2
}

internal fun comparisonMs(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
internal fun comparisonFailure(failure: Throwable): String = "${failure.javaClass.simpleName}: ${failure.message}".take(1000)
