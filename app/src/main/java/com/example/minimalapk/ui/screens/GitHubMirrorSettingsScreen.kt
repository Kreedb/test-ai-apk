package com.example.minimalapk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.minimalapk.pose.GitHubMirrorMode
import com.example.minimalapk.pose.GitHubMirrorPreference
import com.example.minimalapk.pose.normalizeMirrorPrefix

@Composable
internal fun GitHubMirrorSettingsScreen(
    preference: GitHubMirrorPreference,
    onSave: (GitHubMirrorPreference) -> Unit,
    onBack: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(preference.mode) }
    var customPrefix by rememberSaveable { mutableStateOf(preference.customPrefix) }
    val normalizedPrefix = normalizeMirrorPrefix(customPrefix)
    val customValid = mode != GitHubMirrorMode.CUSTOM || normalizedPrefix != null

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = "GitHub 镜像源", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "镜像只改写 GitHub 的 raw 文件与 releases/download 地址，其它来源始终直连。" +
                    "前缀必须是 HTTPS；大小、SHA-256 与 TFLite 结构校验不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.selectableGroup()) {
                    GitHubMirrorMode.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = mode == option,
                                    onClick = { mode = option },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == option, onClick = null)
                            Column(modifier = Modifier.padding(start = 16.dp)) {
                                Text(option.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (mode == GitHubMirrorMode.CUSTOM) {
                OutlinedTextField(
                    value = customPrefix,
                    onValueChange = { customPrefix = it },
                    label = { Text("HTTPS 前缀") },
                    placeholder = { Text("https://mirror.example/") },
                    singleLine = true,
                    isError = customPrefix.isNotBlank() && normalizedPrefix == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (normalizedPrefix != null) {
                        "实际使用：$normalizedPrefix"
                    } else {
                        "请输入以 https:// 开头的有效前缀，不能包含账号、空白、query 或 fragment"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (normalizedPrefix != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Button(
                enabled = customValid,
                onClick = {
                    onSave(GitHubMirrorPreference(mode = mode, customPrefix = customPrefix.trim()))
                },
            ) { Text("保存") }
        }
    }
}
