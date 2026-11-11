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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.cameraModelProblem
import com.example.minimalapk.plugins.CONFIGURED_MODEL_ID_KEY
import com.example.minimalapk.plugins.PluginIds
import com.example.minimalapk.plugins.PluginSettings
import com.example.minimalapk.plugins.PluginSettingsStore
import com.example.minimalapk.plugins.PoseEnhancementSettings
import com.example.minimalapk.plugins.SystemMonitorSettings
import com.example.minimalapk.pose.ModelSpec
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.NpuSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PluginsScreen(store: PluginSettingsStore) {
    val context = LocalContext.current
    val settings by store.settings.collectAsStateWithLifecycle()
    val pose = settings.getValue(PluginIds.POSE_ENHANCEMENT)
    val monitor = settings.getValue(PluginIds.SYSTEM_MONITOR)
    val configured = settings.getValue(PluginIds.CONFIGURED_MODEL)
    var configure by rememberSaveable { mutableStateOf(false) }
    var configureMonitor by rememberSaveable { mutableStateOf(false) }
    var configureModels by rememberSaveable { mutableStateOf(false) }
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
