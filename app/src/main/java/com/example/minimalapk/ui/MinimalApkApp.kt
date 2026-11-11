package com.example.minimalapk.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.minimalapk.R
import com.example.minimalapk.plugins.PluginSettingsStore
import com.example.minimalapk.ui.screens.CameraScreen
import com.example.minimalapk.ui.screens.ConfigScreen
import com.example.minimalapk.ui.screens.PluginsScreen
import com.example.minimalapk.ui.screens.StatusScreen
import com.example.minimalapk.ui.theme.ThemeSettings

private class Tab(
    val label: String,
    val icon: @Composable () -> Unit,
)

private const val HOME_TAB = 0
private const val CAMERA_TAB = 1
private const val PLUGINS_TAB = 2
private const val SETTINGS_TAB = 3

private val tabs = listOf(
    Tab("主页") { Icon(Icons.Filled.Home, contentDescription = "主页") },
    Tab("相机") { Icon(painterResource(R.drawable.ic_camera), contentDescription = "相机") },
    Tab("插件") { Icon(Icons.Filled.Build, contentDescription = "插件") },
    Tab("设置") { Icon(Icons.Filled.Settings, contentDescription = "设置") },
)

/** Maps a tab index saved by an older build. The removed 示例 tab (2) and unknown values fall
 * back to 主页; the old 插件 (3) and 设置 (4) positions move to the four-tab layout. */
internal fun tabForRestoredIndex(saved: Any?): Int {
    val previous = when (saved) {
        is Int -> saved
        is Number -> saved.toInt()
        else -> return HOME_TAB
    }
    return when (previous) {
        0 -> HOME_TAB
        1 -> CAMERA_TAB
        3 -> PLUGINS_TAB
        4 -> SETTINGS_TAB
        else -> HOME_TAB
    }
}

private const val TAB_STATE_PREFIX = "v2:"

/**
 * Current saves carry a version prefix so the four-tab indices 2/3 are not mistaken for the legacy
 * 示例 (2) and 插件 (3) layout. Values without the prefix are legacy and go through
 * [tabForRestoredIndex].
 */
internal fun tabForRestoredState(saved: Any?): Int = when {
    saved is String && saved.startsWith(TAB_STATE_PREFIX) ->
        saved.removePrefix(TAB_STATE_PREFIX).toIntOrNull()
            ?.takeIf { it in HOME_TAB..SETTINGS_TAB } ?: HOME_TAB
    else -> tabForRestoredIndex(saved)
}

/** Page-local forms/dialogs must never reuse the old numeric 示例/插件/设置 keys. These names
 * remain stable when switching tabs or restoring the selected tab; durable user data is separate. */
internal fun tabPageStateKey(index: Int): String = when (index) {
    HOME_TAB -> "page:v2:home"
    CAMERA_TAB -> "page:v2:camera"
    PLUGINS_TAB -> "page:v2:plugins"
    SETTINGS_TAB -> "page:v2:settings"
    else -> error("Unknown tab $index")
}

private val TabIndexSaver = Saver<Int, Any>(
    save = { "$TAB_STATE_PREFIX$it" },
    restore = { tabForRestoredState(it) },
)

@Composable
fun MinimalApkApp(
    themeSettings: ThemeSettings,
    onThemeSettingsChange: (ThemeSettings) -> Unit,
) {
    var selectedTab by rememberSaveable(stateSaver = TabIndexSaver) { mutableStateOf(HOME_TAB) }
    val context = LocalContext.current
    val plugins = remember(context) { PluginSettingsStore(context) }
    DisposableEffect(plugins) { onDispose { plugins.close() } }
    val stateHolder = rememberSaveableStateHolder()

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { tab.icon() },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            stateHolder.SaveableStateProvider(tabPageStateKey(selectedTab)) {
                when (selectedTab) {
                    HOME_TAB -> StatusScreen()
                    CAMERA_TAB -> CameraScreen(plugins)
                    PLUGINS_TAB -> PluginsScreen(plugins)
                    SETTINGS_TAB -> ConfigScreen(
                        themeSettings = themeSettings,
                        onThemeSettingsChange = onThemeSettingsChange,
                    )
                }
            }
        }
    }
}
