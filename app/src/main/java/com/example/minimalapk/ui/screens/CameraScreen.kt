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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.minimalapk.pose.DeviceUsage
import com.example.minimalapk.pose.DeviceUsageSampler
import com.example.minimalapk.pose.PoseAnalyzer
import com.example.minimalapk.pose.PoseDetection
import com.example.minimalapk.pose.PoseFrame
import com.example.minimalapk.pose.PoseMeasurement
import com.example.minimalapk.pose.PoseNet
import com.example.minimalapk.pose.PoseTimings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    var heightText by rememberSaveable { mutableStateOf("170") }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { result ->
            requested = true
            granted = result
            permanentlyDenied = context.cameraPermanentlyDenied(result, requested = true)
        },
    )

    LifecycleResumeEffect(Unit) {
        granted = context.hasCameraPermission()
        permanentlyDenied = context.cameraPermanentlyDenied(granted, requested)
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        if (!granted) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = "相机",
            subtitle = "YOLO26-pose 离线姿态测量",
        )

        if (granted) {
            OutlinedTextField(
                value = heightText,
                onValueChange = { input -> heightText = input.filter(Char::isDigit).take(3) },
                label = { Text("身高 (cm)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            PoseCamera(
                heightCm = heightText.toFloatOrNull(),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp)),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (permanentlyDenied) {
                        "相机权限已被拒绝，请在系统设置中开启"
                    } else {
                        "需要相机权限才能预览"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        if (permanentlyDenied) {
                            context.openAppSettings()
                        } else {
                            launcher.launch(Manifest.permission.CAMERA)
                        }
                    },
                ) {
                    Text(if (permanentlyDenied) "打开设置" else "授予权限")
                }
            }
        }
    }
}

