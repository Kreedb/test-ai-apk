package com.example.minimalapk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.plugins.ExampleSceneSpec
import com.example.minimalapk.plugins.PluginIds
import com.example.minimalapk.plugins.PluginSettings
import com.example.minimalapk.plugins.SCENE_MASK_OPACITY_KEY
import com.example.minimalapk.plugins.SCENE_THRESHOLD_KEY
import com.example.minimalapk.plugins.SceneThresholdSettings
import com.example.minimalapk.plugins.sceneMaskOpacity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ExampleSceneSettingsDialog(
    spec: ExampleSceneSpec,
    settings: PluginSettings,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var thresholdText by rememberSaveable { mutableStateOf(settings.config[SCENE_THRESHOLD_KEY] ?: spec.recommendedThreshold.toString()) }
    var opacity by rememberSaveable { mutableFloatStateOf(sceneMaskOpacity(settings)) }
    var labelQuery by rememberSaveable { mutableStateOf("") }
    var labels by remember { mutableStateOf(emptyList<String>()) }
    var labelStatus by remember { mutableStateOf("标签随固定模型下载；尚未读取") }
    LaunchedEffect(spec.id) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val file = ExampleCatalog.file(context, spec.example)
                require(file.isFile && file.length() == spec.example.sizeBytes) { "模型未缓存，首次启用时下载权重与标签" }
                ExampleDownload.labels(file, spec.example)
            }
        }
        labels = result.getOrDefault(emptyList())
        labelStatus = result.exceptionOrNull()?.message ?: "已读取 ${labels.size} 个模型标签"
    }
    val valid = !spec.thresholdEnabled || SceneThresholdSettings(thresholdText).threshold != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(spec.description, style = MaterialTheme.typography.bodySmall)
                Text("模型：${spec.example.name}（约 ${spec.example.sizeBytes / 1024 / 1024} MB）", style = MaterialTheme.typography.titleSmall)
                if (spec.thresholdEnabled) OutlinedTextField(
                    value = thresholdText, onValueChange = { thresholdText = it.filter { char -> char.isDigit() || char == '.' }.take(4) },
                    label = { Text("模型得分阈值") }, singleLine = true, isError = !valid,
                    supportingText = { Text("0.05–0.95；调高可减少不确定的结果") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                if (spec.id == PluginIds.IMAGE_SEGMENTATION_SCENE) {
                    Text("蒙版不透明度：${(opacity * 100).toInt()}%")
                    Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.15f..0.85f)
                    Text("只调整显示，不把 DeepLab 原始响应当作概率阈值。", style = MaterialTheme.typography.bodySmall)
                }
                if (spec.example.labelFile != null) {
                    Text("已知类别范围 · $labelStatus", style = MaterialTheme.typography.titleSmall)
                    if (labels.isNotEmpty()) {
                        OutlinedTextField(value = labelQuery, onValueChange = { labelQuery = it.take(80) },
                            label = { Text("搜索模型标签") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val matched = labels.filter { it.contains(labelQuery, ignoreCase = true) }
                        Text(matched.take(20).joinToString("、").ifBlank { "无匹配标签" }, style = MaterialTheme.typography.bodySmall)
                        if (matched.size > 20) Text("共 ${matched.size} 项，先显示 20 项；输入关键词缩小范围", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("启用后返回相机查看结果；首次自动下载并校验，完成后可离线使用。关闭插件会撤回对应结果并释放模型。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(settings.config +
                    (if (spec.thresholdEnabled) SceneThresholdSettings(thresholdText).toConfig() else emptyMap()) +
                    (if (spec.id == PluginIds.IMAGE_SEGMENTATION_SCENE) mapOf(SCENE_MASK_OPACITY_KEY to opacity.toString()) else emptyMap()))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
