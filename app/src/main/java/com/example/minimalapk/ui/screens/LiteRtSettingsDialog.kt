package com.example.minimalapk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.example.minimalapk.models.AssetCatalog
import com.example.minimalapk.plugins.LiteRtScene
import com.example.minimalapk.plugins.LiteRtSceneKind
import com.example.minimalapk.plugins.LiteRtScenes
import com.example.minimalapk.plugins.LiteRtMemory
import com.example.minimalapk.plugins.LITERT_LAYA_TASK
import com.example.minimalapk.plugins.LiteRtLayaMode
import com.example.minimalapk.plugins.PluginSettings
import com.example.minimalapk.plugins.liteRtSize
import com.example.minimalapk.plugins.liteRtTextRunRequest
import java.io.File

@Composable
internal fun LiteRtSettingsDialog(
    scene: LiteRtScene,
    settings: PluginSettings,
    onSave: (Map<String, String>, Boolean) -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val catalog = remember { runCatching { AssetCatalog.load(context) } }
    val sets = catalog.getOrNull().orEmpty()
    var task by rememberSaveable { mutableStateOf(LiteRtScenes.task(settings, scene)) }
    var backend by rememberSaveable { mutableStateOf(settings.config["backend"] ?: "auto") }
    var input by rememberSaveable { mutableStateOf(settings.config["input"].orEmpty()) }
    var question by rememberSaveable { mutableStateOf(settings.config["question"] ?: "What is the intent of this message?") }
    var choices by rememberSaveable { mutableStateOf(settings.config["choices"] ?: "question: asking a question\nrequest: requesting help\nother: something else") }
    var layaMode by rememberSaveable { mutableStateOf(settings.config["laya_mode"] ?: LiteRtLayaMode.CHOICE.key) }
    var layaLevels by rememberSaveable { mutableStateOf(settings.config["laya_levels"] ?: "not urgent\nsoon\ncritical deadline or blocking issue") }
    var selectLayaMode by remember { mutableStateOf(false) }
    var seconds by rememberSaveable { mutableStateOf(LiteRtScenes.windowSeconds(settings, scene.kind == LiteRtSceneKind.CAPTIONS).toString()) }
    var replace by rememberSaveable { mutableStateOf(settings.config["replace_preview"] != "false") }
    var selectTask by remember { mutableStateOf(false) }
    var selectBackend by remember { mutableStateOf(false) }
    var memory by remember(task) { mutableStateOf(LiteRtMemory.snapshot(context)) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val selected = sets.firstOrNull { it.id == task }
    val ready = selected?.isReadyIn(File(context.filesDir, "model_assets")) == true
    val textInput = scene.kind == LiteRtSceneKind.TEXT || scene.kind == LiteRtSceneKind.SPEECH
    fun config(): Map<String, String> = settings.config + mapOf(
        "task_id" to task, "backend" to backend, "input" to input, "question" to question,
        "choices" to choices, "window_seconds" to seconds, "replace_preview" to replace.toString(),
    ) + if (scene.kind == LiteRtSceneKind.TEXT) mapOf(
        "laya_mode" to if (task == LITERT_LAYA_TASK) layaMode else LiteRtLayaMode.CHOICE.key,
        "laya_levels" to layaLevels,
    ) else emptyMap()
    val textValidation = if (scene.kind == LiteRtSceneKind.TEXT)
        runCatching { liteRtTextRunRequest(settings.copy(config = config())) } else null
    val valid = when (scene.kind) {
        LiteRtSceneKind.TEXT -> textValidation?.isSuccess == true
        LiteRtSceneKind.SPEECH -> input.isNotBlank() && input.length <= 200
        LiteRtSceneKind.CAPTIONS -> seconds.toIntOrNull()?.let { it in 2..8 } == true
        LiteRtSceneKind.AUDIO -> seconds.toIntOrNull()?.let { it in 2..10 } == true
        else -> task in scene.tasks
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(scene.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingRow("模型任务", selected?.let { "${it.name} · ${liteRtSize(it.totalBytes)}" } ?: task,
                    onClick = { selectTask = true })
                Text(if (scene.kind == LiteRtSceneKind.TEXT && task == LITERT_LAYA_TASK)
                    "Laya 支持多选、等级评分与是/否概率；填写文本后选择决策模式。结果在相机独立报告中显示。"
                    else LiteRtScenes.explanation(task), style = MaterialTheme.typography.bodySmall)
                LiteRtMemory.description(task, memory)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (task == "image-tagging") TextButton(onClick = { memory = LiteRtMemory.snapshot(context) }) { Text("刷新内存状态（加载前会再次检查）") }
                selected?.let { set ->
                    Text(if (ready) "文件已就绪（安装凭据与大小检查）；加载与推理将在相机中检查" else "模型未就绪，请先显式下载此任务的全部文件", color = MaterialTheme.colorScheme.primary)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${set.files.size} 个文件 · ${liteRtSize(set.totalBytes)}\n许可：${set.licenseName}", style = MaterialTheme.typography.bodySmall)
                            Text("来源：${set.upstreamUrl}", style = MaterialTheme.typography.bodySmall)
                            Text("模型卡：${set.modelCardUrl}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = {
                        linkError = runCatching { uriHandler.openUri(set.licenseUrl) }.exceptionOrNull()?.let { "无法打开链接，请复制上方来源地址" }
                    }) { Text("查看模型许可") }
                }
                linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                catalog.exceptionOrNull()?.let { Text("目录读取失败：${it.message}", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { onSave(config(), false); onDownload() }) { Text("自定义模型 → 从远程下载模型") }
                SettingRow("推理后端", when (backend) { "cpu" -> "CPU"; "gpu" -> "GPU（失败时报告回退）"; else -> "按目录推荐（${selected?.backend ?: "未知"}）" },
                    onClick = { selectBackend = true })
                if (task in LiteRtScenes.superResolutionTasks) PluginSwitchRow("替换画面", "仅按住时显示增强；释放立即恢复", replace) { replace = it }
                if (scene.kind in setOf(LiteRtSceneKind.CAPTIONS, LiteRtSceneKind.AUDIO)) {
                    OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(2) },
                        label = { Text(if (scene.kind == LiteRtSceneKind.CAPTIONS) "窗口秒数（2–8）" else "录音秒数（2–10）") },
                        singleLine = true, isError = !valid, modifier = Modifier.fillMaxWidth())
                    Text("使用相机共享的 16 kHz 单声道 PCM，按任务重采样；录音不落盘。播放时暂停麦克风。", style = MaterialTheme.typography.bodySmall)
                }
                if (textInput) {
                    OutlinedTextField(input, { input = it.take(if (scene.kind == LiteRtSceneKind.SPEECH) 200 else 4096) },
                        label = { Text(if (scene.kind == LiteRtSceneKind.SPEECH) "英语短句" else "输入文本") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
                if (scene.kind == LiteRtSceneKind.TEXT) {
                    val laya = task == LITERT_LAYA_TASK
                    if (laya) SettingRow("Laya 决策模式",
                        LiteRtLayaMode.entries.firstOrNull { it.key == layaMode }?.label ?: "未知模式：$layaMode",
                        onClick = { selectLayaMode = true })
                    OutlinedTextField(question, { question = it.take(1024) },
                        label = { Text(if (laya && layaMode == LiteRtLayaMode.YES_NO.key) "待判断的陈述或是/否问题" else "问题") },
                        modifier = Modifier.fillMaxWidth())
                    when {
                        laya && layaMode == LiteRtLayaMode.SCORE.key -> {
                            OutlinedTextField(layaLevels, { layaLevels = it.take(4096) },
                                label = { Text("等级：从低到高，每行一个说明") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                            Text("2–12 个不同等级；从 0 开始编号。输出为等级索引的概率加权期望，可为小数，并显示各等级概率。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        laya && layaMode == LiteRtLayaMode.YES_NO.key -> Text(
                            "使用模型固定 false/true 顺序与默认说明；无需选项。输出是陈述成立的连续概率 P(true)，不转成多选标签或硬阈值判断。",
                            style = MaterialTheme.typography.bodySmall)
                        else -> {
                            OutlinedTextField(choices, { choices = it.take(4096) }, label = { Text("选项：每行 key: 说明") },
                                minLines = 3, modifier = Modifier.fillMaxWidth())
                            Text("2–12 个不同选项；配置中的文本结果显示在相机独立报告，不执行任何命令。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (laya) Text("三种模式均使用 Laya 按类型/选项数的温度校准，并展示独立 action head 的 act_probability；仅报告概率，不执行动作。",
                        style = MaterialTheme.typography.bodySmall)
                    textValidation?.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                if (textInput) {
                    TextButton(enabled = settings.enabled && ready && valid, onClick = { onSave(config(), true) }) { Text("保存并请求运行（返回相机执行）") }
                }
                Text("启用后在相机操作；下载、模型加载与真实推理结果分别报告。切后台或停用将取消工作并释放模型。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(config(), false) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
    if (selectTask) OptionDialog(
        title = "选择${scene.name}任务", options = scene.tasks, selected = task,
        label = { id -> sets.firstOrNull { it.id == id }?.let { "${it.name} · ${liteRtSize(it.totalBytes)}\n$id" } ?: id },
        onSelect = {
            task = it
            if (scene.kind == LiteRtSceneKind.TEXT && it != LITERT_LAYA_TASK) layaMode = LiteRtLayaMode.CHOICE.key
            selectTask = false
        }, onDismiss = { selectTask = false },
    )
    if (selectBackend) OptionDialog(
        title = "推理后端", options = listOf("auto", "cpu", "gpu"), selected = backend,
        label = { when (it) { "auto" -> "按目录推荐"; "cpu" -> "CPU"; else -> "GPU" } },
        onSelect = { backend = it; selectBackend = false }, onDismiss = { selectBackend = false },
    )
    if (selectLayaMode && scene.kind == LiteRtSceneKind.TEXT && task == LITERT_LAYA_TASK) OptionDialog(
        title = "Laya 决策模式", options = LiteRtLayaMode.entries.map { it.key }, selected = layaMode,
        label = { key -> LiteRtLayaMode.entries.first { it.key == key }.label },
        onSelect = { layaMode = it; selectLayaMode = false }, onDismiss = { selectLayaMode = false },
    )
}
