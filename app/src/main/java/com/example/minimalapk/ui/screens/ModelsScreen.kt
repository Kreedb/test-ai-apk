package com.example.minimalapk.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.AssetTransfer
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.sha256
import com.example.minimalapk.models.commitModelInstallation
import com.example.minimalapk.models.runAssetTransfer
import com.example.minimalapk.pose.BoxFormat
import com.example.minimalapk.pose.GitHubMirrorMode
import com.example.minimalapk.pose.GitHubMirrorPreference
import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.ModelCatalog
import com.example.minimalapk.pose.ModelDownload
import com.example.minimalapk.pose.ModelSpec
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.OutputLayout
import com.example.minimalapk.pose.PoseModelImport
import com.example.minimalapk.pose.RemoteModel
import com.example.minimalapk.pose.TfliteGraph
import com.example.minimalapk.pose.knownSummary
import com.example.minimalapk.pose.readCapped
import com.example.minimalapk.pose.modelFile
import com.example.minimalapk.pose.rewriteGitHubUrl
import com.example.minimalapk.pose.validatePoseModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun ModelsScreen(
    specs: List<ModelSpec>,
    onSpecsChange: (List<ModelSpec>) -> Unit,
    onBack: () -> Unit,
    title: String = "模型",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mirrorPreference = remember { GitHubMirrorPreference.load(context) }
    var status by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ModelSpec?>(null) }
    var importing by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val byteProgress = remember { MutableStateFlow<Pair<Long, Long?>?>(null) }
    val progress by byteProgress.collectAsStateWithLifecycle()
    var showDownload by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var lastAttempt by remember { mutableStateOf<RemoteModel?>(null) }
    var configuredModels by remember { mutableStateOf(emptyList<ConfiguredModel>()) }
    var editingConfigured by remember { mutableStateOf<ConfiguredModel?>(null) }
    val directory = remember(context) { ConfiguredModelStore.directory(context) }
    // Imports run for seconds; use the latest list so a toggle made meanwhile is not discarded.
    val currentSpecs by rememberUpdatedState(specs)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) operation?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); operation?.cancel() }
    }

    LaunchedEffect(directory) {
        ConfiguredModelStore.revision.collect {
            try {
                configuredModels = withContext(Dispatchers.IO) { ConfiguredModelStore.list(directory) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = "读取模型失败：${error.message}"
            }
        }
    }

    fun applyImport(spec: ModelSpec, message: String) {
        // Re-importing the same bytes keeps the existing toggles and corrected configuration.
        // The small accepted commit can outlive dismissal. Read the persisted latest list so an
        // immediately reopened dialog's toggles/deletions cannot be replaced by this old UI list.
        onSpecsChange(ModelStore.withImported(ModelStore.load(context), spec))
        status = message
    }

    fun startTransfer(download: Boolean, prepare: (AssetTransfer) -> PendingModelImport) {
        if (importing) return
        importing = true
        committing = false
        status = null
        byteProgress.value = null
        operation = scope.launch {
            try {
                val prepared = runAssetTransfer(prepare)
                committing = true
                commitModelInstallation(prepared.install) { (spec, message) ->
                    if (spec != null) applyImport(spec, message) else status = message
                    if (download) showDownload = false
                }
            } catch (error: CancellationException) {
                status = "已取消，未完成的模型不会安装"
                throw error
            } catch (error: Exception) {
                AppLog.w(TAG, if (download) "模型下载失败" else "模型导入失败", error)
                if (download) downloadError = error.message ?: "未知错误"
                else status = "导入失败：${error.message ?: "未知错误"}"
            } finally {
                importing = false
                committing = false
                operation = null
                byteProgress.value = null
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        startTransfer(download = false) { transfer ->
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            val bytes = transfer.use(input) {
                it.readCapped(ModelDownload.MAX_BYTES, transfer::checkpoint) { count -> byteProgress.value = count to null }
            }
            val pending = prepareModelImport(context, context.displayName(uri), bytes)
            transfer.checkpoint()
            pending
        }
    }

    fun download(model: RemoteModel, preference: GitHubMirrorPreference) {
        downloadError = null
        lastAttempt = model
        startTransfer(download = true) { transfer ->
            val example = ModelCatalog.configurableModels.firstOrNull { it.url == model.url }
            val onProgress: (Long, Long?) -> Unit = { count, total -> byteProgress.value = count to total }
            if (example != null) {
                // This caches only a complete verified file. The following config/label commit
                // is prepared without another download and cannot be lost on a cancelled return.
                val prepared = ExampleDownload.prepareInstall(context, example, transfer, preference, onProgress)
                PendingModelImport {
                    val installed = prepared()
                    null to "已下载 ${installed.name}，点击模型卡片配置；图片模型开启后参与相机推理"
                }
            } else {
                val bytes = ModelDownload.fetch(model.url, preference, transfer, onProgress)
                ModelDownload.validateDownloaded(bytes, model.sizeBytes, model.sha256, transfer::checkpoint)
                val pending = prepareModelImport(context, model.name, bytes, model.boxFormat)
                transfer.checkpoint()
                pending
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = title, onBack = { operation?.cancel(); onBack() })
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "自定义模型总开关开启后，相机逐帧运行下方所有已启用的模型，无需另选模型。" +
                        "姿态增强也可单独使用已启用的姿态模型。" +
                        "姿态模型最多同时开启 ${ModelStore.MAX_ENABLED} 个；模型越多帧率越低。点击卡片配置参数，音频等模型可在卡片内选择文件测试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    SettingRow(
                        title = "导入 tflite 模型",
                        subtitle = "自动读取张量；导入后点击模型卡片配置、验证与运行",
                        onClick = { if (!importing) launcher.launch(arrayOf("*/*")) },
                    )
                }
            }
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    SettingRow(
                        title = "从远程下载模型",
                        subtitle = "官方单模型与完整资源集下载/导入，或任意 TFLite HTTPS 地址",
                        onClick = {
                            if (!importing) {
                                downloadError = null
                                showDownload = true
                            }
                        },
                    )
                }
            }
            if (importing) {
                item {
                    ModelTransferProgress(progress, committing)
                    TextButton(enabled = !committing, onClick = { operation?.cancel() }) { Text("取消传输") }
                }
            }
            status?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            items(specs, key = { it.id }) { spec ->
                ModelCard(
                    spec = spec,
                    onToggle = { enabled ->
                        if (enabled && currentSpecs.count { it.enabled } >= ModelStore.MAX_ENABLED) {
                            status = "姿态加速管线最多同时加载 ${ModelStore.MAX_ENABLED} 个模型，请先停用其它姿态模型"
                        } else {
                            status = null
                            onSpecsChange(
                                currentSpecs.map { if (it.id == spec.id) it.copy(enabled = enabled) else it },
                            )
                        }
                    },
                    onClick = { editing = spec },
                )
            }
            items(configuredModels, key = { "configured:${it.id}" }) { model ->
                ModelCard(
                    name = model.name,
                    summary = "通用 TFLite · 点击配置输入、输出与标签",
                    enabled = model.enabled,
                    onToggle = { enabled ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { ConfiguredModelStore.setEnabled(model, enabled) }
                                status = null
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                status = error.message
                            }
                        }
                    },
                    onClick = { editingConfigured = model },
                )
            }
        }
    }

    if (showDownload) {
        ModelDownloadDialog(
            downloading = importing,
            error = downloadError,
            mirror = mirrorPreference,
            failedModel = lastAttempt.takeIf { downloadError != null },
            progress = progress,
            committing = committing,
            onDownload = { model -> download(model, mirrorPreference) },
            onRetryDirect = { model ->
                download(model, GitHubMirrorPreference(mode = GitHubMirrorMode.DIRECT))
            },
            onCancel = { operation?.cancel() },
            onDismiss = { operation?.cancel(); showDownload = false },
        )
    }

    editing?.let { spec ->
        ModelEditDialog(
            spec = spec,
            onSave = { updated ->
                onSpecsChange(currentSpecs.map { if (it.id == updated.id) updated.copy(enabled = it.enabled) else it })
                editing = null
            },
            onDelete = {
                PoseModelImport.delete(context, spec)
                onSpecsChange(currentSpecs.filterNot { it.id == spec.id })
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    editingConfigured?.let { model ->
        ConfiguredModelEditDialog(model, onDismiss = { editingConfigured = null }, onDelete = { editingConfigured = null })
    }
}

@Composable
private fun ModelCard(spec: ModelSpec, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    ModelCard(
        name = spec.name,
        summary = "${spec.inputSize}×${spec.inputSize} · ${spec.anchors} 锚点 · ${spec.keypoints} 关键点\n" +
            "置信度 ${formatThreshold(spec.scoreThreshold)} · 关键点 ${formatThreshold(spec.keypointThreshold)} · " +
            "防抖 ${spec.debounceFrames} 帧 · ${spec.boxFormat.label} · ${spec.outputLayout.label} · " +
            if (spec.imported) "已下载/导入" else "内置",
        enabled = spec.enabled,
        onToggle = onToggle,
        onClick = onClick,
    )
}

@Composable
private fun ModelCard(name: String, summary: String, enabled: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onClick).padding(16.dp)) {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = "$name 启用" },
            )
        }
    }
}

