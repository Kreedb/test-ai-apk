package com.example.minimalapk.ui.screens

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.ExampleModel
import com.example.minimalapk.examples.personalizationCheckpoint
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.cameraModelProblem
import com.example.minimalapk.plugins.AudioClassificationSettings
import com.example.minimalapk.plugins.CONFIGURED_MODEL_ID_KEY
import com.example.minimalapk.plugins.ExampleSceneSpec
import com.example.minimalapk.plugins.ExampleScenes
import com.example.minimalapk.plugins.PluginIds
import com.example.minimalapk.plugins.PluginSettings
import com.example.minimalapk.plugins.PluginSettingsStore
import com.example.minimalapk.plugins.PoseEnhancementSettings
import com.example.minimalapk.plugins.SceneThresholdSettings
import com.example.minimalapk.plugins.SuperResolutionSettings
import com.example.minimalapk.plugins.SystemMonitorSettings
import com.example.minimalapk.plugins.TTS_TEXT_KEY
import com.example.minimalapk.plugins.TtsEngine
import com.example.minimalapk.plugins.clampTtsText
import com.example.minimalapk.pose.ModelSpec
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.NpuSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PluginsScreen(store: PluginSettingsStore) {
    val context = LocalContext.current
    val settings by store.settings.collectAsStateWithLifecycle()
    val pose = settings.getValue(PluginIds.POSE_ENHANCEMENT)
    val monitor = settings.getValue(PluginIds.SYSTEM_MONITOR)
    val configured = settings.getValue(PluginIds.CONFIGURED_MODEL)
    val superResolution = settings.getValue(PluginIds.SUPER_RESOLUTION)
    val audioClassification = settings.getValue(PluginIds.AUDIO_CLASSIFICATION)
    val tts = settings.getValue(PluginIds.TTS)
    val personalization = settings.getValue(PluginIds.PERSONALIZATION)
    var configure by rememberSaveable { mutableStateOf(false) }
    var configureMonitor by rememberSaveable { mutableStateOf(false) }
    var configureModels by rememberSaveable { mutableStateOf(false) }
    var configureSuperResolution by rememberSaveable { mutableStateOf(false) }
    var configureAudio by rememberSaveable { mutableStateOf(false) }
    var configureTts by rememberSaveable { mutableStateOf(false) }
    var configurePersonalization by rememberSaveable { mutableStateOf(false) }
    var configureScene by rememberSaveable { mutableStateOf<String?>(null) }
    var configuredModels by remember { mutableStateOf(emptyList<InstalledConfiguredModel>()) }
    var configuredModelsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        ConfiguredModelStore.revision.collect {
            configuredModels = loadConfiguredModels(context)
            configuredModelsLoaded = true
        }
    }
    val selectedConfigured = configuredModels.firstOrNull {
        it.model.id == configured.config[CONFIGURED_MODEL_ID_KEY].orEmpty()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("插件", "点击卡片配置，使用开关独立启停") }
        item {
            PluginCard(
                name = "姿态增强", status = if (pose.enabled) "已启用 · 身高与人体测量" else "已停用 · 可继续配置",
                enabled = pose.enabled,
                onEnabledChange = { store.setEnabled(PluginIds.POSE_ENHANCEMENT, it) },
                onConfigure = { configure = true },
            )
        }
        item {
            PluginCard(
                name = "自定义模型",
                status = configuredModelStatus(configured, selectedConfigured, configuredModelsLoaded),
                enabled = configured.enabled,
                onEnabledChange = { store.setEnabled(PluginIds.CONFIGURED_MODEL, it) },
                onConfigure = { configureModels = true },
            )
        }
        item {
            PluginCard(
                name = "系统监控", status = if (monitor.enabled) "已启用 · 相机指标与 NPU 探测" else "已停用 · 可继续配置",
                enabled = monitor.enabled,
                onEnabledChange = { store.setEnabled(PluginIds.SYSTEM_MONITOR, it) },
                onConfigure = { configureMonitor = true },
            )
        }
        item {
            PluginCard(
                name = "超分辨率",
                status = if (superResolution.enabled) "已启用 · 按住预览增强中心区域" else "已停用 · 可继续配置",
                enabled = superResolution.enabled,
                onEnabledChange = { store.setEnabled(PluginIds.SUPER_RESOLUTION, it) },
                onConfigure = { configureSuperResolution = true },
            )
        }
        items(ExampleScenes.all, key = { it.id }) { spec ->
            val scene = settings.getValue(spec.id)
            PluginCard(
                name = spec.name,
                status = sceneStatus(context, scene, spec),
                enabled = scene.enabled,
                onEnabledChange = { store.setEnabled(spec.id, it) },
                onConfigure = { configureScene = spec.id },
            )
        }
        item {
            PluginCard(
                name = "声音识别",
                status = if (audioClassification.enabled) "已启用 · YAMNet 声音/环境声分类（不是语音识别）"
                else "已停用 · 可继续配置",
                enabled = audioClassification.enabled,
                onEnabledChange = { store.setEnabled(PluginIds.AUDIO_CLASSIFICATION, it) },
                onConfigure = { configureAudio = true },
            )
        }
        item {
            PluginCard(
                name = "语音朗读",
                status = if (tts.enabled) "已启用 · 系统 TTS，显式朗读" else "已停用 · 可继续配置",
                enabled = tts.enabled,
                onEnabledChange = { store.setEnabled(PluginIds.TTS, it) },
                onConfigure = { configureTts = true },
            )
        }
        item {
            PluginCard(
                name = "设备端学习",
                status = personalizationStatus(context, personalization),
                enabled = personalization.enabled,
                onEnabledChange = { store.setEnabled(PluginIds.PERSONALIZATION, it) },
                onConfigure = { configurePersonalization = true },
            )
        }
    }
    if (configure) {
        PoseEnhancementDialog(PoseEnhancementSettings.from(pose), onSave = {
            store.configure(PluginIds.POSE_ENHANCEMENT, it.toConfig())
            configure = false
        }, onDismiss = { configure = false })
    }
    if (configureMonitor) {
        SystemMonitorDialog(SystemMonitorSettings.from(monitor), onSave = {
            store.configure(PluginIds.SYSTEM_MONITOR, it.toConfig())
            configureMonitor = false
        }, onDismiss = { configureMonitor = false })
    }
    if (configureSuperResolution) {
        SuperResolutionDialog(SuperResolutionSettings.from(superResolution), onSave = {
            store.configure(PluginIds.SUPER_RESOLUTION, it.toConfig())
            configureSuperResolution = false
        }, onDismiss = { configureSuperResolution = false })
    }
    if (configureModels) {
        ConfiguredModelDialog(
            configured = configured,
            installed = configuredModels,
            onSelectionChange = { id ->
                store.configure(
                    PluginIds.CONFIGURED_MODEL,
                    configured.config + (CONFIGURED_MODEL_ID_KEY to id),
                )
            },
            onDismiss = { configureModels = false },
        )
    }
    configureScene?.let { id ->
        val spec = ExampleScenes.all.firstOrNull { it.id == id }
        if (spec != null) {
            ScenePluginDialog(
                spec = spec,
                settings = settings.getValue(id),
                installed = configuredModels,
                onSave = { config ->
                    store.configure(id, config)
                    configureScene = null
                },
                onDismiss = { configureScene = null },
            )
        }
    }
    if (configureAudio) {
        AudioClassificationDialog(
            settings = audioClassification,
            installed = configuredModels,
            onSave = {
                store.configure(PluginIds.AUDIO_CLASSIFICATION, it.toConfig())
                configureAudio = false
            },
            onDismiss = { configureAudio = false },
        )
    }
    if (configureTts) {
        TtsDialog(
            settings = tts,
            enabled = tts.enabled,
            onSaveText = { text -> store.configure(PluginIds.TTS, mapOf(TTS_TEXT_KEY to text)) },
            onDismiss = { configureTts = false },
        )
    }
    if (configurePersonalization) {
        Dialog(
            onDismissRequest = { configurePersonalization = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                PersonalizationScreen(onBack = { configurePersonalization = false })
            }
        }
    }
}

