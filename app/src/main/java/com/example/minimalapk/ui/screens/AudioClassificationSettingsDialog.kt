package com.example.minimalapk.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.minimalapk.plugins.AUDIO_INPUT_KEY
import com.example.minimalapk.plugins.AUDIO_INPUT_WAV
import com.example.minimalapk.plugins.AUDIO_WAV_URI_KEY
import com.example.minimalapk.plugins.AudioClassificationSettings
import com.example.minimalapk.plugins.PluginSettings
import com.example.minimalapk.plugins.audioUsesMicrophone

@Composable
internal fun AudioClassificationSettingsDialog(
    settings: PluginSettings,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var topKText by rememberSaveable { mutableStateOf(AudioClassificationSettings.from(settings).topKText) }
    var microphone by rememberSaveable { mutableStateOf(audioUsesMicrophone(settings)) }
    var uri by rememberSaveable { mutableStateOf(settings.config[AUDIO_WAV_URI_KEY].orEmpty()) }
    var chooseSource by remember { mutableStateOf(false) }
    var fileNotice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            uri = selected.toString()
            fileNotice = runCatching {
                context.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.exceptionOrNull()?.let { "此文件提供方未允许长期访问，重启后可能需要重新选择。" }
        }
    }
    val valid = AudioClassificationSettings(topKText).topK != null && (microphone || uri.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("声音识别") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("YAMNet 的 521 类说话声、音乐、动物叫声与环境声；只识别声音类别，不转写讲话内容。", style = MaterialTheme.typography.bodySmall)
                Text("首次使用自动下载约 4 MB 模型与配套标签；之后可离线使用。", style = MaterialTheme.typography.bodySmall)
                SettingRow("输入来源", if (microphone) "共享麦克风" else "WAV 文件", onClick = { chooseSource = true })
                if (microphone) {
                    Text("仅在相机前台按需请求录音权限，以 16 kHz 单声道、0.975 秒窗口分类；不保存录音。播放朗读时暂停，离开相机或关闭插件释放麦克风。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("无需录音权限。选择不超过 16 MB 的 PCM16 WAV（单/双声道），返回相机点击“识别 WAV”。只分析开头 0.975 秒，短文件补零；不复制文件到应用存储。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { picker.launch(arrayOf("audio/*", "application/octet-stream")) }) {
                        Text(if (uri.isBlank()) "选择 WAV 文件" else "重新选择 WAV 文件")
                    }
                    if (uri.isNotBlank()) Text("已选择文件", style = MaterialTheme.typography.bodySmall)
                    fileNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedTextField(
                    value = topKText, onValueChange = { topKText = it.filter(Char::isDigit).take(1) },
                    label = { Text("显示类别数") }, singleLine = true,
                    isError = AudioClassificationSettings(topKText).topK == null,
                    supportingText = { Text("1–5；每类显示名称与模型得分") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(settings.config + AudioClassificationSettings(topKText).toConfig() + mapOf(
                    AUDIO_INPUT_KEY to if (microphone) "microphone" else AUDIO_INPUT_WAV,
                    AUDIO_WAV_URI_KEY to uri,
                ))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (chooseSource) OptionDialog(
        title = "声音输入来源", options = listOf(true, false), selected = microphone,
        label = { if (it) "共享麦克风" else "WAV 文件（不录音）" },
        onSelect = { microphone = it; chooseSource = false }, onDismiss = { chooseSource = false },
    )
}
