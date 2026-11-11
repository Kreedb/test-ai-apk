package com.example.minimalapk.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.pose.BoxFormat
import com.example.minimalapk.pose.GitHubMirrorMode
import com.example.minimalapk.pose.GitHubMirrorPreference
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun ModelsScreen(
    specs: List<ModelSpec>,
    onSpecsChange: (List<ModelSpec>) -> Unit,
    onBack: () -> Unit,
    title: String = "模型",
    header: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mirrorPreference = remember { GitHubMirrorPreference.load(context) }
    var status by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ModelSpec?>(null) }
    var importing by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var lastAttempt by remember { mutableStateOf<RemoteModel?>(null) }
    var showWorkbench by remember { mutableStateOf(false) }
    // Imports run for seconds; use the latest list so a toggle made meanwhile is not discarded.
    val currentSpecs by rememberUpdatedState(specs)

    BackHandler(enabled = showWorkbench) { showWorkbench = false }
    if (showWorkbench) {
        ModelWorkbenchScreen(onBack = { showWorkbench = false })
        return
    }

    fun applyImport(spec: ModelSpec, message: String) {
        // Re-importing the same bytes yields the same id, which must replace, not duplicate.
        onSpecsChange(currentSpecs.filterNot { it.id == spec.id } + spec)
        status = message
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readCapped(ModelDownload.MAX_BYTES) }
                        ?: error("无法读取所选文件")
                    installOrKeepModel(context, context.displayName(uri), bytes)
                }
            }
            importing = false
            result.onSuccess { (spec, message) -> if (spec != null) applyImport(spec, message) else status = message }
                .onFailure { error ->
                    AppLog.w(TAG, "模型导入失败", error)
                    status = "导入失败：${error.message ?: "未知错误"}"
                }
        }
    }

    fun download(model: RemoteModel, preference: GitHubMirrorPreference) {
        importing = true
        downloadError = null
        lastAttempt = model
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ModelDownload.fetch(model.url, preference)
                    ModelDownload.validateDownloaded(bytes, model.sizeBytes, model.sha256)
                    val (spec, message) = installOrKeepModel(context, model.name, bytes)
                    // The catalog knows the box channel order, which the tensor shape cannot reveal.
                    spec?.copy(boxFormat = model.boxFormat ?: spec.boxFormat) to message
                }
            }
            importing = false
            result.onSuccess { (spec, message) ->
                if (spec != null) applyImport(spec, message) else status = message
                showDownload = false
            }.onFailure { error ->
                AppLog.w(TAG, "模型下载失败", error)
                downloadError = error.message ?: "未知错误"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = title, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            header?.let { pluginHeader -> item { pluginHeader() } }
            item {
                Text(
                    text = "同帧串行：每帧按顺序运行启用的模型，最多同时启用 " +
                        "${ModelStore.MAX_ENABLED} 个。保存后相机自动更新，加载期间保留基础预览。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    SettingRow(
                        title = "模型工作台与配置文件",
                        subtitle = "非 YOLO 模型、多输入/输出、量化与动态形状；编辑配置后运行",
                        onClick = { if (!importing) showWorkbench = true },
                    )
                }
            }
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    SettingRow(
                        title = "导入 tflite 模型",
                        subtitle = "YOLO 姿态自动配置；其它模型保存到工作台，通过配置文件修正",
                        onClick = { if (!importing) launcher.launch(arrayOf("*/*")) },
                    )
                }
            }
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    SettingRow(
                        title = "从远程下载模型",
                        subtitle = "官方模型目录，或粘贴任意 TFLite 的 https 地址（仅 YOLO26n 内置）",
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
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                        if (enabled && specs.count { it.enabled } >= ModelStore.MAX_ENABLED) {
                            status = "最多同时启用 ${ModelStore.MAX_ENABLED} 个模型，请先停用其它模型"
                        } else {
                            status = null
                            onSpecsChange(
                                specs.map { if (it.id == spec.id) it.copy(enabled = enabled) else it },
                            )
                        }
                    },
                    onClick = { editing = spec },
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
            onDownload = { model -> download(model, mirrorPreference) },
            onRetryDirect = { model ->
                download(model, GitHubMirrorPreference(mode = GitHubMirrorMode.DIRECT))
            },
            onDismiss = { if (!importing) showDownload = false },
        )
    }

    editing?.let { spec ->
        ModelEditDialog(
            spec = spec,
            onSave = { updated ->
                onSpecsChange(specs.map { if (it.id == updated.id) updated else it })
                editing = null
            },
            onDelete = {
                PoseModelImport.delete(context, spec)
                onSpecsChange(specs.filterNot { it.id == spec.id })
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ModelCard(spec: ModelSpec, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = spec.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${spec.inputSize}×${spec.inputSize} · ${spec.anchors} anchors · " +
                        "${spec.keypoints} 关键点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "置信度 ${formatThreshold(spec.scoreThreshold)} · " +
                        "关键点 ${formatThreshold(spec.keypointThreshold)} · " +
                        "防抖 ${spec.debounceFrames} 帧 · " +
                        spec.boxFormat.label + " · " +
                        spec.outputLayout.label + " · " +
                        if (spec.imported) "导入" else "内置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = spec.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = spec.name },
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
    onDownload: (RemoteModel) -> Unit,
    onRetryDirect: (RemoteModel) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("下载模型") },
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
                    if (failedModel != null && !mirror.isDirect) {
                        TextButton(
                            enabled = !downloading,
                            onClick = { onRetryDirect(failedModel) },
                        ) { Text("直连重试") }
                    }
                }
                if (downloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = "下载后与本地导入使用同一套校验；anchor、关键点、布局与框格式可在编辑页修正。",
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
            TextButton(enabled = !downloading, onClick = onDismiss) { Text("关闭") }
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
    // Bundled models rebuild these from the asset, so only imports can be corrected here.
    val contractValid = !spec.imported || (anchorsValue != null && keypointsValue != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (spec.imported) {
                    Text(
                        text = "模型配置：张量名称、形状、dtype、量化与签名来自模型文件；" +
                            "anchor、关键点数与输出布局按形状推断，框格式无法从形状判断，均可在下方修正。" +
                            "输入尺寸由静态输入形状决定，不在此处修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = anchors,
                        onValueChange = { anchors = it.filter(Char::isDigit).take(7) },
                        label = { Text("输出 anchor 数（推断）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = keypoints,
                        onValueChange = { keypoints = it.filter(Char::isDigit).take(3) },
                        label = { Text("关键点数（推断，输出通道 = 5 + 3K）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(
                        text = "输出布局（推断，可修正）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutputLayout.entries.forEach { layout ->
                            FilterChip(
                                selected = outputLayout == layout,
                                onClick = { outputLayout = layout },
                                label = { Text(layout.label) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = score,
                    onValueChange = { score = it.filterDecimal() },
                    label = { Text("检测置信度阈值（0–1）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = keypoint,
                    onValueChange = { keypoint = it.filterDecimal() },
                    label = { Text("关键点阈值（0–1）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = debounce,
                    onValueChange = { debounce = it.filter(Char::isDigit).take(2) },
                    label = { Text("防抖帧数（1–30）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    text = "检测框格式（推断：张量形状无法判断）：同一模型若框位置/大小明显错误，切换为另一种格式",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoxFormat.entries.forEach { format ->
                        FilterChip(
                            selected = boxFormat == format,
                            onClick = { boxFormat = format },
                            label = { Text(format.label) },
                        )
                    }
                }
                Text(
                    text = "阈值在模型输出处过滤；防抖帧数越大越稳定，出现与消失的延迟也越高。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = scoreValue != null && keypointValue != null &&
                    debounceValue != null && contractValid,
                onClick = {
                    onSave(
                        spec.copy(
                            scoreThreshold = scoreValue ?: spec.scoreThreshold,
                            keypointThreshold = keypointValue ?: spec.keypointThreshold,
                            debounceFrames = debounceValue ?: spec.debounceFrames,
                            boxFormat = boxFormat,
                            outputLayout = outputLayout,
                            anchors = anchorsValue ?: spec.anchors,
                            keypoints = keypointsValue ?: spec.keypoints,
                        ),
                    )
                },
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

/**
 * Validates and staticizes [bytes], copies them into `filesDir/models`, and returns the installed
 * spec plus a status line that lists whatever the user should still confirm in the editor.
 */
private fun installModel(
    context: Context,
    displayName: String?,
    bytes: ByteArray,
): Pair<ModelSpec, String> {
    val prepared = PoseModelImport.prepare(bytes)
    val spec = PoseModelImport.install(context, displayName, prepared)
    val patched = if (prepared.staticizedSignatures > 0) {
        "，已自动把 ${prepared.staticizedSignatures} 个张量的动态 shape signature 改为静态"
    } else {
        "，shape signature 已全部为静态（GPU delegate 可用）"
    }
    val known = TfliteGraph(prepared.bytes).describe().knownSummary()
    val base = "已导入 ${spec.name}：输入 ${spec.inputSize}，" +
        "${spec.anchors} anchors，${spec.keypoints} 关键点$patched。" +
        "文件信息：$known。anchor/关键点/输出布局为推断，框格式无法从形状判断，请按模型说明确认。"
    return if (prepared.warnings.isEmpty()) {
        spec to base
    } else {
        spec to base + "注意：" + prepared.warnings.joinToString("；")
    }
}

private fun installOrKeepModel(context: Context, name: String?, bytes: ByteArray): Pair<ModelSpec?, String> {
    return try {
        installModel(context, name, bytes)
    } catch (error: IllegalArgumentException) {
        val model = ConfiguredModelStore.install(ConfiguredModelStore.directory(context), name, bytes)
        val known = ConfiguredModelStore.describe(model).knownSummary()
        null to "已保存 ${model.name} 到模型工作台，原始模型与可编辑配置均已保留。文件信息：$known。" +
            "姿态管线不适配：${error.message}。打开“模型工作台与配置文件”修改输入/输出配置并运行；" +
            "归一化 mean/std、框含义、labels、tokenizer、采样率属推断或待配置，需按模型说明确认。"
    }
}

private fun Context.displayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

private fun String.filterDecimal(): String = filter { it.isDigit() || it == '.' }

private fun formatThreshold(value: Float): String = String.format(Locale.US, "%.2f", value)

private const val TAG = "Models"