private data class InstalledConfiguredModel(val model: ConfiguredModel, val problem: String?)

private suspend fun loadConfiguredModels(context: Context): List<InstalledConfiguredModel> =
    withContext(Dispatchers.IO) {
        ConfiguredModelStore.list(ConfiguredModelStore.directory(context)).map { model ->
            val problem = try {
                val description = ConfiguredModelStore.describe(model)
                val config = ModelConfig.decode(model.configFile.readText(), description)
                cameraModelProblem(config)
            } catch (error: Exception) {
                error.message ?: "配置无法读取"
            }
            InstalledConfiguredModel(model, problem)
        }
    }

private fun configuredModelStatus(
    settings: PluginSettings,
    selected: InstalledConfiguredModel?,
    loaded: Boolean,
): String {
    if (!settings.enabled) return "已停用 · 可继续配置"
    if (settings.config[CONFIGURED_MODEL_ID_KEY].orEmpty().isBlank()) return "已启用 · 未就绪：未选择模型"
    if (!loaded) return "已启用 · 正在读取模型列表"
    if (selected == null) return "已启用 · 未就绪：所选模型已删除"
    selected.problem?.let { return "已启用 · 未就绪：$it" }
    return "已启用 · ${selected.model.name}"
}

private fun exampleCached(context: Context, example: ExampleModel): Boolean {
    val file = ExampleCatalog.file(context, example)
    return file.isFile && file.length() == example.sizeBytes
}

