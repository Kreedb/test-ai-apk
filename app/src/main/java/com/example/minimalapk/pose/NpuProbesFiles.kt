package com.example.minimalapk.pose

import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8

internal sealed interface ProbeRead {
    data class Content(val text: String, val truncated: Boolean = false) : ProbeRead
    data object Missing : ProbeRead
    data object PermissionDenied : ProbeRead
    data class Failure(val message: String, val state: NpuProbeState = NpuProbeState.PARSE_ERROR) : ProbeRead
}

internal data class ProbeDirectories(
    val paths: List<String> = emptyList(),
    val state: NpuProbeState = NpuProbeState.OK,
    val detail: String? = null,
)

internal data class ProbeEvidence(
    val path: String,
    val state: NpuProbeState,
    val timestampNanos: Long,
    /** Exact text, including whitespace/newlines; null means that no content was read. */
    val text: String? = null,
    val detail: String? = null,
)

internal interface ProbeFileSystem {
    fun read(path: String): ProbeRead
    fun listDirectories(parent: String): List<String>
    fun scanDirectories(parent: String): ProbeDirectories = ProbeDirectories(listDirectories(parent))
}

internal object RealProbeFileSystem : ProbeFileSystem {
    override fun read(path: String): ProbeRead = try {
        val file = File(path)
        when {
            !file.exists() -> if (hasUnreadableParent(file)) ProbeRead.PermissionDenied else ProbeRead.Missing
            !file.canRead() -> ProbeRead.PermissionDenied
            else -> file.inputStream().use(::readProbeContent)
        }
    } catch (error: Exception) {
        if (isPermissionFailure(error)) ProbeRead.PermissionDenied
        else ProbeRead.Failure(error.message ?: "读取失败")
    }

    override fun listDirectories(parent: String): List<String> = scanDirectories(parent).paths

    override fun scanDirectories(parent: String): ProbeDirectories = try {
        val directory = File(parent)
        when {
            !directory.exists() -> ProbeDirectories(state = if (hasUnreadableParent(directory))
                NpuProbeState.PERMISSION_DENIED else NpuProbeState.MISSING)
            !directory.canRead() -> ProbeDirectories(state = NpuProbeState.PERMISSION_DENIED)
            else -> {
                val children = directory.listFiles()
                if (children == null) ProbeDirectories(state = NpuProbeState.PERMISSION_DENIED, detail = "目录枚举失败")
                else {
                    val paths = children.asSequence().filter { it.isDirectory }
                        .take(MAX_PROBE_DIRECTORIES + 1).map { it.absolutePath }.toList()
                    ProbeDirectories(paths.take(MAX_PROBE_DIRECTORIES),
                        if (paths.size > MAX_PROBE_DIRECTORIES) NpuProbeState.TRUNCATED else NpuProbeState.OK,
                        if (paths.size > MAX_PROBE_DIRECTORIES) "仅扫描前 $MAX_PROBE_DIRECTORIES 个目录" else null)
                }
            }
        }
    } catch (error: Exception) {
        ProbeDirectories(state = if (isPermissionFailure(error)) NpuProbeState.PERMISSION_DENIED
            else NpuProbeState.PARSE_ERROR, detail = error.message)
    }

    private fun hasUnreadableParent(file: File): Boolean {
        var parent = file.parentFile
        while (parent != null) {
            if (parent.exists()) return !parent.canRead() || !parent.canExecute()
            parent = parent.parentFile
        }
        return false
    }

    private fun isPermissionFailure(error: Exception): Boolean = error is SecurityException ||
        listOf("EACCES", "EPERM", "Permission denied").any { error.message?.contains(it, true) == true }
}

/** Read through short reads, and check one extra byte. A prefix is never reported as a complete file. */
internal fun readProbeContent(input: InputStream): ProbeRead.Content {
    val bytes = ByteArray(MAX_PROBE_BYTES + 1)
    var count = 0
    while (count < bytes.size) {
        val read = input.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) {
            val byte = input.read()
            if (byte < 0) break
            bytes[count++] = byte.toByte()
        } else count += read
    }
    return ProbeRead.Content(String(bytes, 0, minOf(count, MAX_PROBE_BYTES), UTF_8), count > MAX_PROBE_BYTES)
}

/** Per-run budgets. A blocking kernel read cannot be preempted safely, so callers run this off
 * the camera/UI thread; the deadline prevents further I/O after that read returns. */
