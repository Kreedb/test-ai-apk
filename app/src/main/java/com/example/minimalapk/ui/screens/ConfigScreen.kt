package com.example.minimalapk.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.minimalapk.BuildConfig
import com.example.minimalapk.R
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.pose.PoseBackendPreference
import com.example.minimalapk.ui.theme.ThemeSettings

private enum class SettingsPage {
    ROOT,
    THEME,
    ABOUT,
    LOG,
}

@Composable
fun ConfigScreen(
    themeSettings: ThemeSettings,
    onThemeSettingsChange: (ThemeSettings) -> Unit,
) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    var backendPreference by remember { mutableStateOf(PoseBackendPreference.load(context)) }
    var showBackendDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    when (page) {
        SettingsPage.ROOT -> SettingsList(
            backendPreference = backendPreference,
            onOpenBackend = { showBackendDialog = true },
            onOpenTheme = { page = SettingsPage.THEME },
            onOpenLogs = { page = SettingsPage.LOG },
            onOpenAbout = { page = SettingsPage.ABOUT },
        )

        SettingsPage.THEME -> ThemeSettingsScreen(
            settings = themeSettings,
            onSettingsChange = onThemeSettingsChange,
            onBack = { page = SettingsPage.ROOT },
        )

        SettingsPage.ABOUT -> AboutScreen(onBack = { page = SettingsPage.ROOT })

        SettingsPage.LOG -> LogScreen(onBack = { page = SettingsPage.ROOT })
    }

    if (showBackendDialog) {
        OptionDialog(
            title = "推理后端",
            options = PoseBackendPreference.entries,
            selected = backendPreference,
            label = { it.label },
            onSelect = { option ->
                backendPreference = option
                PoseBackendPreference.save(context, option)
                AppLog.i("Settings", "推理后端切换为 ${option.label}，重新进入相机后生效")
                showBackendDialog = false
            },
            onDismiss = { showBackendDialog = false },
        )
    }
}

@Composable
private fun SettingsList(
    backendPreference: PoseBackendPreference,
    onOpenBackend: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = "设置",
            subtitle = "主题、推理与关于",
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingRow(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_developer_board),
                            contentDescription = null,
                        )
                    },
                    title = "推理后端",
                    subtitle = "${backendPreference.label} · 重新进入相机生效",
                    onClick = onOpenBackend,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    title = "日志",
                    subtitle = "查看推理后端与相机运行日志",
                    onClick = onOpenLogs,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_palette),
                            contentDescription = null,
                        )
                    },
                    title = "主题设置",
                    subtitle = "自定义更多主题选项",
                    onClick = onOpenTheme,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    title = "关于",
                    subtitle = "版本 ${BuildConfig.VERSION_NAME}",
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