@Composable
private fun PoseCamera(heightCm: Float?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var frame by remember { mutableStateOf<PoseFrame?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fps by remember { mutableFloatStateOf(0f) }
    var usage by remember { mutableStateOf(DeviceUsage()) }
    var executionProvider by remember { mutableStateOf<String?>(null) }
    val frameTimes = remember { ArrayDeque<Long>() }
    val usageSampler = remember { DeviceUsageSampler() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
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
            delay(1_000L)
        }
    }

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = try {
            context.awaitCameraProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "相机初始化失败"
            return@LaunchedEffect
        }
        val analyzer = try {
            withContext(Dispatchers.IO) { PoseAnalyzer(context) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "模型加载失败"
            return@LaunchedEffect
        }
        executionProvider = analyzer.executionProvider
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var analysis: ImageAnalysis? = null
        try {
            analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val rotationDegrees = proxy.imageInfo.rotationDegrees
                try {
                    val bitmap = proxy.toBitmap()
                    val result = try {
                        analyzer.analyze(bitmap, rotationDegrees)
                    } finally {
                        bitmap.recycle()
                    }
                    val completedNanos = System.nanoTime()
                    mainExecutor.execute {
                        errorMessage = null
                        frame = result
                        frameTimes.addLast(completedNanos)
                        fps = frameRate(frameTimes, completedNanos)
                    }
                } catch (e: Exception) {
                    mainExecutor.execute { errorMessage = e.message ?: "姿态推理失败" }
                } finally {
                    proxy.close()
                }
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis,
            )
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "相机初始化失败"
        } finally {
            analysis?.clearAnalyzer()
            analysisExecutor.shutdownNow()
            cameraProvider.unbindAll()
            withContext(NonCancellable + Dispatchers.IO) { analyzer.close() }
        }
    }

    val detection = frame?.detections?.firstOrNull()
    val measurement = remember(detection, heightCm) {
        if (detection != null && heightCm != null) {
            PoseMeasurement.measure(detection, heightCm)
        } else {
            null
        }
    }

    Box(modifier = modifier) {
        val current = frame
        if (current != null) {
            Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            PoseOverlay(
                detection = detection,
                modifier = Modifier.fillMaxSize(),
            )
            MetricsPanel(
                fps = fps,
                timings = current.timings,
                usage = usage,
                executionProvider = executionProvider,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        } else if (errorMessage == null) {
            Text(
                text = "正在加载模型…",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (heightCm == null || heightCm <= 0f) {
                MeasurementLine("请输入身高以换算尺寸")
            } else if (detection == null) {
                MeasurementLine("未检测到人体")
            } else if (measurement != null) {
                measurement.armSpanCm?.let { MeasurementLine("腕距约 ${format(it)} cm（双臂平举，近似臂展）") }
                measurement.eyeDistanceCm?.let { MeasurementLine("双眼间距约 ${format(it)} cm") }
                measurement.shoulderTiltDeg?.let { MeasurementLine("肩线倾斜 ${format(it)}°") }
                measurement.hipTiltDeg?.let { MeasurementLine("髋线倾斜 ${format(it)}°") }
                measurement.torsoOffsetCm?.let { MeasurementLine("肩髋横向偏移约 ${format(it)} cm") }
            } else {
                MeasurementLine("请输入有效身高（50–250 cm）")
            }
            MeasurementLine(
                text = "结果仅供筛查参考，不能用于医学诊断",
                color = Color(0xFFBDBDBD),
            )
        }
    }
}

@Composable
private fun MeasurementLine(text: String, color: Color = Color.White) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun MetricsPanel(
    fps: Float,
    timings: PoseTimings,
    usage: DeviceUsage,
    executionProvider: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MetricLine("帧率", if (fps > 0f) "${format(fps)} FPS" else "—")
        MetricLine("推理", "${format(timings.inferenceMs)} ms")
        MetricLine("预处理", "${format(timings.preprocessMs)} ms")
        MetricLine("总耗时", "${format(timings.totalMs)} ms")
        MetricLine("加速器", executionProvider ?: "—")
        MetricLine("CPU(应用)", formatPercent(usage.cpuPercent))
        MetricLine("GPU", formatPercent(usage.gpuPercent))
        MetricLine("NPU", formatPercent(usage.npuPercent))
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Text(
        text = "$label $value",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
    )
}

@Composable
private fun PoseOverlay(detection: PoseDetection?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (detection == null) return@Canvas
        val scale = min(size.width, size.height) / PoseNet.INPUT_SIZE
        val left = (size.width - PoseNet.INPUT_SIZE * scale) / 2f
        val top = (size.height - PoseNet.INPUT_SIZE * scale) / 2f
        fun point(index: Int): Offset? {
            val keypoint = detection.keypoints.getOrNull(index) ?: return null
            if (keypoint.confidence < PoseNet.KEYPOINT_THRESHOLD) return null
            return Offset(left + keypoint.x * scale, top + keypoint.y * scale)
        }

        val skeletonColor = Color(0xFF00E5FF)
        PoseNet.skeletonEdges.forEach { (first, second) ->
            val start = point(first) ?: return@forEach
            val end = point(second) ?: return@forEach
            drawLine(skeletonColor, start, end, strokeWidth = 3f)
        }
        detection.keypoints.indices.forEach { index ->
            val keypoint = point(index) ?: return@forEach
            drawCircle(Color(0xFFFFC107), radius = 5f, center = keypoint)
        }
        drawRect(
            color = Color(0x9900E5FF),
            topLeft = Offset(left + detection.left * scale, top + detection.top * scale),
            size = Size(detection.width * scale, detection.height * scale),
            style = Stroke(width = 3f),
        )
    }
}

private fun format(value: Float): String = String.format(Locale.US, "%.1f", value)

private fun formatPercent(value: Float?): String =
    if (value != null) "${format(value)}%" else "N/A"

private fun frameRate(timestamps: ArrayDeque<Long>, nowNanos: Long): Float {
    while (timestamps.size > 1 && nowNanos - timestamps.first() > FPS_WINDOW_NANOS) {
        timestamps.removeFirst()
    }
    if (timestamps.size < 2) return 0f
    val spanNanos = (nowNanos - timestamps.first()).toFloat()
    if (spanNanos <= 0f) return 0f
    return (timestamps.size - 1) * 1_000_000_000f / spanNanos
}

private const val FPS_WINDOW_NANOS = 2_000_000_000L

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.cameraPermanentlyDenied(granted: Boolean, requested: Boolean): Boolean =
    isCameraPermanentlyDenied(
        granted = granted,
        requested = requested,
        shouldShowRationale = findActivity()
            ?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
    )

internal fun isCameraPermanentlyDenied(
    granted: Boolean,
    requested: Boolean,
    shouldShowRationale: Boolean?,
): Boolean = !granted && requested && shouldShowRationale == false

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
