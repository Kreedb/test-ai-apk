package com.example.minimalapk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun PluginsScreen(
    heightText: String,
    onHeightTextChange: (String) -> Unit,
    showHeightLine: Boolean,
    onShowHeightLineChange: (Boolean) -> Unit,
    showArmSpan: Boolean,
    onShowArmSpanChange: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "插件",
                subtitle = "姿态增强：身高与测量叠加",
            )
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        ) {
                            Text(
                                text = "姿态增强",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "全身入镜并填写身高后，标注身高与臂展",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { onHeightTextChange(it.filter(Char::isDigit).take(3)) },
                        label = { Text("身高 (cm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    PluginSwitchRow(
                        name = "身高线",
                        description = "标注身高计算点与长度",
                        checked = showHeightLine,
                        onCheckedChange = onShowHeightLineChange,
                    )
                    PluginSwitchRow(
                        name = "臂长线",
                        description = "标注左右腕点与臂展长度",
                        checked = showArmSpan,
                        onCheckedChange = onShowArmSpanChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginSwitchRow(
    name: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = name },
        )
    }
}
