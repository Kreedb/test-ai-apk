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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.pose.BoxFormat
import com.example.minimalapk.pose.ModelSpec
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.PoseModelImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun ModelsScreen(
    specs: List<ModelSpec>,
    onSpecsChange: (List<ModelSpec>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ModelSpec?>(null) }
    var importing by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { importModel(context, uri) } }
            importing = false
            result.onSuccess { (spec, message) ->
                // Re-importing the same bytes yields the same id, which must replace, not duplicate.
                onSpecsChange(specs.filterNot { it.id == spec.id } + spec)
                status = message
            }.onFailure { error ->
                AppLog.w(TAG, "模型导入失败", error)
                status = "导入失败：${error.message ?: "未知错误"}"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = "模型", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "同帧串行：每帧按顺序运行启用的模型，最多同时启用 " +
                        "${ModelStore.MAX_ENABLED} 个。修改后重新进入相机生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    SettingRow(
                        title = "导入 tflite 模型",
                        subtitle = "支持同族 YOLO-pose 值优先输出，自动识别输入尺寸、anchor 与关键点数",
                        onClick = { if (!importing) launcher.launch(arrayOf("*/*")) },
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
    val scoreValue = score.toFloatOrNull()?.takeIf { it in 0f..1f }
    val keypointValue = keypoint.toFloatOrNull()?.takeIf { it in 0f..1f }
    val debounceValue = debounce.toIntOrNull()?.takeIf { it in 1..30 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    text = "检测框格式：同一模型若框位置/大小明显错误，切换为另一种格式",
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
                enabled = scoreValue != null && keypointValue != null && debounceValue != null,
                onClick = {
                    onSave(
                        spec.copy(
                            scoreThreshold = scoreValue ?: spec.scoreThreshold,
                            keypointThreshold = keypointValue ?: spec.keypointThreshold,
                            debounceFrames = debounceValue ?: spec.debounceFrames,
                            boxFormat = boxFormat,
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

/** Copies the picked model into `filesDir/models` after validating and staticizing its signatures. */
private fun importModel(context: Context, uri: Uri): Pair<ModelSpec, String> {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readCapped(MAX_MODEL_BYTES) }
        ?: error("无法读取所选文件")
    val prepared = PoseModelImport.prepare(bytes)
    val spec = PoseModelImport.install(context, context.displayName(uri), prepared)
    val patched = if (prepared.staticizedSignatures > 0) {
        "，已自动把 ${prepared.staticizedSignatures} 个张量的动态 shape signature 改为静态"
    } else {
        "，shape signature 已全部为静态（GPU delegate 可用）"
    }
    return spec to "已导入 ${spec.name}：输入 ${spec.inputSize}，" +
        "${spec.anchors} anchors，${spec.keypoints} 关键点$patched"
}

/** The picker accepts any file, so bound the read before it becomes a whole-file allocation. */
private fun java.io.InputStream.readCapped(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        if (output.size() > limit) {
            error("模型文件超过 ${limit / 1024 / 1024} MB 上限")
        }
    }
    return output.toByteArray()
}

private fun Context.displayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

private fun String.filterDecimal(): String = filter { it.isDigit() || it == '.' }

private fun formatThreshold(value: Float): String = String.format(Locale.US, "%.2f", value)

private const val TAG = "Models"
private const val MAX_MODEL_BYTES = 64 * 1024 * 1024
