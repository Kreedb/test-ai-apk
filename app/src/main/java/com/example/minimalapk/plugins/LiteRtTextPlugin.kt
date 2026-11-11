package com.example.minimalapk.plugins

import android.content.Context
import com.example.minimalapk.litert.text.LayaDecisionEngine
import com.example.minimalapk.litert.text.LayaDecisionResult
import com.example.minimalapk.litert.text.LayaQuestion
import com.example.minimalapk.litert.text.TextDecisionEngine
import com.example.minimalapk.litert.text.TextDecisionRequest
import com.example.minimalapk.litert.text.TextDecisionResult
import com.example.minimalapk.litert.text.TextDecisionTasks
import com.example.minimalapk.litert.text.validateTextBudget
import java.util.Locale

internal const val LITERT_LAYA_TASK = "text-decision-laya-multilingual"

internal enum class LiteRtLayaMode(val key: String, val label: String) {
    CHOICE("choice", "多选 · choice"),
    SCORE("score", "有序等级评分 · score"),
    YES_NO("noul", "是 / 否概率 · yes/no"),
}

/** Ordinary choices keep their original keys/descriptions; score levels use a separate setting. */
internal fun liteRtTextRequest(settings: PluginSettings): TextDecisionRequest {
    require(settings.config["choices"].orEmpty().length <= 4096) { "选项文本最多 4096 字" }
    val options = TextDecisionRequest.parseOptions(settings.config["choices"].orEmpty().lines())
    require(options.size in 2..12) { "请填写 2–12 个不同选项，每行 key: 说明" }
    require(options.all { it.first.length <= 32 && it.second.length <= 256 }) { "选项 key 最多 32 字，说明最多 256 字" }
    return TextDecisionRequest(settings.config["input"].orEmpty(), settings.config["question"].orEmpty(), options)
}

internal sealed interface LiteRtTextRunRequest {
    val text: String
    val instructions: String

    data class Choice(val value: TextDecisionRequest) : LiteRtTextRunRequest {
        override val text: String get() = value.text
        override val instructions: String get() = value.question
    }

    data class Laya(override val text: String, val question: LayaQuestion) : LiteRtTextRunRequest {
        override val instructions: String get() = question.instructions
    }
}

/** Validate editable configuration before submitting work or loading a model. */
internal fun liteRtTextRunRequest(settings: PluginSettings): LiteRtTextRunRequest {
    val task = LiteRtScenes.task(settings, LiteRtScenes.text)
    require(task in TextDecisionTasks.ids) { "不支持的文本任务：$task" }
    val modeKey = settings.config["laya_mode"] ?: LiteRtLayaMode.CHOICE.key
    val mode = requireNotNull(LiteRtLayaMode.entries.firstOrNull { it.key == modeKey }) {
        "未知 Laya 决策模式：$modeKey；请选择多选、评分或是/否"
    }
    require(task == LITERT_LAYA_TASK || mode == LiteRtLayaMode.CHOICE) {
        "评分与是/否模式仅适用于 Laya；当前模型请选择多选"
    }
    val text = settings.config["input"].orEmpty()
    val instructions = settings.config["question"].orEmpty()
    require(text.isNotBlank() && text.length <= 4096) { "请输入 1–4096 字的文本" }
    require(instructions.isNotBlank() && instructions.length <= 1024) { "请输入 1–1024 字的问题或陈述" }
    if (task != LITERT_LAYA_TASK) return LiteRtTextRunRequest.Choice(liteRtTextRequest(settings))
    val question = when (mode) {
        LiteRtLayaMode.CHOICE -> LayaQuestion.choice(instructions, liteRtTextRequest(settings).options.toMap(LinkedHashMap()))
        LiteRtLayaMode.SCORE -> {
            val rows = settings.config["laya_levels"].orEmpty()
            require(rows.length <= 4096) { "等级文本最多 4096 字" }
            val levels = rows.lines().map(String::trim).filter(String::isNotEmpty)
            require(levels.size in 2..12 && levels.distinct().size == levels.size) {
                "请按从低到高填写 2–12 个不同等级，每行一个说明（索引从 0 开始）"
            }
            require(levels.all { it.length <= 256 }) { "每个等级说明最多 256 字" }
            LayaQuestion.score(instructions, levels)
        }
        LiteRtLayaMode.YES_NO -> LayaQuestion.yesNo(instructions)
    }
    question.validate()
    validateTextBudget(listOf(text, instructions) + question.renderedOptions())
    return LiteRtTextRunRequest.Laya(text, question)
}

