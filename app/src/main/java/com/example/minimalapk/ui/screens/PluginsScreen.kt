package com.example.minimalapk.ui.screens

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minimalapk.plugins.PluginIds
import com.example.minimalapk.plugins.PluginSettingsStore
import com.example.minimalapk.plugins.PoseEnhancementSettings

@Composable
internal fun PluginsScreen(store: PluginSettingsStore) {
    val settings by store.settings.collectAsStateWithLifecycle()
    val pose = settings.getValue(PluginIds.POSE_ENHANCEMENT)
    var configure by rememberSaveable { mutableStateOf(false) }
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
    }
    if (configure) {
        PoseEnhancementDialog(PoseEnhancementSettings.from(pose), onSave = {
            store.configure(PluginIds.POSE_ENHANCEMENT, it.toConfig())
            configure = false
        }, onDismiss = { configure = false })
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
internal fun PluginSwitchRow(name: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange, modifier = Modifier.semantics { contentDescription = name })
    }
}
