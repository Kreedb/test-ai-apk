package com.example.minimalapk.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.minimalapk.pose.BodyMeasurements
import com.example.minimalapk.pose.Yolo26PoseDetector
import com.example.minimalapk.pose.measureBody
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PoseScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
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

    var heightText by rememberSaveable { mutableStateOf("170") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var measurements by remember { mutableStateOf<BodyMeasurements?>(null) }
    val scope = rememberCoroutineScope()
    val detector = remember { Yolo26PoseDetector(context) }

    DisposableEffect(detector) {
        onDispose { detector.close() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "体测",
            subtitle = "输入身高后拍摄，估算肩宽、臂长与腿长",
        )

        OutlinedTextField(
            value = heightText,
            onValueChange = { heightText = it.filter(Char::isDigit) },
            label = { Text("身高 (cm)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        if (granted) {
            PoseCamera(
                onImageCaptureReady = { imageCapture = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    val height = heightText.toFloatOrNull()
                    if (height == null || height <= 0f) {
                        message = "请输入有效身高"
                        return@Button
                    }
                    busy = true
                    message = null
                    measurements = null
                    capture.takePicture(
                        Dispatchers.Default.asExecutor(),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val bitmap = runCatching {
                                    image.toBitmap().upright(image.imageInfo.rotationDegrees)
                                }
                                image.close()
                                val source = bitmap.getOrElse {
                                    scope.launch {
                                        busy = false
                                        message = it.message ?: "读取画面失败"
                                    }
                                    return
                                }
                                scope.launch {
                                    try {
                                        val person = withContext(Dispatchers.Default) {
                                            detector.detect(source) ?: error("画面中未检测到人物")
                                        }
                                        val measured = measureBody(height, person)
                                            ?: error("无法根据该姿态计算尺寸")
                                        if (measured.shoulderWidthCm == null &&
                                            measured.leftArmCm == null &&
                                            measured.rightArmCm == null &&
                                            measured.hipWidthCm == null &&
                                            measured.leftLegCm == null &&
                                            measured.rightLegCm == null
                                        ) {
                                            error("关键点不可见，请确保全身入镜")
                                        }
                                        measurements = measured
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        message = e.message ?: "测量失败"
                                    } finally {
                                        source.recycle()
                                        busy = false
                                    }
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                scope.launch {
                                    busy = false
                                    message = "拍照失败：${exception.message}"
                                }
                            }
                        },
                    )
                },
                enabled = imageCapture != null && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "测量中…" else "拍摄并测量")
            }
            measurements?.let { Measurements(it) }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
                        "需要相机权限才能体测"
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
private fun PoseCamera(
    onImageCaptureReady: (ImageCapture) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(previewView, lifecycleOwner, imageCapture) {
        try {
            val cameraProvider = context.awaitCameraProvider()
            coroutineContext.ensureActive()
            provider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
            onImageCaptureReady(imageCapture)
        } catch (e: Exception) {
            errorMessage = e.message ?: "相机初始化失败"
        }
    }

    DisposableEffect(Unit) {
        onDispose { provider?.unbindAll() }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
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
    }
}

@Composable
private fun Measurements(measurements: BodyMeasurements) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "参考身高 ${oneDecimal(measurements.pixelHeight)} px，" +
                "1 px ≈ ${"%.2f".format(Locale.CHINA, measurements.cmPerPixel)} cm",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        measurements.shoulderWidthCm?.let { MeasurementLine("肩宽", it) }
        measurements.leftArmCm?.let { MeasurementLine("左臂长", it) }
        measurements.rightArmCm?.let { MeasurementLine("右臂长", it) }
        measurements.hipWidthCm?.let { MeasurementLine("髋宽", it) }
        measurements.leftLegCm?.let { MeasurementLine("左腿长", it) }
        measurements.rightLegCm?.let { MeasurementLine("右腿长", it) }
    }
}

@Composable
private fun MeasurementLine(label: String, valueCm: Float) {
    Text(
        text = "$label：${oneDecimal(valueCm)} cm",
        style = MaterialTheme.typography.bodyLarge,
    )
}

private fun oneDecimal(value: Float): String = "%.1f".format(Locale.CHINA, value)

private fun Bitmap.upright(rotationDegrees: Int): Bitmap {
    if (rotationDegrees == 0) return this
    return Bitmap.createBitmap(
        this,
        0,
        0,
        width,
        height,
        Matrix().apply { postRotate(rotationDegrees.toFloat()) },
        true,
    ).also { if (it !== this) recycle() }
}
