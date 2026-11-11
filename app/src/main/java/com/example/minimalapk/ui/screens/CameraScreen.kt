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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.plugins.CameraModelSettingsStore
import com.example.minimalapk.plugins.CameraSession
import com.example.minimalapk.plugins.ConfiguredModelPlugin
import com.example.minimalapk.plugins.MessageKind
import com.example.minimalapk.plugins.PluginHost
import com.example.minimalapk.plugins.PluginIds
import com.example.minimalapk.plugins.PluginSettingsStore
import com.example.minimalapk.plugins.PoseEnhancementPlugin
import com.example.minimalapk.plugins.PoseModelsPlugin
import com.example.minimalapk.plugins.cameraPluginSettings
import com.example.minimalapk.pose.DeviceUsage
import com.example.minimalapk.pose.DeviceUsageSampler
import com.example.minimalapk.pose.PoseTimings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun CameraScreen(store: PluginSettingsStore) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    var exampleModels by remember { mutableStateOf(emptyList<ConfiguredModel>()) }
    var showExamplePicker by rememberSaveable { mutableStateOf(false) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val configured = settings.getValue(PluginIds.CONFIGURED_MODEL)
    val selected = exampleModels.firstOrNull { it.id == configured.config["model_id"] }
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
    LaunchedEffect(Unit) {
        ConfiguredModelStore.revision.collect {
            try {
                exampleModels = withContext(Dispatchers.IO) { ConfiguredModelStore.list(ConfiguredModelStore.directory(context)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.w("Camera", "示例模型列表读取失败", error)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("相机", "离线姿态与插件预览")
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "示例模型叠加：${if (configured.enabled) selected?.name ?: "待选择" else "关闭"}",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showExamplePicker = true }) { Text("选择") }
            }
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
    if (showExamplePicker) {
        OptionDialog(
            title = "示例模型叠加", options = listOf<ConfiguredModel?>(null) + exampleModels,
            selected = selected.takeIf { configured.enabled }, label = { it?.name ?: "关闭（保留基础姿态）" },
            onSelect = { model ->
                store.update(PluginIds.CONFIGURED_MODEL) {
                    it.copy(enabled = model != null, config = it.config + ("model_id" to (model?.id ?: "")))
                }
                showExamplePicker = false
            }, onDismiss = { showExamplePicker = false },
        )
    }
}

@Composable
private fun PoseCamera(store: PluginSettingsStore, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val models = remember(context, lifecycleOwner) { CameraModelSettingsStore(context) }
    val poses = remember(context, lifecycleOwner) { PoseModelsPlugin(context.applicationContext) }
    val host = remember(context, lifecycleOwner) {
        PluginHost(listOf(poses, PoseEnhancementPlugin(), ConfiguredModelPlugin(context.applicationContext)))
    }
    val camera = remember(host, lifecycleOwner) { CameraSession(context, lifecycleOwner, host, poses) }
    val snapshot by host.snapshot.collectAsStateWithLifecycle()
    val error by camera.error.collectAsStateWithLifecycle()
    var fps by remember { mutableFloatStateOf(0f) }
    var usage by remember { mutableStateOf(DeviceUsage()) }
    val frameTimes = remember { ArrayDeque<Long>() }
    val usageSampler = remember { DeviceUsageSampler() }

    DisposableEffect(camera) {
        camera.start()
        onDispose { camera.close(); models.close() }
    }
    LaunchedEffect(host, store, models) {
        combine(store.settings, models.settings, ConfiguredModelStore.revision) { plugins, base, revision ->
            cameraPluginSettings(plugins, base, revision)
        }.collect { host.configure(it) }
    }
    LaunchedEffect(host) {
        var lastFrame = -1L
        host.snapshot.collect { state ->
            val frame = state.frame
            if (frame == null) {
                frameTimes.clear()
                fps = 0f
                lastFrame = -1
            } else if (frame.source.frameNumber != lastFrame) {
                lastFrame = frame.source.frameNumber
                frameTimes.addLast(frame.timestampNanos)
                fps = frameRate(frameTimes, System.nanoTime())
            }
        }
    }
    LaunchedEffect(lifecycleOwner) {
        while (isActive) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                usage = withContext(Dispatchers.IO) { usageSampler.sample() }
                fps = frameRate(frameTimes, System.nanoTime())
            } else {
                usageSampler.reset()
                frameTimes.clear()
                fps = 0f
            }
            delay(1_000)
        }
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
            MetricsPanel(
                fps, frame.poses.firstOrNull()?.timings, usage, frame.executionProvider,
                Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        } else if (error == null) {
            Text("正在启动相机…", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)
        }
        error?.let {
            Text(it, Modifier.align(Alignment.Center).padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        if (snapshot.reports.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().heightIn(max = 200.dp)
                    .background(Color.Black.copy(alpha = 0.55f)).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                snapshot.reports.forEach { report ->
                    report.output.messages.forEach { message ->
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

@Composable
private fun MetricsPanel(fps: Float, timings: PoseTimings?, usage: DeviceUsage, executionProvider: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MetricLine("帧率", if (fps > 0f) "${format(fps)} FPS" else "—")
        MetricLine("推理", timings?.let { "${format(it.inferenceMs)} ms" } ?: "—")
        MetricLine("预处理", timings?.let { "${format(it.preprocessMs)} ms" } ?: "—")
        MetricLine("后处理", timings?.let { "${format(it.postprocessMs)} ms" } ?: "—")
        MetricLine("分析耗时", timings?.let { "${format(it.totalMs)} ms" } ?: "—")
        MetricLine("加速器", executionProvider ?: "—")
        MetricLine("CPU(应用)", formatPercent(usage.cpuPercent))
        MetricLine("GPU", formatPercent(usage.gpuPercent))
        MetricLine("NPU", formatPercent(usage.npuPercent))
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Text("$label $value", style = MaterialTheme.typography.labelSmall, color = Color.White)
}

private fun format(value: Float): String = String.format(Locale.US, "%.1f", value)

internal fun frameRate(timestamps: ArrayDeque<Long>, nowNanos: Long): Float {
    while (timestamps.size > 2 && nowNanos - timestamps.first() > FPS_WINDOW_NANOS) timestamps.removeFirst()
    if (timestamps.size < 2) return 0f
    val spanNanos = (nowNanos - timestamps.first()).toFloat()
    if (spanNanos <= 0f) return 0f
    return (timestamps.size - 1) * 1_000_000_000f / spanNanos
}

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
