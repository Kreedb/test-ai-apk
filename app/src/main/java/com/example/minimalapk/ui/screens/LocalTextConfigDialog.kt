package com.example.minimalapk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.minimalapk.litert.lm.LocalTextModels
import com.example.minimalapk.litert.lm.LocalTextOptions
import com.example.minimalapk.litert.lm.LocalTextTask
import com.example.minimalapk.litert.lm.strictUtf8Bytes
import com.example.minimalapk.plugins.PluginSettings

/** Hook into the existing plugin configuration list, not a second chat/model-management screen. */
@Composable
internal fun LocalTextConfigDialog(
    settings: PluginSettings,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    val defaults = LocalTextOptions()
    var taskName by rememberSaveable { mutableStateOf(settings.config["task"] ?: defaults.task.name) }
    var text by rememberSaveable { mutableStateOf(settings.config["text"] ?: defaults.text) }
    var language by rememberSaveable { mutableStateOf(settings.config["target_language"] ?: defaults.targetLanguage) }
    var outputLimit by rememberSaveable { mutableStateOf(settings.config["max_output_tokens"] ?: "128") }
    val config = mapOf(
        "model_id" to LocalTextModels.QWEN_ID, "task" to taskName, "text" to text,
        "target_language" to language, "max_output_tokens" to outputLimit,
    )
    val error = runCatching { LocalTextOptions.from(config).prepare() }.exceptionOrNull()?.message
    val bytes = runCatching { strictUtf8Bytes(text) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地文本建议") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Qwen2.5-Coder-1.5B-Instruct · int4 · Apache-2.0", style = MaterialTheme.typography.titleSmall)
                Text("面向代码建议，兼顾短问答、摘要和翻译。模型资源约 1.04 GiB，使用 CPU、1024-token 窗口；至少需要 2560 MiB 当前可用内存。",
                    style = MaterialTheme.typography.bodySmall)
                Text("内存门槛依据 Linux CPU 多次全新引擎峰值向上取整为 2 GiB，另加 512 MiB；使用引擎内共享权重缓存，不是 Android 内存保证。",
                    style = MaterialTheme.typography.bodySmall)
                Text("每次生成另需 1536 MiB 可用存储，用于私有编译缓存；引擎关闭后删除。模型权重仍由同一资源目录管理。",
                    style = MaterialTheme.typography.bodySmall)
                LocalTextTask.entries.forEach { task ->
                    FilterChip(selected = taskName == task.name, onClick = { taskName = task.name }, label = { Text(task.label) })
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("输入文本 / 问题") },
                    minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${bytes ?: "无效"} UTF-8 字节；最多 768，另受模板与输出预算限制") },
                )
                if (taskName == LocalTextTask.TRANSLATION.name) {
                    OutlinedTextField(
                        value = language, onValueChange = { language = it }, label = { Text("目标语言，例如 Chinese / English") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = outputLimit, onValueChange = { outputLimit = it }, label = { Text("最大输出 tokens（16–256）") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("先在同一模型资源目录下载，再回相机点击“生成配置文本”。逐步显示结果；取消、后台和停用都会停止发布。每次使用全新引擎，生成的代码仅供查看。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = error == null, onClick = { onConfirm(config) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
