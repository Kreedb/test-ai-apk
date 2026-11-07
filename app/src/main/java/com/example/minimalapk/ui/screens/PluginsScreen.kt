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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private data class Feature(
    val name: String,
    val description: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

@Composable
fun PluginsScreen(
    showPose: Boolean,
    onShowPoseChange: (Boolean) -> Unit,
    showHeightLine: Boolean,
    onShowHeightLineChange: (Boolean) -> Unit,
    showArmSpan: Boolean,
    onShowArmSpanChange: (Boolean) -> Unit,
) {
    val features = listOf(
        Feature(
            name = "姿态识别",
            description = "在预览中显示骨骼关键点与检测框",
            checked = showPose,
            onCheckedChange = onShowPoseChange,
        ),
        Feature(
            name = "身高线",
            description = "全身入镜并填写身高后，标注身高计算点与长度",
            checked = showHeightLine,
            onCheckedChange = onShowHeightLineChange,
        ),
        Feature(
            name = "臂长线",
            description = "全身入镜并填写身高后，标注左右腕点与臂展长度",
            checked = showArmSpan,
            onCheckedChange = onShowArmSpanChange,
        ),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "插件",
                subtitle = "控制预览画面的推理与测量叠加",
            )
        }

        itemsIndexed(features) { _, feature ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                            text = feature.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = feature.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Switch(
                        checked = feature.checked,
                        onCheckedChange = feature.onCheckedChange,
                        modifier = Modifier.semantics { contentDescription = feature.name },
                    )
                }
            }
        }
    }
}
