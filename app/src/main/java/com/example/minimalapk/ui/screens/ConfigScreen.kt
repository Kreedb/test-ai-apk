package com.example.minimalapk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.minimalapk.BuildConfig

@Composable
fun ConfigScreen() {
    var autoUpdate by rememberSaveable { mutableStateOf(true) }
    var verboseLog by rememberSaveable { mutableStateOf(false) }
    var endpoint by rememberSaveable { mutableStateOf("https://example.com/api") }
    var saved by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = "配置",
            subtitle = "调整模板的默认行为",
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                SwitchRow(
                    title = "自动检查更新",
                    subtitle = "启动时检查是否存在新版本",
                    checked = autoUpdate,
                    onCheckedChange = {
                        autoUpdate = it
                        saved = false
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchRow(
                    title = "详细日志",
                    subtitle = "记录更多调试信息",
                    checked = verboseLog,
                    onCheckedChange = {
                        verboseLog = it
                        saved = false
                    },
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "服务端地址",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        saved = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Endpoint") },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { saved = true },
                enabled = endpoint.isNotBlank(),
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = {
                    autoUpdate = true
                    verboseLog = false
                    endpoint = "https://example.com/api"
                    saved = false
                },
            ) {
                Text("重置")
            }
        }

        if (saved) {
            Text(
                text = "配置已保存",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "关于",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "使用 GitHub Actions 自动构建 APK。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
