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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.minimalapk.models.AssetResourceStore
import com.example.minimalapk.plugins.AudioCaptureHost
import com.example.minimalapk.plugins.AudioClassificationPlugin
import com.example.minimalapk.plugins.AudioPermission
import com.example.minimalapk.plugins.CameraModelSettingsStore
import com.example.minimalapk.plugins.CameraSession
import com.example.minimalapk.plugins.ConfiguredModelPlugin
import com.example.minimalapk.plugins.ExampleScenePlugin
import com.example.minimalapk.plugins.ExampleScenes
import com.example.minimalapk.plugins.MessageKind
import com.example.minimalapk.plugins.PluginContribution
import com.example.minimalapk.plugins.PluginHost
import com.example.minimalapk.plugins.PluginSettingsStore
import com.example.minimalapk.plugins.PersonalizationPlugin
import com.example.minimalapk.plugins.PoseEnhancementPlugin
import com.example.minimalapk.plugins.PoseModelsPlugin
import com.example.minimalapk.plugins.SuperResolutionPlugin
import com.example.minimalapk.plugins.SystemMonitorPlugin
import com.example.minimalapk.plugins.TtsPlugin
import com.example.minimalapk.plugins.LiteRtScenes
import com.example.minimalapk.plugins.LiteRtImagePlugin
import com.example.minimalapk.plugins.LiteRtTextPlugin
import com.example.minimalapk.plugins.LiteRtAudioPlugin
import com.example.minimalapk.plugins.LiteRtCaptionsPlugin
import com.example.minimalapk.plugins.LiteRtSpeechPlugin
import com.example.minimalapk.plugins.PluginAudioFocus
import com.example.minimalapk.plugins.LocalTextPlugin
import com.example.minimalapk.plugins.audioPermissionAfterCheck
import com.example.minimalapk.plugins.cameraFrameRate
import com.example.minimalapk.plugins.cameraPluginSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

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
        ScreenHeader("相机", "实时预览 · 开启插件显示对应结果")
        if (granted) {
            PluginCamera(store, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)))
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
private fun PluginCamera(store: PluginSettingsStore, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val models = remember(context, lifecycleOwner) { CameraModelSettingsStore(context) }
    val resources = remember(context) { AssetResourceStore.get(context) }
    val poses = remember(context, lifecycleOwner) { PoseModelsPlugin(context.applicationContext) }
    val host = remember(context, lifecycleOwner) {
        PluginHost(listOf(
            poses, PoseEnhancementPlugin(), ConfiguredModelPlugin(context.applicationContext),
            SystemMonitorPlugin(context.applicationContext), SuperResolutionPlugin(context.applicationContext),
            *ExampleScenes.all.map { ExampleScenePlugin(context.applicationContext, it) }.toTypedArray(),
            AudioClassificationPlugin(context.applicationContext), TtsPlugin(context.applicationContext),
            PersonalizationPlugin(context.applicationContext),
            LiteRtImagePlugin(context.applicationContext, LiteRtScenes.vision),
            LiteRtImagePlugin(context.applicationContext, LiteRtScenes.image),
            LiteRtTextPlugin(context.applicationContext), LiteRtAudioPlugin(context.applicationContext),
            LiteRtCaptionsPlugin(context.applicationContext), LiteRtSpeechPlugin(context.applicationContext),
            LocalTextPlugin(context.applicationContext),
        ))
    }
    val audio = remember(context, host, lifecycleOwner) {
        AudioCaptureHost(context.applicationContext, host)
    }
    val camera = remember(host, lifecycleOwner, audio) {
        CameraSession(context, lifecycleOwner, host, poses, audio)
    }
    val snapshot by host.snapshot.collectAsStateWithLifecycle()
    val error by camera.error.collectAsStateWithLifecycle()
    var audioPermission by remember { mutableStateOf(AudioPermission.NOT_REQUESTED) }
    var audioRequested by remember { mutableStateOf(false) }
    var audioPermanentlyDenied by remember { mutableStateOf(false) }
    val audioPermissionNeeded = snapshot.session != null && snapshot.audioPermissionRequested
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioRequested = true
        val next = audioPermissionAfterCheck(granted, requested = true, previous = audioPermission)
        audioPermission = next
        audioPermanentlyDenied = context.audioPermanentlyDenied(granted, requested = true)
        audio.permissionChanged(next)
    }
    LifecycleResumeEffect(Unit) {
        val granted = context.hasAudioPermission()
        val next = audioPermissionAfterCheck(granted, audioRequested, audioPermission)
        if (next != audioPermission) {
            audioPermission = next
            audio.permissionChanged(next)
        }
        audioPermanentlyDenied = context.audioPermanentlyDenied(granted, audioRequested)
        onPauseOrDispose { }
    }
    LaunchedEffect(audioPermissionNeeded) {
        if (!audioPermissionNeeded) return@LaunchedEffect
        if (context.hasAudioPermission()) {
            audioPermanentlyDenied = false
            if (audioPermission != AudioPermission.GRANTED) {
                audioPermission = AudioPermission.GRANTED
                audio.permissionChanged(AudioPermission.GRANTED)
            }
        } else if (!audioRequested) {
            audioRequested = true
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            val next = audioPermissionAfterCheck(false, requested = true, previous = audioPermission)
            if (next != audioPermission) {
                audioPermission = next
                audio.permissionChanged(next)
            }
        }
    }

    DisposableEffect(camera) {
        camera.start()
        onDispose { camera.close(); audio.close(); models.close() }
    }
    LaunchedEffect(host, store, models, audio) {
        combine(store.settings, models.settings, ConfiguredModelStore.revision, resources.revision) { plugins, base, revision, assetsRevision ->
            cameraPluginSettings(plugins, base, revision) to assetsRevision
        }.collect { (settings, assetsRevision) ->
            host.configure(settings, assetsRevision)
            audio.refresh()
        }
    }
    LaunchedEffect(audio) {
        PluginAudioFocus.playing.collect { audio.playbackChanged(it) }
    }
    LaunchedEffect(host, audio) {
        host.snapshot.map { it.audioCaptureRequested }.distinctUntilChanged().collect { audio.refresh() }
    }
    LaunchedEffect(host, store) {
        combine(store.requests, host.snapshot) { requests, state -> requests to state.session }.collect { (requests, session) ->
            if (session != null) requests.forEach { (id, request) ->
                if (store.requests.value[id] == request && host.action(id, request.action, request.revision)) store.acknowledgeAction(id, request)
            }
        }
    }

    Box(modifier.onSizeChanged { camera.viewport(it.width, it.height) }) {
        Box(Modifier.fillMaxSize().pluginPressInput(host)) {
            val frame = snapshot.frame
            val image = snapshot.displayedImage
            if (image != null && frame != null) {
                CameraFramePreview(snapshot, Modifier.fillMaxSize())
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
                snapshot.guides.forEach { contribution ->
                    contribution.output.guide?.let { PluginPhotoGuide(it, frame.source, Modifier.fillMaxSize()) }
                }
                MonitorPanel(snapshot.monitors, Modifier.align(Alignment.TopEnd).padding(8.dp))
            } else if (error == null) {
                Text("正在启动相机…", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)
            }
            error?.let {
                Text(it, Modifier.align(Alignment.Center).padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
        val displayedAudioPermission = if (snapshot.session != null) snapshot.audioPermission else audioPermission
        if (audioPermissionNeeded && displayedAudioPermission != AudioPermission.GRANTED) {
            // A denial only disables audio plugins; the camera and every other plugin keep running.
            Column(
                Modifier.align(Alignment.TopCenter).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    when (displayedAudioPermission) {
                        AudioPermission.DENIED, AudioPermission.REVOKED -> "需要麦克风权限才能运行音频插件"
                        AudioPermission.MICROPHONE_BUSY -> "麦克风不可用或被系统静音，音频插件已暂停"
                        else -> "正在请求麦克风权限…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                when {
                    audioPermanentlyDenied -> Button(onClick = { context.openAppSettings() }) { Text("打开设置") }
                    displayedAudioPermission == AudioPermission.DENIED ||
                        displayedAudioPermission == AudioPermission.REVOKED -> Button(onClick = {
                        audioRequested = true
                        audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("授予权限") }
                }
            }
        }
        val reports = snapshot.reports
        if (reports.isNotEmpty()) {
            SelectionContainer(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp)
                        .background(Color.Black.copy(alpha = 0.55f)).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    reports.forEach { report ->
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
                        report.output.imageReport?.let { image ->
                            val label = if (image.source == null) "文件 / 生成结果 · ${image.label}" else image.label
                            Text("${report.name} · $label", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Image(image.input.bitmap.asImageBitmap(), "${report.name} $label",
                                    Modifier.weight(1f).heightIn(min = 100.dp, max = 180.dp), contentScale = ContentScale.Fit)
                                image.result?.let { result ->
                                    Image(result.bitmap.asImageBitmap(), "${report.name} 分析结果",
                                        Modifier.weight(1f).heightIn(min = 100.dp, max = 180.dp), contentScale = ContentScale.Fit)
                                }
                            }
                        }
                        if (report.output.actions.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                report.output.actions.forEach { action ->
                                    TextButton(enabled = action.enabled,
                                        onClick = { host.action(report.pluginId, action.id) }) {
                                        Text(action.label, color = if (action.enabled) Color(0xFF80DEEA) else Color.Gray)
                                    }
                                }
                            }
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

private fun Context.hasAudioPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun Context.audioPermanentlyDenied(granted: Boolean, requested: Boolean): Boolean = isAudioPermanentlyDenied(
    granted, requested, findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
)

internal fun isAudioPermanentlyDenied(granted: Boolean, requested: Boolean, shouldShowRationale: Boolean?): Boolean =
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
