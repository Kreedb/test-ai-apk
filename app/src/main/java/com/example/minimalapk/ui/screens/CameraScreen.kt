package com.example.minimalapk.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.plugins.CameraModelSettingsStore
import com.example.minimalapk.plugins.CameraSession
import com.example.minimalapk.plugins.ConfiguredModelPlugin
import com.example.minimalapk.plugins.MessageKind
import com.example.minimalapk.plugins.PluginContribution
import com.example.minimalapk.plugins.PluginHost
import com.example.minimalapk.plugins.PluginSettingsStore
import com.example.minimalapk.plugins.PoseEnhancementPlugin
import com.example.minimalapk.plugins.PoseModelsPlugin
import com.example.minimalapk.plugins.SystemMonitorPlugin
import com.example.minimalapk.plugins.cameraFrameRate
import com.example.minimalapk.plugins.cameraPluginSettings
import kotlinx.coroutines.flow.combine

@Composable
internal fun CameraScreen(store: PluginSettingsStore) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        requested = true
        granted = result
        permanentlyDenied = context.cameraPermanentlyDenied(result, requested = true)
    }
    LifecycleResumeEffect(Unit) {
        granted = context.hasCameraPermission()
        permanentlyDenied = context.cameraPermanentlyDenied(granted, requested)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("相机", "离线姿态与插件预览")
        if (granted) {
            PoseCamera(store, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)))
        } else {
            Column(
                Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(if (permanentlyDenied) "相机权限已被拒绝，请在系统设置中开启" else "需要相机权限才能预览")
                Button(modifier = Modifier.padding(top = 12.dp), onClick = {
                    if (permanentlyDenied) context.openAppSettings() else launcher.launch(Manifest.permission.CAMERA)
                }) { Text(if (permanentlyDenied) "打开设置" else "授予权限") }
            }
        }
    }
}

@Composable
private fun PoseCamera(store: PluginSettingsStore, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val models = remember(context, lifecycleOwner) { CameraModelSettingsStore(context) }
    val poses = remember(context, lifecycleOwner) { PoseModelsPlugin(context.applicationContext) }
    val host = remember(context, lifecycleOwner) {
        PluginHost(listOf(
            poses, PoseEnhancementPlugin(), ConfiguredModelPlugin(context.applicationContext),
            SystemMonitorPlugin(),
        ))
    }
    val camera = remember(host, lifecycleOwner) { CameraSession(context, lifecycleOwner, host, poses) }
    val snapshot by host.snapshot.collectAsStateWithLifecycle()
    val error by camera.error.collectAsStateWithLifecycle()

    DisposableEffect(camera) {
        camera.start()
        onDispose { camera.close(); models.close() }
    }
    LaunchedEffect(host, store, models) {
        combine(store.settings, models.settings, ConfiguredModelStore.revision) { plugins, base, revision ->
            cameraPluginSettings(plugins, base, revision)
        }.collect { host.configure(it) }
    }

    Box(modifier.onSizeChanged { camera.viewport(it.width, it.height) }.pluginPressInput(host)) {
        val frame = snapshot.frame
        val image = snapshot.displayedImage
        if (image != null && frame != null) {
            Image(image.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Never draw current-frame bones on an older replacement snapshot.
            if (snapshot.displayedSource == frame.source) {
                BasePoseOverlay(frame.poses.firstOrNull(), frame.source, Modifier.fillMaxSize())
            }
            (snapshot.layers + snapshot.monitors).forEach { contribution ->
                contribution.output.layers.forEach { layer ->
                    layer.image?.let {
                        Image(it.bitmap.asImageBitmap(), "${contribution.name} 输出", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    PluginLineOverlay(layer, Modifier.fillMaxSize())
                }
            }
            MonitorPanel(snapshot.monitors, Modifier.align(Alignment.TopEnd).padding(8.dp))
        } else if (error == null) {
            Text("正在启动相机…", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)
        }
        error?.let {
            Text(it, Modifier.align(Alignment.Center).padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        // Monitor metric lines stay in the top-right panel; its STATUS/ERROR lines (for example the
        // "测试全部" NPU diagnosis) and every other plugin's text share the copyable bottom area.
        val monitorIds = snapshot.monitors.mapTo(mutableSetOf()) { it.pluginId }
        val reports = snapshot.reports.mapNotNull { report ->
            val messages = if (report.pluginId in monitorIds) {
                report.output.messages.filter { it.kind != MessageKind.TEXT }
            } else {
                report.output.messages
            }
            messages.takeIf { it.isNotEmpty() }?.let { report to it }
        }
        if (reports.isNotEmpty()) {
            SelectionContainer(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp)
                        .background(Color.Black.copy(alpha = 0.55f)).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    reports.forEach { (report, messages) ->
                        messages.forEach { message ->
                            Text(
                                "${report.name}：${message.text}", style = MaterialTheme.typography.bodySmall,
                                color = when (message.kind) {
                                    MessageKind.ERROR -> Color(0xFFFF8A80)
                                    MessageKind.STATUS -> Color(0xFFBDBDBD)
                                    MessageKind.TEXT -> Color.White
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorPanel(monitors: List<PluginContribution>, modifier: Modifier = Modifier) {
    val lines = monitors.flatMap { it.output.messages }.filter { it.kind == MessageKind.TEXT }
    if (lines.isNotEmpty()) {
        Column(
            modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lines.forEach { Text(it.text, style = MaterialTheme.typography.labelSmall, color = Color.White) }
        }
    }
}

internal fun frameRate(timestamps: ArrayDeque<Long>, nowNanos: Long): Float =
    cameraFrameRate(timestamps, nowNanos, FPS_WINDOW_NANOS)

private const val FPS_WINDOW_NANOS = 2_000_000_000L

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.cameraPermanentlyDenied(granted: Boolean, requested: Boolean): Boolean = isCameraPermanentlyDenied(
    granted, requested, findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
)

internal fun isCameraPermanentlyDenied(granted: Boolean, requested: Boolean, shouldShowRationale: Boolean?): Boolean =
    !granted && requested && shouldShowRationale == false

private fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