internal data class LiteRtTextReport(val messages: List<PluginMessage>, val execution: PluginExecution? = null)

/**
 * Token-count/encoding errors are request failures, not broken native models. Catch them inside
 * LiteRtWork's operation so AsyncModelSlot keeps the healthy engine. Cancellation and actual
 * model failures still propagate to the existing owner/generation handling.
 */
internal fun liteRtTextRun(request: LiteRtTextRunRequest, engine: TextDecisionEngine): LiteRtTextReport = try {
    when (request) {
        is LiteRtTextRunRequest.Choice -> liteRtTextReport(request, engine.decide(request.value))
        is LiteRtTextRunRequest.Laya -> {
            val laya = checkNotNull(engine as? LayaDecisionEngine) { "当前引擎不支持 Laya 类型化决策；请检查所选模型" }
            liteRtLayaReport(request, laya.decide(request.text, request.question))
        }
    }
} catch (error: IllegalArgumentException) {
    LiteRtTextReport(listOf(PluginMessage(liteRtTextBounded(error.message ?: "请检查文本、问题与选项"), MessageKind.ERROR)))
}

private fun liteRtTextReport(request: LiteRtTextRunRequest.Choice, result: TextDecisionResult): LiteRtTextReport =
    liteRtTextReport(request, "建议选项：${result.answerKey}（不执行命令）",
        result.probabilities.map { (key, p) -> "$key：${liteRtTextPercent(p.toDouble())}" },
        result.ms, result.backend, result.window, result.details, result.fallbackReason)

private fun liteRtLayaReport(request: LiteRtTextRunRequest.Laya, result: LayaDecisionResult): LiteRtTextReport {
    val answer = result.answer
    val question = request.question
    check(answer["type"] == question.type) { "Laya 返回的类型与请求不符" }
    fun number(value: Any?): Double = checkNotNull(value as? Number) { "Laya 返回了无效数值" }.toDouble().also {
        check(it.isFinite()) { "Laya 返回了非有限数值" }
    }
    fun percent(value: Any?): String = liteRtTextPercent(number(value))
    val summary: String
    val rows: List<String>
    if (question.type == LiteRtLayaMode.YES_NO.key) {
        val yes = number(answer["noul"])
        summary = "是 / true 概率：${liteRtTextPercent(yes)}（连续概率）"
        rows = listOf("否 / false：${liteRtTextPercent(1.0 - yes)}", "是 / true：${liteRtTextPercent(yes)}")
    } else {
        val probabilities = checkNotNull(answer["probabilities"] as? Map<*, *>) { "Laya 缺少选项概率" }
        val labels: Map<String, String>
        if (question.type == LiteRtLayaMode.SCORE.key) {
            val levels = question.criteria as List<*>
            val score = number(answer["score"])
            check(score >= 0.0 && score <= levels.lastIndex + 0.0001) { "Laya 返回的期望等级越界" }
            summary = String.format(Locale.ROOT, "期望等级：%.4f（0–%d，可为小数）", score, levels.lastIndex)
            labels = levels.indices.associate { it.toString() to "$it：${levels[it]}" }
        } else {
            val keys = (question.criteria as Map<*, *>).keys.map { it as String }
            val choice = checkNotNull(answer["choice"] as? String) { "Laya 缺少选中选项" }
            check(choice in keys) { "Laya 返回了请求以外的选项" }
            summary = "建议选项：$choice（不执行命令）"
            labels = keys.associateWith { it }
        }
        check(probabilities.keys == labels.keys) { "Laya 返回的概率与选项/等级不符" }
        // Preserve request/ordinal order; never turn a score into an argmax label or renormalize
        // Laya's four-decimal probabilities (their sum can differ slightly from one).
        rows = labels.map { (key, label) -> "$label — ${percent(probabilities[key])}" }
    }
    val action = checkNotNull(answer["action"] as? Map<*, *>) { "Laya 缺少独立动作头结果" }
    val notice = "Laya 已按类型/选项数温度校准；模型置信度 ${percent(answer["confidence"])}；" +
        "独立 action head act_probability ${percent(action["act_probability"])}（仅展示，不执行动作）"
    return liteRtTextReport(request, summary, rows, result.ms, result.backend, result.window,
        result.details, result.fallbackReason, notice)
}