internal class ProbeRunFileSystem(
    private val delegate: ProbeFileSystem,
    private val clock: () -> Long = System::nanoTime,
    private val stillNeeded: () -> Boolean = { true },
) {
    val evidence = mutableListOf<ProbeEvidence>()
    private val started = clock()
    private var operations = 0
    private var capturedBytes = 0
    private val reads = mutableMapOf<String, Pair<ProbeRead, ProbeEvidence>>()
    private val directories = mutableMapOf<String, Pair<List<String>, ProbeEvidence>>()

    private fun limit(): ProbeRead.Failure? = when {
        !stillNeeded() -> ProbeRead.Failure("监控已停止或配置变化，后续读取已取消", NpuProbeState.CANCELLED)
        clock() - started >= MAX_RUN_NANOS -> ProbeRead.Failure("探测超过 200 ms；后续读取已停止", NpuProbeState.TIMEOUT)
        operations++ >= MAX_RUN_OPERATIONS -> ProbeRead.Failure("探测达到 $MAX_RUN_OPERATIONS 次操作上限", NpuProbeState.TRUNCATED)
        capturedBytes + MAX_PROBE_BYTES > MAX_RUN_BYTES -> ProbeRead.Failure("原文达到 $MAX_RUN_BYTES 字节预算；后续读取已停止", NpuProbeState.TRUNCATED)
        else -> null
    }

    fun read(path: String): ProbeRead {
        reads[path]?.let { (read, record) -> evidence += record; return read }
        val read = limit() ?: try { delegate.read(path) } catch (error: Exception) {
            if (error is SecurityException) ProbeRead.PermissionDenied else ProbeRead.Failure(error.message ?: "读取失败")
        }
        val bounded = if (read is ProbeRead.Content) {
            val bytes = read.text.toByteArray(UTF_8)
            if (bytes.size > MAX_PROBE_BYTES) ProbeRead.Content(String(bytes, 0, MAX_PROBE_BYTES, UTF_8), true) else read
        } else read
        val state = when (bounded) {
            is ProbeRead.Content -> if (bounded.truncated) NpuProbeState.TRUNCATED else NpuProbeState.OK
            ProbeRead.Missing -> NpuProbeState.MISSING
            ProbeRead.PermissionDenied -> NpuProbeState.PERMISSION_DENIED
            is ProbeRead.Failure -> bounded.state
        }
        val text = (bounded as? ProbeRead.Content)?.text
        capturedBytes += text?.toByteArray(UTF_8)?.size ?: 0
        val detail = when (bounded) {
            is ProbeRead.Content -> if (bounded.truncated) "节点超过 $MAX_PROBE_BYTES 字节；仅保存前缀，不解析利用率" else null
            is ProbeRead.Failure -> bounded.message
            else -> null
        }
        val record = ProbeEvidence(path, state, clock(), text, detail)
        evidence += record
        val result = if (bounded is ProbeRead.Content && bounded.truncated)
            ProbeRead.Failure(checkNotNull(detail), NpuProbeState.TRUNCATED) else bounded
        reads[path] = result to record
        return result
    }

    fun listDirectories(parent: String): List<String> {
        directories[parent]?.let { (paths, record) -> evidence += record; return paths }
        val limited = limit()
        val scan = if (limited != null) ProbeDirectories(state = limited.state, detail = limited.message)
        else try { delegate.scanDirectories(parent) } catch (error: Exception) {
            ProbeDirectories(state = if (error is SecurityException) NpuProbeState.PERMISSION_DENIED else NpuProbeState.PARSE_ERROR,
                detail = error.message)
        }
        val paths = scan.paths.take(MAX_PROBE_DIRECTORIES)
        val clipped = scan.paths.size > paths.size
        val record = ProbeEvidence(parent, if (clipped) NpuProbeState.TRUNCATED else scan.state, clock(),
            detail = "目录发现 ${paths.size} 项" + (scan.detail?.let { "；$it" } ?: "") +
                (if (clipped) "；仅扫描前 $MAX_PROBE_DIRECTORIES 项" else ""))
        evidence += record
        directories[parent] = paths to record
        return paths
    }
}

internal const val MAX_PROBE_BYTES = 4096
internal const val MAX_PROBE_DIRECTORIES = 64
private const val MAX_RUN_OPERATIONS = 256
private const val MAX_RUN_BYTES = 131072
private const val MAX_RUN_NANOS = 200_000_000L