private fun sceneStatus(context: Context, settings: PluginSettings, spec: ExampleSceneSpec): String {
    if (!settings.enabled) return "已停用 · 可继续配置"
    return if (exampleCached(context, spec.example)) "已启用 · 模型已缓存，在相机帧上运行"
    else "已启用 · 首次启用时下载约 ${spec.example.sizeBytes / 1024 / 1024} MB"
}

private fun personalizationStatus(context: Context, settings: PluginSettings): String {
    if (!settings.enabled) return "已停用 · 4 类本机训练与相机识别"
    return if (personalizationCheckpoint(context).isFile) {
        "已启用 · 使用本机训练的 4 类分类头"
    } else {
        "已启用 · 未就绪：请先在插件中训练"
    }
}

@Composable
private fun ScenePluginDialog(
    spec: ExampleSceneSpec,
    settings: PluginSettings,
    installed: List<InstalledConfiguredModel>,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var thresholdText by rememberSaveable { mutableStateOf(SceneThresholdSettings.from(settings).thresholdText) }
    val threshold = thresholdText.toFloatOrNull()
    val valid = !spec.thresholdEnabled || threshold != null &&
        threshold in SceneThresholdSettings.MIN_THRESHOLD..SceneThresholdSettings.MAX_THRESHOLD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(spec.description, style = MaterialTheme.typography.bodySmall)
                ExampleModelSection(spec.example, installed)
                if (spec.thresholdEnabled) {
                    OutlinedTextField(
                        value = thresholdText,
                        onValueChange = { thresholdText = it.filter { char -> char.isDigit() || char == '.' }.take(4) },
                        label = { Text("置信度阈值") }, singleLine = true, isError = !valid,
                        supportingText = { Text("0.05–0.95；低于阈值的框/关键点/像素不显示，不修改工作台配置") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "启用后插件在专用线程下载并加载模型，在相机帧上运行；多开场景插件会顺序推理并降低帧率。" +
                        "停用、切页或后台会停止推理并清空输出。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    if (spec.thresholdEnabled) SceneThresholdSettings(thresholdText).toConfig() else emptyMap(),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AudioClassificationDialog(
    settings: PluginSettings,
    installed: List<InstalledConfiguredModel>,
    onSave: (AudioClassificationSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var topKText by rememberSaveable { mutableStateOf(AudioClassificationSettings.from(settings).topKText) }
    val topK = topKText.toIntOrNull()
    val valid = topK != null && topK in AudioClassificationSettings.MIN_TOP_K..AudioClassificationSettings.MAX_TOP_K
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("声音识别") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "YAMNet 对最近约 1 秒的麦克风 PCM 做声音/环境声分类，只显示模型内置标签的前几类。" +
                        "它不是语音识别，不转写语音内容；插件不自行打开麦克风、不把音频写入磁盘。",
                    style = MaterialTheme.typography.bodySmall,
                )
                ExampleModelSection(ExampleCatalog.audio, installed)
                OutlinedTextField(
                    value = topKText, onValueChange = { topKText = it.filter(Char::isDigit).take(1) },
                    label = { Text("显示类别数") }, singleLine = true, isError = !valid,
                    supportingText = { Text("1–5；推理慢于窗口时丢弃旧窗口，只发布最新结果") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "停止、切页、后台或修改配置会清空半窗口缓冲；旧代次的结果不会恢复文字。" +
                        "共享麦克风权限与其他音频插件由相机宿主统一管理。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(AudioClassificationSettings(topKText)) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TtsDialog(
    settings: PluginSettings,
    enabled: Boolean,
    onSaveText: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val maxText = TextToSpeech.getMaxSpeechInputLength()
    var text by rememberSaveable {
        mutableStateOf(settings.config[TTS_TEXT_KEY].orEmpty().ifEmpty { "你好，这是插件语音朗读。" })
    }
    var status by remember { mutableStateOf("正在初始化系统语音引擎…") }
    var ready by remember { mutableStateOf(false) }
    var utterance by remember { mutableStateOf<String?>(null) }
    val holder = remember { arrayOfNulls<TtsEngine>(1) }
    DisposableEffect(Unit) {
        val engine = TtsEngine(context) { message, isReady -> status = message; ready = isReady }
        holder[0] = engine
        onDispose {
            engine.close()
            holder[0] = null
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                holder[0]?.stop()
                utterance = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            holder[0]?.stop()
            utterance = null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("语音朗读") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "调用设备自带的 TextToSpeech 引擎，不是 LiteRT 模型；是否离线取决于系统语音包。" +
                        "只在你按下朗读时发声，不随相机帧重复、不自动播放，也不需要麦克风权限。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = clampTtsText(it, maxText) },
                    label = { Text("要朗读的文本") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${text.length} / $maxText 字") },
                )
                if (!enabled) {
                    Text(
                        "插件已停用：先在卡片总开关中启用，才能在这里朗读。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = enabled && ready && text.isNotBlank(),
                        onClick = {
                            if (holder[0]?.speak(text) == true) {
                                utterance = text
                                status = "正在朗读…"
                                onSaveText(text)
                            } else {
                                status = "朗读失败，请检查系统引擎与语音包"
                            }
                        },
                    ) { Text("朗读") }
                    TextButton(enabled = enabled, onClick = {
                        holder[0]?.stop()
                        utterance = null
                        status = "已停止朗读"
                    }) { Text("停止") }
                }
                utterance?.let {
                    Text(
                        "当前朗读：${it.take(120)}${if (it.length > 120) "…" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "离开本页、切到后台、关闭弹层或停用插件都会停止朗读，避免与声音识别等音频插件形成回环。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSaveText(text)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ExampleModelSection(example: ExampleModel, installed: List<InstalledConfiguredModel>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val cached = exampleCached(context, example)
    val installedModel = installed.firstOrNull { it.model.id == example.sha256 }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("模型：${example.name}（约 ${example.sizeBytes / 1024 / 1024} MB）", style = MaterialTheme.typography.titleSmall)
        SelectionContainer {
            Text(example.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (cached) "本地缓存：已下载（大小匹配，启用时再次校验 SHA-256）"
            else "本地缓存：未下载（启用或重试时下载并校验大小/SHA-256）",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (installedModel != null) "工作台安装：已安装（复用同一模型与配置，不覆盖已有配置）"
            else "工作台安装：未安装",
            style = MaterialTheme.typography.bodySmall,
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(enabled = !busy, onClick = {
            busy = true
            message = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { ExampleDownload.install(context, example) }
                    message = "已下载、校验并安装；启用后由相机插件加载"
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    message = "重试失败：${error.message ?: "下载或校验失败"}"
                } finally {
                    busy = false
                }
            }
        }) { Text("重试下载") }
    }
}

@Composable
private fun ConfiguredModelDialog(
    configured: PluginSettings,
    installed: List<InstalledConfiguredModel>,
    onSelectionChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var specs by remember { mutableStateOf(ModelStore.load(context)) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val selected = installed.firstOrNull {
        it.model.id == configured.config[CONFIGURED_MODEL_ID_KEY].orEmpty()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            ModelsScreen(
                specs = specs,
                onSpecsChange = { updated ->
                    specs = updated
                    ModelStore.save(context, updated)
                },
                onBack = onDismiss,
                title = "自定义模型",
                header = {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        SettingRow(
                            title = "相机使用的模型",
                            subtitle = when {
                                !configured.enabled -> "插件已停用；开启后使用${
                                    selected?.model?.name?.let { " $it" } ?: "所选模型"
                                }"
                                selected == null && configured.config[CONFIGURED_MODEL_ID_KEY].orEmpty().isBlank() ->
                                    "未选择 · 插件未就绪"
                                selected == null -> "所选模型已删除 · 插件未就绪"
                                selected.problem != null -> "${selected.model.name} · 相机不可用：${selected.problem}"
                                else -> selected.model.name
                            },
                            onClick = { showPicker = true },
                        )
                    }
                },
            )
        }
    }
    if (showPicker) {
        OptionDialog(
            title = "相机使用的模型",
            options = listOf<InstalledConfiguredModel?>(null) + installed,
            selected = selected,
            label = { entry ->
                entry?.let { item ->
                    if (item.problem == null) item.model.name
                    else "${item.model.name}（相机不可用：${item.problem}）"
                } ?: "未选择（插件未就绪）"
            },
            onSelect = { entry ->
                onSelectionChange(entry?.model?.id ?: "")
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** Shared card for the downstream built-ins. The switch and configuration click are siblings,
 * never nested click targets: toggling cannot also open the dialog, including with TalkBack.
 */
@Composable
internal fun PluginCard(
    name: String,
    status: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConfigure: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "配置$name", onClick = onConfigure)
                    .padding(16.dp),
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = enabled, onCheckedChange = onEnabledChange,
                modifier = Modifier.padding(end = 16.dp).semantics {
                    contentDescription = "$name 总开关"
                    stateDescription = if (enabled) "已启用" else "已停用"
                },
            )
        }
    }
}

@Composable
private fun PoseEnhancementDialog(
    initial: PoseEnhancementSettings,
    onSave: (PoseEnhancementSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var height by rememberSaveable { mutableStateOf(initial.heightText) }
    var heightLine by rememberSaveable { mutableStateOf(initial.showHeightLine) }
    var armSpan by rememberSaveable { mutableStateOf(initial.showArmSpan) }
    val valid = height.toFloatOrNull()?.let { it in 50f..250f } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("姿态增强") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = height, onValueChange = { height = it.filter(Char::isDigit).take(3) },
                    label = { Text("身高 (cm)") }, singleLine = true, isError = !valid,
                    supportingText = { Text("有效范围 50–250 cm；清空后相机提示填写身高") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                PluginSwitchRow("身高线", "标注身高计算点与长度", heightLine) { heightLine = it }
                PluginSwitchRow("臂长线", "标注左右腕点与臂展长度", armSpan) { armSpan = it }
                Text("总开关仅控制增强测量线与人体文字；基础姿态骨架由已启用的姿态模型提供。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(PoseEnhancementSettings(height, heightLine, armSpan)) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SuperResolutionDialog(
    initial: SuperResolutionSettings,
    onSave: (SuperResolutionSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var replacePreview by rememberSaveable { mutableStateOf(initial.replacePreview) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("超分辨率") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PluginSwitchRow(
                    "替换画面",
                    "按住相机预览时显示增强画面；松手或取消立即恢复实时原图",
                    replacePreview,
                ) { replacePreview = it }
                Text(
                    "仅增强画面中心、较小边一半的方形区域：模型输入 50×50，输出 200×200（4×），" +
                        "贴回同一来源帧的同一位置，不是整帧超分。模型首次使用时下载（约 5 MB），" +
                        "下载/加载状态与失败原因显示在相机画面底部。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(SuperResolutionSettings(replacePreview)) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SystemMonitorDialog(
    initial: SystemMonitorSettings,
    onSave: (SystemMonitorSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var showFps by rememberSaveable { mutableStateOf(initial.showFps) }
    var showTimings by rememberSaveable { mutableStateOf(initial.showTimings) }
    var showBackend by rememberSaveable { mutableStateOf(initial.showBackend) }
    var showCpu by rememberSaveable { mutableStateOf(initial.showCpu) }
    var showGpu by rememberSaveable { mutableStateOf(initial.showGpu) }
    var showNpu by rememberSaveable { mutableStateOf(initial.showNpu) }
    var refreshText by rememberSaveable { mutableStateOf(initial.refreshMs.toString()) }
    var windowText by rememberSaveable { mutableStateOf(initial.fpsWindowSeconds.toString()) }
    var source by rememberSaveable { mutableStateOf(initial.npuSource) }
    var showSourceOptions by rememberSaveable { mutableStateOf(false) }
    val refresh = refreshText.toIntOrNull()
    val window = windowText.toIntOrNull()
    val valid = refresh != null && refresh in SystemMonitorSettings.MIN_REFRESH_MS..SystemMonitorSettings.MAX_REFRESH_MS &&
        window != null && window in SystemMonitorSettings.MIN_FPS_WINDOW_SECONDS..SystemMonitorSettings.MAX_FPS_WINDOW_SECONDS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("系统监控") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("显示指标", style = MaterialTheme.typography.titleSmall)
                PluginSwitchRow("帧率", "相机帧率，来自帧时间戳", showFps) { showFps = it }
                PluginSwitchRow("耗时", "预处理/推理/后处理/分析总耗时", showTimings) { showTimings = it }
                PluginSwitchRow("后端", "上次成功加载的推理后端", showBackend) { showBackend = it }
                PluginSwitchRow("CPU", "本应用占设备总 CPU 算力", showCpu) { showCpu = it }
                PluginSwitchRow("GPU", "厂商 sysfs 负载，显示来源", showGpu) { showGpu = it }
                PluginSwitchRow("NPU", "按所选来源与单位显示", showNpu) { showNpu = it }
                OutlinedTextField(
                    value = refreshText, onValueChange = { refreshText = it.filter(Char::isDigit).take(4) },
                    label = { Text("刷新间隔 (ms)") }, singleLine = true,
                    isError = refresh == null ||
                        refresh !in SystemMonitorSettings.MIN_REFRESH_MS..SystemMonitorSettings.MAX_REFRESH_MS,
                    supportingText = { Text("250–5000 ms，实时生效") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = windowText, onValueChange = { windowText = it.filter(Char::isDigit).take(2) },
                    label = { Text("FPS 窗口 (s)") }, singleLine = true,
                    isError = window == null ||
                        window !in SystemMonitorSettings.MIN_FPS_WINDOW_SECONDS..SystemMonitorSettings.MAX_FPS_WINDOW_SECONDS,
                    supportingText = { Text("1–10 s，慢帧保留最近两个样本") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NPU 来源", style = MaterialTheme.typography.titleSmall)
                        Text(source.label, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showSourceOptions = true }) { Text("选择") }
                }
                Text(
                    "自动只尝试有百分比语义的来源；指定方案可单独确认设备是否可读；测试全部会逐个报告状态与原文摘要，不会把不可用写成 0%。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(SystemMonitorSettings(
                    showFps = showFps, showTimings = showTimings, showBackend = showBackend,
                    showCpu = showCpu, showGpu = showGpu, showNpu = showNpu,
                    refreshMs = checkNotNull(refresh), fpsWindowSeconds = checkNotNull(window),
                    npuSource = source,
                ))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (showSourceOptions) {
        OptionDialog(
            title = "NPU 来源", options = NpuSource.entries, selected = source, label = { it.label },
            onSelect = { source = it; showSourceOptions = false }, onDismiss = { showSourceOptions = false },
        )
    }
}

@Composable
internal fun PluginSwitchRow(name: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange, modifier = Modifier.semantics { contentDescription = name })
    }
}