@Composable
private fun ModelDownloadDialog(
    downloading: Boolean,
    error: String?,
    mirror: GitHubMirrorPreference,
    failedModel: RemoteModel?,
    progress: Pair<Long, Long?>?,
    committing: Boolean,
    onDownload: (RemoteModel) -> Unit,
    onRetryDirect: (RemoteModel) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从远程下载模型") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelCatalog.models.forEach { model ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !downloading) { onDownload(model) },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(model.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = model.url,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (model.note.isNotEmpty()) {
                                Text(
                                    text = model.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                AssetCatalogSection(enabled = !downloading)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("自定义 https 地址") },
                    singleLine = true,
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "下载源：${mirror.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(
                        text = "下载失败：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    failedModel?.let { model ->
                        Text(
                            text = "原始地址：${model.url}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (failedModel != null && rewriteGitHubUrl(failedModel.url, mirror.prefix) != failedModel.url) {
                        TextButton(
                            enabled = !downloading,
                            onClick = { onRetryDirect(failedModel) },
                        ) { Text("直连重试") }
                    }
                }
                if (downloading) {
                    ModelTransferProgress(progress, committing)
                }
                Text(
                    text = "目录模型校验大小与 SHA-256，保留已有配置；下载完成后在模型卡片中设置。" +
                        "自定义 TFLite 下载上限 ${ModelDownload.MAX_BYTES / 1024 / 1024} MB。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading && url.startsWith("https://"),
                onClick = {
                    onDownload(
                        RemoteModel(
                            name = url.substringAfterLast('/').substringBefore('?'),
                            url = url,
                        ),
                    )
                },
            ) { Text("下载") }
        },
        dismissButton = {
            Row {
                if (downloading) TextButton(enabled = !committing, onClick = onCancel) { Text("取消传输") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun ModelEditDialog(
    spec: ModelSpec,
    onSave: (ModelSpec) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var score by remember(spec) { mutableStateOf(formatThreshold(spec.scoreThreshold)) }
    var keypoint by remember(spec) { mutableStateOf(formatThreshold(spec.keypointThreshold)) }
    var debounce by remember(spec) { mutableStateOf(spec.debounceFrames.toString()) }
    var boxFormat by remember(spec) { mutableStateOf(spec.boxFormat) }
    var outputLayout by remember(spec) { mutableStateOf(spec.outputLayout) }
    var anchors by remember(spec) { mutableStateOf(spec.anchors.toString()) }
    var keypoints by remember(spec) { mutableStateOf(spec.keypoints.toString()) }
    val scoreValue = score.toFloatOrNull()?.takeIf { it in 0f..1f }
    val keypointValue = keypoint.toFloatOrNull()?.takeIf { it in 0f..1f }
    val debounceValue = debounce.toIntOrNull()?.takeIf { it in 1..30 }
    val anchorsValue = anchors.toIntOrNull()?.takeIf { it in 1..1_000_000 }
    val keypointsValue = keypoints.toIntOrNull()?.takeIf { it in 1..100 }
    var description by remember(spec.id) { mutableStateOf<GraphDescription?>(null) }
    var loadError by remember(spec.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(spec.id) {
        try {
            description = withContext(Dispatchers.IO) {
                val bytes = spec.assetPath?.let { context.assets.open(it).use { stream -> stream.readBytes() } }
                    ?: modelFile(context, spec).readBytes()
                TfliteGraph(bytes).describe()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            loadError = "无法校验模型文件：${error.message}"
        }
    }
    val updated = if (scoreValue != null && keypointValue != null && debounceValue != null &&
        anchorsValue != null && keypointsValue != null) {
        spec.copy(scoreThreshold = scoreValue, keypointThreshold = keypointValue, debounceFrames = debounceValue,
            boxFormat = boxFormat, outputLayout = outputLayout, anchors = anchorsValue, keypoints = keypointsValue)
    } else null
    val validation = remember(updated, description) {
        updated?.let { candidate -> description?.let { graph -> runCatching { validatePoseModelConfig(candidate, graph) } } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "输入 ${spec.inputSize}×${spec.inputSize} RGB。阈值与框格式可调整；" +
                        "锚点数、关键点数和布局保存前必须与模型文件的输出形状匹配。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                description?.signatures?.firstOrNull()?.outputs?.singleOrNull()?.let {
                    Text("文件输出：${it.shape} ${it.typeName}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = anchors,
                    onValueChange = { anchors = it.filter(Char::isDigit).take(7) },
                    label = { Text("输出锚点数") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keypoints,
                    onValueChange = { keypoints = it.filter(Char::isDigit).take(3) },
                    label = { Text("关键点数（通道 = 5 + 3K）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("输出布局", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    OutputLayout.entries.forEach { layout ->
                        ModelChoiceRow(layout.label, outputLayout == layout) { outputLayout = layout }
                    }
                }
                OutlinedTextField(
                    value = score,
                    onValueChange = { score = it.filterDecimal() },
                    label = { Text("检测置信度阈值（0–1）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keypoint,
                    onValueChange = { keypoint = it.filterDecimal() },
                    label = { Text("关键点阈值（0–1）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = debounce,
                    onValueChange = { debounce = it.filter(Char::isDigit).take(2) },
                    label = { Text("防抖帧数（1–30）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "检测框格式（推断：张量形状无法判断）：同一模型若框位置/大小明显错误，切换为另一种格式",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    BoxFormat.entries.forEach { format ->
                        ModelChoiceRow(format.label, boxFormat == format) { boxFormat = format }
                    }
                }
                (loadError ?: validation?.exceptionOrNull()?.message)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (description == null && loadError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    text = "阈值在模型输出处过滤；防抖帧数越大越稳定，出现与消失的延迟也越高。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = validation?.isSuccess == true,
                onClick = { onSave(checkNotNull(updated)) },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (spec.imported) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ModelChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Prepares immutable bytes/metadata without installing. The accepted commit writes the model;
 * commitModelInstallation then registers the spec without a cancellable dispatcher hand-off.
 */
private class PendingModelImport(val install: () -> Pair<ModelSpec?, String>)

private fun prepareModelImport(
    context: Context,
    displayName: String?,
    bytes: ByteArray,
    boxFormat: BoxFormat? = null,
): PendingModelImport {
    val directory = ConfiguredModelStore.directory(context)
    val id = bytes.sha256()
    if (ConfiguredModelStore.list(directory).any { it.id == id }) {
        return PendingModelImport {
            val existing = ConfiguredModelStore.install(directory, displayName, bytes)
            null to "${existing.name} 已在模型列表中，原有配置与开关已保留"
        }
    }
    val prepared = try {
        PoseModelImport.prepare(bytes)
    } catch (error: IllegalArgumentException) {
        // Inspect before any file is written; fallback is only for the pose contract, never
        // for an exception after successful pose installation/registration.
        val known = TfliteGraph(bytes).describe().knownSummary()
        return PendingModelImport {
            val model = ConfiguredModelStore.install(directory, displayName, bytes)
            null to "已保存 ${model.name}，原始模型与可编辑配置均已保留。文件信息：$known。" +
                "姿态管线不适配：${error.message}。点击模型卡片修改输入/输出配置并运行；" +
                "归一化 mean/std、框含义、labels、tokenizer、采样率属推断或待配置，需按模型说明确认。"
        }
    }
    val patched = if (prepared.staticizedSignatures > 0) {
        "，已自动把 ${prepared.staticizedSignatures} 个张量的动态 shape signature 改为静态"
    } else {
        "，shape signature 已全部为静态（GPU delegate 可用）"
    }
    val known = TfliteGraph(prepared.bytes).describe().knownSummary()
    return PendingModelImport {
        val installed = PoseModelImport.install(context, displayName, prepared)
        val spec = installed.copy(boxFormat = boxFormat ?: installed.boxFormat)
        val base = "已导入 ${spec.name}：输入 ${spec.inputSize}，" +
            "${spec.anchors} anchors，${spec.keypoints} 关键点$patched。" +
            "文件信息：$known。anchor/关键点/输出布局为推断，框格式无法从形状判断，请按模型说明确认。"
        spec to if (prepared.warnings.isEmpty()) base else base + "注意：" + prepared.warnings.joinToString("；")
    }
}

private fun Context.displayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

private fun String.filterDecimal(): String = filter { it.isDigit() || it == '.' }

private fun formatThreshold(value: Float): String = String.format(Locale.US, "%.2f", value)

private const val TAG = "Models"
