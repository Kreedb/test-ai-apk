package com.example.minimalapk.litert.lm

import java.util.concurrent.CancellationException

/** Cancellation and publishing share a lock: after cancel returns, no old text can be delivered. */
internal class LocalTextCancellation {
    private val lock = Any()
    @Volatile var isCancelled: Boolean = false
        private set

    fun cancel() = synchronized(lock) { isCancelled = true }

    fun check() {
        if (isCancelled || Thread.currentThread().isInterrupted) throw CancellationException("本地文本请求已取消")
    }

    fun deliver(action: () -> Unit): Boolean = synchronized(lock) {
        if (isCancelled) return@synchronized false
        action()
        true
    }
}

/** Bounded cumulative text, including correct handling when JNI chunks split a UTF-16 pair. */
internal class LocalTextStream(
    private val cancellation: LocalTextCancellation,
    private val maxChars: Int = 16_384,
    private val output: (String) -> Unit,
) {
    private val text = StringBuilder()
    private var pendingSurrogate = ""

    fun append(chunk: String): Boolean = cancellation.deliver {
        require(text.length.toLong() + pendingSurrogate.length + chunk.length <= maxChars) {
            "模型输出超过文本显示上限"
        }
        val joined = pendingSurrogate + chunk
        pendingSurrogate = if (joined.lastOrNull()?.isHighSurrogate() == true) joined.takeLast(1) else ""
        val complete = joined.dropLast(pendingSurrogate.length)
        strictUtf8Bytes(complete)
        if (complete.isNotEmpty()) {
            text.append(complete)
            output(text.toString())
        }
    }

    fun finish(): String {
        cancellation.check()
        require(pendingSurrogate.isEmpty()) { "模型输出包含不完整的 Unicode 字符" }
        return text.toString()
    }
}
