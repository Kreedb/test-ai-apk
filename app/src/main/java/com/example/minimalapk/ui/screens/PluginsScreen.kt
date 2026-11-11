package com.example.minimalapk.ui.screens

import android.content.Context
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleModel
import com.example.minimalapk.examples.personalizationCheckpoint
import com.example.minimalapk.plugins.AUDIO_WAV_URI_KEY
import com.example.minimalapk.plugins.audioUsesMicrophone
import com.example.minimalapk.plugins.ExampleSceneSpec
import com.example.minimalapk.plugins.ExampleScenes
import com.example.minimalapk.plugins.PluginIds
import com.example.minimalapk.plugins.LiteRtScenes
import com.example.minimalapk.plugins.LocalTextPlugin
import com.example.minimalapk.plugins.PluginSettings
import com.example.minimalapk.plugins.PluginSettingsStore
import com.example.minimalapk.plugins.PoseEnhancementSettings
import com.example.minimalapk.plugins.SuperResolutionSettings
import com.example.minimalapk.plugins.SystemMonitorSettings
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.NpuSource

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
    var configureLiteRt by rememberSaveable { mutableStateOf<String?>(null) }
    var configureLocalText by rememberSaveable { mutableStateOf(false) }
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
                status = if (configured.enabled) "已启用 · 运行模型列表中打开的模型" else "已停用 · 点击添加与配置模型",
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
                status = if (superResolution.enabled) "已启用 · 按住预览查看整幅增强画面" else "已停用 · 整幅画面细节增强",
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
                status = "${if (audioClassification.enabled) "已启用" else "已停用"} · " + when {
                    audioUsesMicrophone(audioClassification) -> "共享麦克风 · 说话、音乐与环境声分类"
                    audioClassification.config[AUDIO_WAV_URI_KEY].isNullOrBlank() -> "WAV 文件 · 待选择文件（不录音）"
                    else -> "WAV 文件 · 返回相机点击识别（不录音）"
                },
                enabled = audioClassification.enabled,
                onEnabledChange = { store.setEnabled(PluginIds.AUDIO_CLASSIFICATION, it) },
                onConfigure = { configureAudio = true },
            )
        }
        item {
            PluginCard(
                name = "语音朗读",
                status = if (tts.enabled) "已启用 · 输入文字并选择语音引擎朗读" else "已停用 · 文字转语音",
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
        items(LiteRtScenes.all, key = { it.id }) { scene ->
            val current = settings[scene.id] ?: PluginSettings()
            PluginCard(
                name = scene.name,
                status = "${if (current.enabled) "已启用" else "已停用"} · ${scene.purpose}",
                enabled = current.enabled,
                onEnabledChange = { store.setEnabled(scene.id, it) },
                onConfigure = { configureLiteRt = scene.id },
            )
        }
        item {
            val localText = settings[LocalTextPlugin.ID] ?: PluginSettings()
            PluginCard(
                name = "本地文本助手",
                status = "${if (localText.enabled) "已启用" else "已停用"} · 根据输入文本生成回答",
                enabled = localText.enabled,
                onEnabledChange = { store.setEnabled(LocalTextPlugin.ID, it) },
                onConfigure = { configureLocalText = true },
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
        }, onCompare = {
            store.setEnabled(PluginIds.SYSTEM_MONITOR, true)
            store.requestAction(PluginIds.SYSTEM_MONITOR, "backend_compare")
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
        ConfiguredModelDialog(onDismiss = { configureModels = false })
    }
    configureLiteRt?.let { id ->
        LiteRtScenes.all.firstOrNull { it.id == id }?.let { scene ->
            LiteRtSettingsDialog(
                scene = scene, settings = settings[id] ?: PluginSettings(),
                onSave = { config, run ->
                    store.configure(id, config)
                    if (run) store.requestAction(id, "run")
                    configureLiteRt = null
                },
                onDownload = { configureLiteRt = null; configureModels = true },
                onDismiss = { configureLiteRt = null },
            )
        }
    }
    if (configureLocalText) {
        LocalTextConfigDialog(
            settings = settings[LocalTextPlugin.ID] ?: PluginSettings(),
            onDismiss = { configureLocalText = false },
            onConfirm = { config ->
                store.configure(LocalTextPlugin.ID, config)
                configureLocalText = false
            },
        )
    }
    configureScene?.let { id ->
        val spec = ExampleScenes.all.firstOrNull { it.id == id }
        if (spec != null) {
            ExampleSceneSettingsDialog(
                spec = spec,
                settings = settings.getValue(id),
                onSave = { config ->
                    store.configure(id, config)
                    configureScene = null
                },
                onDismiss = { configureScene = null },
            )
        }
    }
    if (configureAudio) {
        AudioClassificationSettingsDialog(
            settings = audioClassification,
            onSave = {
                store.configure(PluginIds.AUDIO_CLASSIFICATION, it)
                configureAudio = false
            },
            onDismiss = { configureAudio = false },
        )
    }
    if (configureTts) {
        TtsSettingsDialog(
            settings = tts,
            enabled = tts.enabled,
            onSaveConfig = { config -> store.configure(PluginIds.TTS, config) },
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

private fun exampleCached(context: Context, example: ExampleModel): Boolean {
    val file = ExampleCatalog.file(context, example)
    return file.isFile && file.length() == example.sizeBytes
}

private fun sceneStatus(context: Context, settings: PluginSettings, spec: ExampleSceneSpec): String {
    val purpose = when (spec.id) {
        PluginIds.MOVENET_SCENE -> "放下 → 抬起 → 放下，自动统计完整次数"
        PluginIds.IMAGE_CLASSIFICATION_SCENE -> "对准单个物品，查看类别与置信度"
        PluginIds.OBJECT_DETECTION_SCENE -> "框出画面中的物体并统计数量"
        PluginIds.IMAGE_SEGMENTATION_SCENE -> "分色显示人物、物体区域与面积占比"
        else -> spec.description
    }
    return "${if (settings.enabled) "已启用" else "已停用"} · $purpose" +
        if (settings.enabled && !exampleCached(context, spec.example)) "（首次需下载模型）" else ""
}

private fun personalizationStatus(context: Context, settings: PluginSettings): String {
    if (!settings.enabled) return "已停用 · 自定 4 类，每类至少 5 张照片训练"
    return if (personalizationCheckpoint(context).isFile) {
        "已启用 · 已有训练存档，进入相机加载"
    } else {
        "已启用 · 未就绪：请先在插件中训练"
    }
}

@Composable
private fun ConfiguredModelDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var specs by remember { mutableStateOf(ModelStore.load(context)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            ModelsScreen(
                specs = specs,
                onSpecsChange = { updated ->
                    specs = updated
                    ModelStore.save(context, updated)
                },
                onSpecsReload = { specs = it },
                onBack = onDismiss,
                title = "自定义模型",
            )
        }
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
                    "对整幅相机画面分块增强。保持相机稳定并按住预览，等待处理完成后查看增强快照；" +
                        "松手恢复实时画面。首次使用需下载约 5 MB 模型，处理进度与分辨率显示在相机中。",
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
    onCompare: () -> Unit,
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
                TextButton(onClick = onCompare) { Text("比较 CPU / GPU / NPU（内置 YOLO26n）") }
                Text("同一内置模型与固定合成输入；每后端预热 1 次、测量 3 次，包含读回。返回相机执行，可取消；GPU/NPU 可混合 CPU，实际硬件未必可证明。", style = MaterialTheme.typography.bodySmall)
                PluginSwitchRow("帧率", "实际处理的相机帧流（非传感器标称帧率）", showFps) { showFps = it }
                PluginSwitchRow("耗时", "主姿态链路 / 同步帧处理 / 异步最近完成（分别显示）", showTimings) { showTimings = it }
                PluginSwitchRow("后端", "主姿态后端；GPU/NPU 实际分区可能未知", showBackend) { showBackend = it }
                PluginSwitchRow("CPU", "本应用占设备总 CPU 算力", showCpu) { showCpu = it }
                PluginSwitchRow("GPU", "厂商 sysfs 负载，显示来源", showGpu) { showGpu = it }
                PluginSwitchRow("NPU", "按所选来源与单位显示", showNpu) { showNpu = it }
                OutlinedTextField(
                    value = refreshText, onValueChange = { refreshText = it.filter(Char::isDigit).take(4) },
                    label = { Text("刷新间隔 (ms)") }, singleLine = true,
                    isError = refresh == null ||
                        refresh !in SystemMonitorSettings.MIN_REFRESH_MS..SystemMonitorSettings.MAX_REFRESH_MS,
                    supportingText = { Text("250–5000 ms；默认 250 ms，保存后生效") },
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
                    "自动优先有已验证语义的来源，未知 load 保留 raw；指定方案可单独确认设备是否可读。开启 NPU 并选“测试全部”，保存返回相机后点击“复制完整 NPU 诊断”，包含每条来源原文、失败原因与读取限制；空闲/推理时各复制一次。诊断不写日志、不上传。",
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
