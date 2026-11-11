package com.example.minimalapk.plugins

import android.content.Context
import com.example.minimalapk.litert.lm.LocalTextCancellation
import com.example.minimalapk.litert.lm.LocalTextCompileCache
import com.example.minimalapk.litert.lm.LocalTextModels
import com.example.minimalapk.litert.lm.LocalTextOptions
import com.example.minimalapk.litert.lm.LocalTextReadiness
import com.example.minimalapk.litert.lm.LocalTextRunner
import com.example.minimalapk.models.AssetResourceStore
import java.util.Locale
import java.util.concurrent.CancellationException

/** Text-only camera report. No image subscription, tool registration, or code execution. */
internal class LocalTextPlugin(application: Context) : CameraPlugin {
    private val application = application.applicationContext
    override val descriptor = PluginDescriptor(
        ID, "本地文本建议", setOf(PluginCapability.TEXT, PluginCapability.ACTIONS, PluginCapability.LIFECYCLE,
            PluginCapability.MODEL_ASSETS),
        actions = setOf("generate", "cancel"),
    )

    private data class Work(
        val context: PluginContext,
        val options: LocalTextOptions,
        val cancellation: LocalTextCancellation,
        val generate: Boolean = true,
    )

    private val stateLock = Any()
    private var current: Work? = null
    private var closed = false
    private val runner = LocalTextRunner(this.application)
    private val resources = AssetResourceStore.get(this.application)
    private val worker = BoundedFrameWorker<Work>(
        "LocalTextOwner",
        failed = { work, error ->
            if (error !is CancellationException) work.cancellation.deliver {
                publish(work.context, error.message ?: "本地文本生成失败", error = true)
            }
        },
    ) { work ->
        work.cancellation.check()
        if (!work.context.isCurrent()) return@BoundedFrameWorker
        if (!work.generate) {
            checkReadiness(work)
            return@BoundedFrameWorker
        }
        var lastPublish = 0L
        val result = runner.generate(work.options, work.cancellation) { text ->
            if (!work.context.isCurrent()) {
                work.cancellation.cancel()
            } else {
                // Native tokens can arrive faster than camera reports need to be recomposed.
                val now = System.nanoTime()
                if (lastPublish == 0L || now - lastPublish >= 100_000_000L) {
                    lastPublish = now
                    publish(work.context, "生成中 · Qwen2.5-Coder · CPU（请求后端）", text, running = true)
                }
            }
        }
        work.cancellation.deliver {
            val first = result.firstTextMs?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—"
            publish(work.context,
                "CPU（请求后端；节点放置不可测） · 首字 $first ms · 总计 ${String.format(Locale.ROOT, "%.0f", result.elapsedMs)} ms · " +
                    "输入+输出 ${result.totalTokens} tokens · 本次引擎已关闭",
                result.text,
                execution = PluginExecution(result.elapsedMs, "CPU 请求；原生节点放置不可测", "文本校验+加载+生成（含首字 $first ms）"),
            )
        }
    }

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> {
                cancel()
                showReadiness(context)
            }
            is PluginEvent.Action -> when (event.id) {
                "generate" -> start(context)
                "cancel" -> {
                    cancel()
                    publish(context, "已取消；后续 token 不再显示，原生清理完成后可重新生成")
                }
            }
            is PluginEvent.Stopped -> cancel()
            is PluginEvent.Lifecycle -> if (event.state in setOf(
                    CameraLifecycle.BACKGROUND, CameraLifecycle.LEAVE, CameraLifecycle.STOP,
                )) cancel()
            else -> Unit
        }
    }

    private fun showReadiness(context: PluginContext) {
        val work = Work(context, LocalTextOptions.from(context.settings.config), LocalTextCancellation(), generate = false)
        synchronized(stateLock) {
            if (closed) return
            current = work
        }
        worker.offer(work)
    }

    private fun checkReadiness(work: Work) {
        work.options.prepare()
        val set = LocalTextModels.assetSets(application).single()
        // Recovery/readiness uses the shared writer barrier and belongs on the worker, not UI.
        val ready = resources.installedDirectory(set) != null
        work.cancellation.check()
        if (!ready) {
            work.cancellation.deliver {
                publish(work.context, "请到 插件 → 自定义模型 → 模型资源目录 下载 Qwen2.5-Coder-1.5B（1.04 GiB）；配置输入后点击生成", error = true)
            }
            return
        }
        val reasons = LocalTextReadiness.reasons(LocalTextReadiness.device(application)) +
            if (application.noBackupFilesDir.usableSpace < LocalTextCompileCache.REQUIRED_FREE_BYTES)
                listOf("每次文本生成需要至少 1536 MiB 可用存储来共享编译权重缓存") else emptyList()
        work.cancellation.deliver {
            publish(work.context, if (reasons.isEmpty()) "已安装 · 每次生成重新校验文件并创建独立引擎 · 配置文字后点击生成"
                else reasons.joinToString("；"), error = reasons.isNotEmpty())
        }
    }

    private fun start(context: PluginContext) {
        val options = LocalTextOptions.from(context.settings.config)
        options.prepare() // Invalid edits do not interrupt a valid in-flight request.
        cancel()
        val work = Work(context, options, LocalTextCancellation())
        synchronized(stateLock) {
            if (closed) return
            current = work
        }
        work.cancellation.deliver {
            publish(context, "正在校验并加载 Qwen2.5-Coder · CPU · 输出上限 ${options.maxOutputTokens} tokens", running = true)
        }
        worker.offer(work)
    }

    private fun cancel() {
        // Never hold stateLock while waiting for a callback's publish to return.
        val work = synchronized(stateLock) { current.also { current = null } }
        work?.cancellation?.cancel()
        worker.clear()
    }

    private fun publish(
        context: PluginContext,
        status: String,
        text: String = "",
        running: Boolean = false,
        error: Boolean = false,
        execution: PluginExecution? = null,
    ) {
        if (!context.isCurrent()) return
        val messages = buildList {
            add(PluginMessage(status, if (error) MessageKind.ERROR else MessageKind.STATUS))
            // Host accepts eight messages of 240 UTF-16 chars. Use its existing protocol without
            // silently discarding the answer tail or splitting an emoji at the per-message bound.
            localTextReportParts(text).forEach { add(PluginMessage(it)) }
        }
        context.publish(PluginOutput(
            System.nanoTime(), messages = messages,
            actions = listOf(PluginAction("generate", "生成配置文本", enabled = !running),
                PluginAction("cancel", "取消生成", enabled = running)),
            execution = execution,
        ))
    }

    override fun close() {
        synchronized(stateLock) { closed = true }
        cancel()
        // shutdown() is non-blocking. The owner drains native cancellation and closes both
        // Conversation and Engine on its own thread; never close a live model from the UI.
        worker.close()
    }

    companion object {
        const val ID = "local_text"
    }
}
