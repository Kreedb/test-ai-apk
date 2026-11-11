package com.example.minimalapk.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minimalapk.BuildConfig
import com.example.minimalapk.R
import com.example.minimalapk.diagnostics.AppSelfCheck
import com.example.minimalapk.diagnostics.SelfCheckState
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.pose.GitHubMirrorPreference
import com.example.minimalapk.pose.PoseBackendPreference
import com.example.minimalapk.ui.theme.ThemeSettings

internal enum class SettingsPage {
    ROOT,
    MIRROR,
    THEME,
    ABOUT,
    LOG,
}

/** Older builds could restore the removed MODELS page; never reopen a deleted destination. */
internal fun settingsPageForRestore(saved: Any?): SettingsPage = when (saved) {
    is SettingsPage -> saved
    is Enum<*> -> SettingsPage.entries.firstOrNull { it.name == saved.name } ?: SettingsPage.ROOT
    is String -> SettingsPage.entries.firstOrNull { it.name == saved } ?: SettingsPage.ROOT
    else -> SettingsPage.ROOT
}

private val SettingsPageSaver = Saver<SettingsPage, Any>(
    save = { it.name },
    restore = { settingsPageForRestore(it) },
)

@Composable
fun ConfigScreen(
    themeSettings: ThemeSettings,
    onThemeSettingsChange: (ThemeSettings) -> Unit,
) {
    val context = LocalContext.current
    var page by rememberSaveable(stateSaver = SettingsPageSaver) { mutableStateOf(SettingsPage.ROOT) }
    var backendPreference by remember { mutableStateOf(PoseBackendPreference.load(context)) }
    var mirrorPreference by remember { mutableStateOf(GitHubMirrorPreference.load(context)) }
    var showBackendDialog by rememberSaveable { mutableStateOf(false) }
    val selfCheck by AppSelfCheck.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var ownedCheck by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                ownedCheck?.let { AppSelfCheck.cancel(it, "应用进入后台") }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ownedCheck?.let { AppSelfCheck.cancel(it, "离开设置页面") }
        }
    }
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    when (page) {
        SettingsPage.ROOT -> SettingsList(
            backendPreference = backendPreference,
            mirrorPreference = mirrorPreference,
            onOpenBackend = { showBackendDialog = true },
            onOpenMirror = { page = SettingsPage.MIRROR },
            onOpenTheme = { page = SettingsPage.THEME },
            onOpenLogs = { page = SettingsPage.LOG },
            onOpenAbout = { page = SettingsPage.ABOUT },
            selfCheck = selfCheck,
            onStartSelfCheck = { AppSelfCheck.start(context)?.let { ownedCheck = it } },
            onCancelSelfCheck = { AppSelfCheck.cancel(selfCheck.runId) },
        )

        SettingsPage.MIRROR -> GitHubMirrorSettingsScreen(
            preference = mirrorPreference,
            onSave = { updated ->
                mirrorPreference = updated
                GitHubMirrorPreference.save(context, updated)
                AppLog.i("Settings", "GitHub 镜像源切换为 ${updated.summary}")
                page = SettingsPage.ROOT
            },
            onBack = { page = SettingsPage.ROOT },
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
                AppLog.i("Settings", "推理后端切换为 ${option.label}，相机将自动更新")
                showBackendDialog = false
            },
            onDismiss = { showBackendDialog = false },
        )
    }
}

@Composable
private fun SettingsList(
    backendPreference: PoseBackendPreference,
    mirrorPreference: GitHubMirrorPreference,
    onOpenBackend: () -> Unit,
    onOpenMirror: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    selfCheck: SelfCheckState,
    onStartSelfCheck: () -> Unit,
    onCancelSelfCheck: () -> Unit,
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

        SelfCheckCard(selfCheck, onStartSelfCheck, onCancelSelfCheck, onOpenLogs)

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
                    subtitle = "${backendPreference.label} · 保存后自动更新相机",
                    onClick = onOpenBackend,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_security),
                            contentDescription = null,
                        )
                    },
                    title = "GitHub 镜像源",
                    subtitle = "${mirrorPreference.summary} · 仅影响 GitHub 模型下载",
                    onClick = onOpenMirror,
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

@Composable
private fun SelfCheckCard(
    state: SelfCheckState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("应用自检", style = MaterialTheme.typography.titleMedium)
            Text(
                "逐项检查内置、已安装及全部目录模型；使用 CPU 合成输入。缺失或不支持的项目记为 SKIP。" +
                    "自检不下载模型、不改训练存档；推理冒烟通过不代表识别准确率或 GPU/NPU 验证。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.running) {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.results.size.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(state.current, style = MaterialTheme.typography.bodySmall)
                Text("${state.results.size}/${state.total} · ${state.summary}", style = MaterialTheme.typography.bodySmall)
            } else if (state.finished) {
                Text("${state.current} · ${state.summary}", style = MaterialTheme.typography.bodyMedium)
            }
            if (state.runId.isNotEmpty()) {
                Text("日志标记：SelfCheck / run=${state.runId}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !state.running) { Text("开始全部模型自检") }
                if (state.running) {
                    TextButton(onClick = onCancel, enabled = !state.cancelling) { Text("取消") }
                }
            }
            TextButton(onClick = onOpenLogs) { Text("查看并分享诊断日志") }
            if (state.running) {
                Text("离开设置或切到后台会取消；当前原生调用返回后释放资源。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