private fun liteRtTextPercent(value: Double): String {
    check(value.isFinite() && value in 0.0..1.0) { "模型返回了无效概率" }
    return String.format(Locale.ROOT, "%.2f%%", value * 100)
}

private fun liteRtTextBounded(text: String, limit: Int = PluginHost.MAX_MESSAGE_CHARS): String {
    if (text.length <= limit) return text
    var end = limit - 1
    if (end > 0 && text[end - 1].isHighSurrogate()) end--
    return text.take(end) + "…"
}

private fun liteRtTextReport(
    request: LiteRtTextRunRequest,
    summary: String,
    rows: List<String>,
    ms: Double,
    backend: String,
    window: Int,
    details: String,
    fallbackReason: String?,
    notice: String? = null,
): LiteRtTextReport {
    val messages = buildList {
        add(PluginMessage(summary))
        add(PluginMessage(liteRtTiming(ms, backend), MessageKind.STATUS))
        add(PluginMessage("问题：${liteRtTextBounded(request.instructions, 100)}\n输入：${liteRtTextBounded(request.text, 120)}", MessageKind.STATUS))
        notice?.let { add(PluginMessage(it, MessageKind.STATUS)) }
        val table = rows.joinToString("\n")
        val tableSlots = if (notice == null) 3 else 2
        // Leave one code unit per boundary so keeping surrogate pairs intact cannot add a slot.
        val tableLimit = tableSlots * (PluginHost.MAX_MESSAGE_CHARS - 1)
        val visible = if (table.length <= tableLimit) table else
            liteRtTextBounded(table, tableLimit - 16) + "\n（其余选项/等级已截断）"
        var start = 0
        while (start < visible.length) {
            var end = minOf(start + PluginHost.MAX_MESSAGE_CHARS, visible.length)
            if (end < visible.length && visible[end - 1].isHighSurrogate()) end--
            add(PluginMessage(visible.substring(start, end)))
            start = end
        }
        add(PluginMessage("模型窗口 $window tokens · $details", MessageKind.STATUS))
        fallbackReason?.let { add(PluginMessage("CPU 回退：$it", MessageKind.STATUS)) }
    }
    return LiteRtTextReport(messages.take(PluginHost.MAX_MESSAGES).map { it.copy(text = liteRtTextBounded(it.text)) },
        PluginExecution(ms, backend))
}

internal class LiteRtTextPlugin(application: Context) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.LITERT_TEXT, "文本决策", setOf(PluginCapability.TEXT, PluginCapability.ACTIONS, PluginCapability.MODEL_ASSETS),
        actions = setOf("run", "cancel", "retry"),
    )
    private val scene = LiteRtScenes.text
    private val assets = LiteRtAssets(application)
    private val actions = listOf(PluginAction("run", "运行文本决策"), PluginAction("cancel", "取消"), PluginAction("retry", "重新加载模型"))
    private var activeToken: PluginToken? = null
    private val work = LiteRtWork<LiteRtInstalledModel<TextDecisionEngine>>(
        "LiteRtText",
        open = { context ->
            assets.open(context, scene) { task, directory, backend -> TextDecisionTasks.create(task, directory, backend) }
        },
        status = ::status,
        ready = { status(it, "已就绪；点击运行，结果只展示，不执行命令", false) },
    )

    private fun status(context: PluginContext, text: String, error: Boolean) {
        context.publish(PluginOutput(System.nanoTime(),
            listOf(PluginMessage(liteRtTextBounded(text), if (error) MessageKind.ERROR else MessageKind.STATUS)), actions = actions))
    }

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> { activeToken = context.token; work.start(context, deferLoad = true) }
            is PluginEvent.Stopped -> if (activeToken == context.token) { activeToken = null; work.stop() }
            is PluginEvent.Action -> when (event.id) {
                "retry" -> work.start(context)
                "cancel" -> { work.cancel(); status(context, "已取消本次文本决策", false) }
                "run" -> {
                    work.cancel()
                    val request = try { liteRtTextRunRequest(context.settings) }
                    catch (error: IllegalArgumentException) { status(context, error.message ?: "请检查文本与选项", true); return }
                    status(context, "正在分析输入文本…", false)
                    work.submit(context) { engine, valid ->
                        if (!valid()) return@submit
                        val report = liteRtTextRun(request, engine.engine)
                        work.publishIf(valid, context, PluginOutput(System.nanoTime(), report.messages, actions = actions,
                            execution = report.execution))
                    }
                }
            }
            else -> Unit
        }
    }

    override fun close() = work.close()
}
