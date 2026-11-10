package com.example.minimalapk.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.example.minimalapk.R
import com.example.minimalapk.ui.screens.CameraScreen
import com.example.minimalapk.ui.screens.ConfigScreen
import com.example.minimalapk.ui.screens.ExamplesScreen
import com.example.minimalapk.ui.screens.PluginsScreen
import com.example.minimalapk.ui.screens.StatusScreen
import com.example.minimalapk.ui.theme.ThemeSettings

private class Tab(
    val label: String,
    val icon: @Composable () -> Unit,
)

private val tabs = listOf(
    Tab("主页") { Icon(Icons.Filled.Home, contentDescription = "主页") },
    Tab("相机") { Icon(painterResource(R.drawable.ic_camera), contentDescription = "相机") },
    Tab("示例") { Icon(Icons.Filled.PlayArrow, contentDescription = "示例") },
    Tab("插件") { Icon(Icons.Filled.Build, contentDescription = "插件") },
    Tab("设置") { Icon(Icons.Filled.Settings, contentDescription = "设置") },
)

@Composable
fun MinimalApkApp(
    themeSettings: ThemeSettings,
    onThemeSettingsChange: (ThemeSettings) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showPose by rememberSaveable { mutableStateOf(true) }
    var showHeightLine by rememberSaveable { mutableStateOf(true) }
    var showArmSpan by rememberSaveable { mutableStateOf(false) }
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
            stateHolder.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    0 -> StatusScreen()
                    1 -> CameraScreen(
                        showPose = showPose,
                        showHeightLine = showHeightLine,
                        showArmSpan = showArmSpan,
                    )
                    2 -> ExamplesScreen()
                    3 -> PluginsScreen(
                        showPose = showPose,
                        onShowPoseChange = { showPose = it },
                        showHeightLine = showHeightLine,
                        onShowHeightLineChange = { showHeightLine = it },
                        showArmSpan = showArmSpan,
                        onShowArmSpanChange = { showArmSpan = it },
                    )
                    4 -> ConfigScreen(
                        themeSettings = themeSettings,
                        onThemeSettingsChange = onThemeSettingsChange,
                    )
                }
            }
        }
    }
}
