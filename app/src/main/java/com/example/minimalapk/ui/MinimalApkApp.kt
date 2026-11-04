package com.example.minimalapk.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.minimalapk.ui.screens.CameraScreen
import com.example.minimalapk.ui.screens.ConfigScreen
import com.example.minimalapk.ui.screens.PluginsScreen
import com.example.minimalapk.ui.screens.StatusScreen

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("状态", Icons.Filled.Home),
    Tab("相机", Icons.Filled.PhotoCamera),
    Tab("插件", Icons.Filled.Extension),
    Tab("配置", Icons.Filled.Settings),
)

@Composable
fun MinimalApkApp() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
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
            when (selectedTab) {
                0 -> StatusScreen()
                1 -> CameraScreen()
                2 -> PluginsScreen()
                3 -> ConfigScreen()
            }
        }
    }
}
