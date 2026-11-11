package com.example.minimalapk.litert.lm

import android.content.Context
import com.example.minimalapk.models.AssetLease
import com.example.minimalapk.models.AssetResourceStore
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

internal data class LocalTextResult(
    val text: String,
    /** Native KV count includes input and output; it is not the number of callback chunks. */
    val totalTokens: Int,
    val initializationMs: Double,
    val firstTextMs: Double?,
    val elapsedMs: Double,
)

/**
 * One invocation owns one freshly initialized native Engine and one Conversation. No conversation,
 * KV state, recurrent cache, or compiled cache survives a request. XNNPACK packed weights share
 * a private request cache WITHIN the Engine; :nocache duplicates weights per signature and
 * measured >7 GiB. :memory is unavailable on API24/kernel3.10 (memfd_create ENOSYS); use
 * private file scratch on all ABIs, never silently fall back. Run on a background owner.
 * Cancellation gates output immediately; the owner asks native code to stop and waits for its
 * terminal callback before closing. Synchronous native initialization itself is not interruptible.
 */
internal class LocalTextRunner(
    private val application: Context,
    private val resources: AssetResourceStore = AssetResourceStore.get(application),
) {
    @OptIn(ExperimentalApi::class)
    fun generate(
        options: LocalTextOptions,
        cancellation: LocalTextCancellation,
        onText: (String) -> Unit,
    ): LocalTextResult {
        val request = options.prepare()
        cancellation.check()
        check(nativePermit.tryAcquire()) { "前一个本地文本引擎仍在运行或清理，请稍后重试" }
        val started = System.nanoTime()
        var engine: Engine? = null
        var lease: AssetLease? = null
        var compileCache: LocalTextCompileCache? = null
        try {
            val set = LocalTextModels.assetSets(application).single()
            lease = resources.acquire(set)
            val file = File(lease.directory, LocalTextModels.FILE_NAME)
            cancellation.check()
            LocalTextReadiness.requireReady(application)
            LocalTextModels.verifyFile(file, cancellation)
            LocalTextReadiness.requireReady(application)
            cancellation.check()
            compileCache = LocalTextCompileCache.create(File(application.noBackupFilesDir, "local_text_compile"))
            engine = Engine(EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU(threadCount = 2),
                maxNumTokens = LocalTextModels.CONTEXT_TOKENS,
                cacheDir = compileCache.directory.absolutePath,
            ))
            engine.initialize()
            compileCache.requirePackedWeights()
            val initializationMs = elapsedMs(started)
            cancellation.check()
            engine.createConversation(ConversationConfig(
                // This bundle embeds the vendor system turn inside its user prefix; adding an
                // explicit systemInstruction would duplicate it. Do not wrap input in ChatML.
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                automaticToolCalling = false,
                maxOutputToken = request.maxOutputTokens,
            )).use { conversation ->
                val rendered = conversation.renderPrefaceIntoString() +
                    conversation.renderMessageIntoString(Message.user(request.prompt))
                check(rendered == QwenCoderTemplate.renderedSingleTurn(request.prompt)) {
                    "原生模型模板与固定 Qwen 单轮配方不符；拒绝用未知模板生成"
                }
                cancellation.check()
                val done = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                val firstTextNanos = AtomicLong(0L)
                val stream = LocalTextStream(cancellation) { text ->
                    firstTextNanos.compareAndSet(0L, System.nanoTime())
                    onText(text)
                }
                conversation.sendMessageAsync(
                    request.prompt,
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (failure.get() != null || cancellation.isCancelled) return
                            try {
                                check(message.toolCalls.isEmpty() && message.channels.isEmpty()) {
                                    "此纯文本配方返回了未支持的工具或思考通道"
                                }
                                val text = message.contents.contents.joinToString("") { content ->
                                    check(content is Content.Text) { "此文本引擎只接受文字输出" }
                                    content.text
                                }
                                stream.append(text)
                            } catch (error: Throwable) {
                                failure.compareAndSet(null, error)
                            }
                        }

                        override fun onDone() { done.countDown() }

                        override fun onError(throwable: Throwable) {
                            failure.compareAndSet(null, throwable)
                            done.countDown()
                        }
                    },
                    maxOutputToken = request.maxOutputTokens,
                )
                var cancelSent = false
                var interrupted = false
                // Native callbacks are terminal only after completion/cancellation. Do not free
                // a live Conversation merely because a UI token was invalidated or time elapsed.
                while (true) {
                    try {
                        if (done.await(25, TimeUnit.MILLISECONDS)) break
                    } catch (_: InterruptedException) {
                        interrupted = true
                        cancellation.cancel()
                    }
                    if (elapsedMs(started) >= REQUEST_TIMEOUT_MS && failure.get() == null && !cancellation.isCancelled) {
                        failure.compareAndSet(null, TimeoutException("本地文本处理超过 120 秒，已请求原生停止"))
                    }
                    if (!cancelSent && (cancellation.isCancelled || failure.get() != null)) {
                        cancelSent = true
                        conversation.cancelProcess()
                    }
                }
                if (interrupted) Thread.currentThread().interrupt()
                cancellation.check()
                failure.get()?.let { throw it }
                val text = stream.finish()
                check(text.isNotBlank()) { "模型没有返回文字" }
                check(QwenCoderTemplate.stopText.none { it in text }) { "模型返回了未处理的结束标记" }
                return LocalTextResult(
                    text, conversation.getTokenCount(), initializationMs,
                    firstTextNanos.get().takeIf { it != 0L }?.let { (it - started) / 1_000_000.0 }, elapsedMs(started),
                )
            }
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("LiteRT-LM ${LocalTextModels.RUNTIME_VERSION} JNI 不可用：${error.message}", error)
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("本地文本原生/进程内存不足，请关闭其它模型后重试", error)
        } finally {
            try {
                // initialize() failure leaves Engine uninitialized; close() would mask its cause.
                if (engine?.isInitialized() == true) engine.close()
            } finally {
                try { compileCache?.close() } finally {
                    try { lease?.close() } finally { nativePermit.release() }
                }
            }
        }
    }

    private fun elapsedMs(start: Long): Double = (System.nanoTime() - start) / 1_000_000.0

    companion object {
        private const val REQUEST_TIMEOUT_MS = 120_000.0
        private val nativePermit = Semaphore(1)
    }
}
