package com.example.minimalapk.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.minimalapk.ui.theme.ThemeMode
import com.example.minimalapk.ui.theme.ThemeSettings
import com.example.minimalapk.ui.theme.label
import com.example.minimalapk.ui.theme.rememberThemeColorScheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

private val keyColorOptions = listOf(
    Color(0xFFF44336).toArgb() to "红色",
    Color(0xFFE91E63).toArgb() to "粉色",
    Color(0xFF9C27B0).toArgb() to "紫色",
    Color(0xFF673AB7).toArgb() to "深紫色",
    Color(0xFF3F51B5).toArgb() to "靛蓝色",
    Color(0xFF2196F3).toArgb() to "蓝色",
    Color(0xFF00BCD4).toArgb() to "青色",
    Color(0xFF009688).toArgb() to "蓝绿色",
    Color(0xFF4FAF50).toArgb() to "绿色",
    Color(0xFFFFEB3B).toArgb() to "黄色",
    Color(0xFFFFC107).toArgb() to "琥珀色",
    Color(0xFFFF9800).toArgb() to "橙色",
    Color(0xFF795548).toArgb() to "棕色",
    Color(0xFF607D8F).toArgb() to "蓝灰色",
    Color(0xFFFF9CA8).toArgb() to "粉红色",
)

@Composable
fun ThemeSettingsScreen(
    settings: ThemeSettings,
    onSettingsChange: (ThemeSettings) -> Unit,
    onBack: () -> Unit,
) {
    var showStyleDialog by rememberSaveable { mutableStateOf(false) }
    var showSpecDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = "主题设置", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ThemePreview(settings)

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.mode == mode,
                        onClick = { onSettingsChange(settings.copy(mode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size,
                        ),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary,
                            activeBorderColor = MaterialTheme.colorScheme.primary,
                            inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                            inactiveBorderColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        icon = {},
                    ) {
                        Text(text = mode.label())
                    }
                }
            }

            Column {
                Text(text = "主题颜色", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        KeyColorItem(
                            color = null,
                            label = "跟随系统",
                            selected = settings.keyColor == 0,
                            settings = settings,
                            onClick = { onSettingsChange(settings.copy(keyColor = 0)) },
                        )
                    }
                    items(keyColorOptions) { (color, label) ->
                        KeyColorItem(
                            color = Color(color),
                            label = label,
                            selected = settings.keyColor == color,
                            settings = settings,
                            onClick = { onSettingsChange(settings.copy(keyColor = color)) },
                        )
                    }
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(
                        title = "色彩风格",
                        subtitle = settings.paletteStyle.name,
                        onClick = { showStyleDialog = true },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingRow(
                        title = "色彩标准",
                        subtitle = settings.colorSpec.name,
                        onClick = { showSpecDialog = true },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showStyleDialog) {
        OptionDialog(
            title = "色彩风格",
            options = PaletteStyle.entries,
            selected = settings.paletteStyle,
            label = { it.name },
            onSelect = {
                onSettingsChange(settings.copy(paletteStyle = it))
                showStyleDialog = false
            },
            onDismiss = { showStyleDialog = false },
        )
    }

    if (showSpecDialog) {
        OptionDialog(
            title = "色彩标准",
            options = ColorSpec.SpecVersion.entries,
            selected = settings.colorSpec,
            label = { it.name },
            onSelect = {
                onSettingsChange(settings.copy(colorSpec = it))
                showSpecDialog = false
            },
            onDismiss = { showSpecDialog = false },
        )
    }
}

@Composable
private fun ThemePreview(settings: ThemeSettings) {
    val darkTheme = settings.mode.isDark(isSystemInDarkTheme())
    val colorScheme = rememberThemeColorScheme(settings, darkTheme)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "配色预览",
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.primaryContainer),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.secondaryContainer),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.tertiaryContainer),
                )
            }
        }
    }
}

@Composable
private fun KeyColorItem(
    color: Color?,
    label: String,
    selected: Boolean,
    settings: ThemeSettings,
    onClick: () -> Unit,
) {
    val darkTheme = settings.mode.isDark(isSystemInDarkTheme())
    val colorScheme = rememberThemeColorScheme(
        settings.copy(keyColor = color?.toArgb() ?: 0),
        darkTheme,
    )

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surfaceContainer)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colorScheme.primary else colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(32.dp)) {
            drawArc(
                color = colorScheme.primaryContainer,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
            )
            drawArc(
                color = colorScheme.tertiaryContainer,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = true,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

