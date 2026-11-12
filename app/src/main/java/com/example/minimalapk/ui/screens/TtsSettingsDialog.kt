package com.example.minimalapk.ui.screens

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.minimalapk.plugins.PluginSettings
import com.example.minimalapk.plugins.TTS_ENGINE_KEY
import com.example.minimalapk.plugins.TTS_TEXT_KEY
import com.example.minimalapk.plugins.TtsEngine
import com.example.minimalapk.plugins.TtsEngineChoice
import com.example.minimalapk.plugins.clampTtsText
import com.example.minimalapk.plugins.installedTtsEngines

/** onSaveConfig persists the complete config; only onDismiss closes this dialog. */
@Composable
internal fun TtsSettingsDialog(
    settings: PluginSettings,
    enabled: Boolean = settings.enabled,
    onSaveConfig: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val maxText = minOf(TextToSpeech.getMaxSpeechInputLength(), 4096)
    var text by rememberSaveable {
        mutableStateOf(clampTtsText(settings.config[TTS_TEXT_KEY].orEmpty(), maxText))
    }
    var requestedEngine by rememberSaveable { mutableStateOf(settings.config[TTS_ENGINE_KEY].orEmpty()) }
    var showEngines by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf(emptyList<TtsEngineChoice>()) }
    var discoveryError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("正在初始化系统语音引擎…") }
    var ready by remember { mutableStateOf(false) }
    var utterance by remember { mutableStateOf<String?>(null) }
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val holder = remember { arrayOfNulls<TtsEngine>(1) }
    fun release() {
        holder[0]?.close()
        holder[0] = null
        ready = false
        utterance = null
    }
    fun save() = onSaveConfig(settings.config + mapOf(TTS_TEXT_KEY to text, TTS_ENGINE_KEY to requestedEngine))
    fun dismiss() { release(); onDismiss() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    release()
                    foreground = false
                    // Stop/start may coalesce before the next composition; still rebind on return.
                    retry++
                    status = "已停止朗读（页面不在前台）"
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context, foreground, requestedEngine, enabled, retry) {
        ready = false
        utterance = null
        choices = try {
            installedTtsEngines(context).also { discoveryError = null }
        } catch (error: Exception) {
            discoveryError = "无法读取已安装语音引擎：${error.message.orEmpty()}"
            emptyList()
        }
        val engine = if (foreground && enabled) {
            status = "正在初始化系统语音引擎…"
            TtsEngine(context, requestedEngine, onPlayback = { utterance = it }) { message, isReady ->
                status = message
                ready = isReady
            }.also { holder[0] = it }
        } else {
            status = if (enabled) "已停止朗读（页面不在前台）" else "插件已停用"
            null
        }
        onDispose {
            engine?.close()
            if (holder[0] === engine) holder[0] = null
        }
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("语音朗读") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "使用设备 TextToSpeech；是否离线取决于语音包。只在按下朗读时发声，不需要麦克风权限。保存后也可在相机中点击“朗读已保存文本”或“停止朗读”。",
                    style = MaterialTheme.typography.bodySmall,
                )
                SettingRow(
                    title = "语音引擎",
                    subtitle = if (requestedEngine.isEmpty()) "系统默认" else choices
                        .firstOrNull { it.packageName == requestedEngine }?.let { "${it.label}（${it.packageName}）" }
                        ?: "$requestedEngine（未安装或不可用）",
                    onClick = { showEngines = true },
                )
                Text(
                    "优先使用所选引擎；连接失败时 Android 可能回退到系统引擎。",
                    style = MaterialTheme.typography.bodySmall,
                )
                discoveryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { release(); retry++ }) { Text("刷新引擎 / 重试初始化") }
                OutlinedTextField(
                    value = text, onValueChange = { text = clampTtsText(it, maxText) },
                    label = { Text("要朗读的文本") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${text.length} / $maxText 字") },
                )
                if (!enabled) {
                    Text("插件已停用：请先启用总开关再朗读。", color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = enabled && foreground && ready && text.isNotBlank(),
                        onClick = { save(); holder[0]?.speak(text) },
                    ) { Text("朗读") }
                    TextButton(enabled = utterance != null, onClick = { holder[0]?.stop() }) { Text("停止") }
                }
                utterance?.let {
                    Text("当前朗读：${it.take(120)}${if (it.length > 120) "…" else ""}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "切换引擎、离开本页或进入后台会停止朗读。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("朗读申请临时音频焦点；共享麦克风在播放期间暂停，结束后恢复。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { save(); dismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("关闭") } },
    )
    if (showEngines) {
        OptionDialog(
            title = "选择已安装的语音引擎",
            options = listOf("") + choices.map { it.packageName }, selected = requestedEngine,
            label = { name ->
                if (name.isEmpty()) "系统默认" else choices.first { it.packageName == name }.let { "${it.label}（${it.packageName}）" }
            },
            onSelect = { name ->
                if (name != requestedEngine) {
                    release()
                    requestedEngine = name
                    save()
                }
                showEngines = false
            },
            onDismiss = { showEngines = false },
        )
    }
}
