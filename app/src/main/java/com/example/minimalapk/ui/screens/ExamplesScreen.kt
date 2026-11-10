package com.example.minimalapk.ui.screens

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.minimalapk.examples.BOTTLENECK_SIZE
import com.example.minimalapk.examples.BottleneckExtractor
import com.example.minimalapk.examples.CLASS_COUNT
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.SoftmaxHead
import com.example.minimalapk.examples.SuperResolutionRunner
import com.example.minimalapk.examples.TRAIN_STEPS
import com.example.minimalapk.examples.TrainingSample
import com.example.minimalapk.examples.decodeImage
import com.example.minimalapk.examples.trainingBatches
import com.example.minimalapk.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val EXAMPLE_TAG = "Examples"

@Composable
fun ExamplesScreen() {
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page != 0) { page = 0 }
    when (page) {
        1 -> SuperResolutionScreen(onBack = { page = 0 })
        2 -> TtsScreen(onBack = { page = 0 })
        3 -> TrainingScreen(onBack = { page = 0 })
        else -> ExampleList(
            onOpenSuperResolution = { page = 1 },
            onOpenTts = { page = 2 },
            onOpenTraining = { page = 3 },
        )
    }
}

@Composable
private fun ExampleList(
    onOpenSuperResolution: () -> Unit,
    onOpenTts: () -> Unit,
    onOpenTraining: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(title = "示例", subtitle = "TensorFlow Lite 例程能力")
        Text(
            text = "示例模型首次使用时从远程下载，下载后离线推理；模型不参与 APK 打包。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "超分辨率",
                subtitle = "ESRGAN 4× 放大：50×50 → 200×200（约 5 MB）",
                onClick = onOpenSuperResolution,
            )
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "语音合成",
                subtitle = "调用系统 TTS 引擎；不依赖模型下载",
                onClick = onOpenTts,
            )
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "设备端训练",
                subtitle = "用相册图片训练 4 类分类头（约 18 MB）",
                onClick = onOpenTraining,
            )
        }
        Text(
            text = "当前只移植了超分辨率与设备端训练两个 TFLite 示例。官方示例仓库已没有 TTS 例程；" +
                "社区 FastSpeech2 模型依赖 Flex 自定义算子，因此语音合成使用系统引擎。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuperResolutionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var runner by remember { mutableStateOf<SuperResolutionRunner?>(null) }
    var status by remember { mutableStateOf("正在准备模型（首次需下载约 5 MB）…") }
    var error by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        var created: SuperResolutionRunner? = null
        runCatchingCancellable {
            val file = withContext(Dispatchers.IO) {
                ExampleDownload.ensure(context, ExampleCatalog.superResolution)
            }
            try {
                withContext(Dispatchers.IO) { created = SuperResolutionRunner(file) }
                currentCoroutineContext().ensureActive()
                checkNotNull(created)
            } catch (e: CancellationException) {
                created?.close()
                throw e
            }
        }.onSuccess {
            runner = it
            status = "模型就绪：50×50 → 200×200（4×）"
        }.onFailure {
            AppLog.e(EXAMPLE_TAG, "超分辨率模型加载失败", it)
            error = it.message ?: "模型加载失败"
            status = "模型不可用"
        }
    }
    DisposableEffect(runner) {
        val model = runner
        onDispose { model?.close() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val model = runner ?: return@rememberLauncherForActivityResult
        scope.launch {
            running = true
            error = null
            runCatchingCancellable {
                val bitmap = withContext(Dispatchers.IO) { decodeImage(context, uri) }
                    ?: error("无法读取所选图片")
                val upscaled = withContext(Dispatchers.IO) { model.run(bitmap) }
                bitmap to upscaled
            }.onSuccess { (bitmap, upscaled) ->
                source = bitmap
                result = upscaled
                status = "完成：${bitmap.width}×${bitmap.height} → ${upscaled.width}×${upscaled.height}"
            }.onFailure {
                AppLog.w(EXAMPLE_TAG, "超分辨率推理失败", it)
                error = it.message ?: "推理失败"
            }
            running = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = "超分辨率", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "ESRGAN 先取图片中心正方形缩到 50×50，再放大 4 倍到 200×200；" +
                    "原图越大，细节损失越明显。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (runner == null && error == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                enabled = runner != null && !running,
                onClick = { picker.launch("image/*") },
            ) {
                Text(if (running) "推理中…" else "选择图片")
            }
            source?.let { bitmap ->
                Text("原图", style = MaterialTheme.typography.titleSmall)
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "原图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
            result?.let { bitmap ->
                Text("超分辨率结果", style = MaterialTheme.typography.titleSmall)
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "超分辨率结果",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

@Composable
private fun TtsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<TextToSpeech>(1) }
    var ready by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在初始化系统语音引擎…") }
    var text by rememberSaveable { mutableStateOf("你好，这是设备端语音合成示例。") }

    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { code ->
            // The constructor can return before the engine binds; run the setup after assignment.
            Handler(Looper.getMainLooper()).post {
                val tts = holder[0]
                when {
                    code != TextToSpeech.SUCCESS -> status = "系统语音引擎初始化失败"
                    tts == null -> status = "系统语音引擎初始化失败，请重试"
                    else -> {
                        val result = tts.setLanguage(Locale.CHINA)
                        ready = result != TextToSpeech.LANG_MISSING_DATA &&
                            result != TextToSpeech.LANG_NOT_SUPPORTED
                        status = if (ready) {
                            "语音引擎就绪：${tts.defaultEngine ?: "系统默认"}"
                        } else {
                            "系统缺少中文语音包，请在系统设置中安装"
                        }
                    }
                }
            }
        }
        holder[0] = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            holder[0] = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = "语音合成", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "使用设备自带的 TTS 引擎；是否离线取决于系统引擎与已安装的语音包。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("要合成的文本") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = ready && text.isNotBlank(),
                    onClick = {
                        holder[0]?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "example")
                    },
                ) {
                    Text("朗读")
                }
                TextButton(
                    enabled = ready,
                    onClick = { holder[0]?.stop() },
                ) {
                    Text("停止")
                }
            }
            Text(
                text = "tensorflow/examples 当前没有 TTS 例程；社区 FastSpeech2 的 TFLite 导出依赖 " +
                    "Flex 自定义算子（如 FlexRandomUniform），LiteRT 无法直接加载，所以这里用系统引擎。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrainingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var extractor by remember { mutableStateOf<BottleneckExtractor?>(null) }
    val head = remember { SoftmaxHead(BOTTLENECK_SIZE) }
    var samples by remember { mutableStateOf(emptyList<TrainingSample>()) }
    var pendingClass by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在准备模型（首次需下载约 18 MB）…") }
    var error by remember { mutableStateOf<String?>(null) }
    var loss by remember { mutableStateOf<Float?>(null) }
    var prediction by remember { mutableStateOf<FloatArray?>(null) }

    LaunchedEffect(Unit) {
        extractor = null
        var created: BottleneckExtractor? = null
        runCatchingCancellable {
            val file = withContext(Dispatchers.IO) {
                ExampleDownload.ensure(context, ExampleCatalog.personalization)
            }
            try {
                withContext(Dispatchers.IO) { created = BottleneckExtractor(file) }
                currentCoroutineContext().ensureActive()
                checkNotNull(created)
            } catch (e: CancellationException) {
                created?.close()
                throw e
            }
        }.onSuccess {
            extractor = it
            status = "模型就绪：为 4 个类别各添加至少一张图片后训练"
        }.onFailure {
            AppLog.e(EXAMPLE_TAG, "设备端训练模型加载失败", it)
            error = it.message ?: "模型加载失败"
            status = "模型不可用"
        }
    }
    DisposableEffect(extractor) {
        val model = extractor
        onDispose { model?.close() }
    }

    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val model = extractor ?: return@rememberLauncherForActivityResult
        if (busy) return@rememberLauncherForActivityResult
        val label = pendingClass
        scope.launch {
            busy = true
            error = null
            runCatchingCancellable {
                val bitmap = withContext(Dispatchers.IO) { decodeImage(context, uri, 512) }
                    ?: error("无法读取所选图片")
                withContext(Dispatchers.IO) { model.extract(bitmap) }
            }.onSuccess { bottleneck ->
                samples = samples + TrainingSample(bottleneck, label)
                status = "已添加类别 ${label + 1} 的样本（共 ${samples.size} 张）"
            }.onFailure {
                AppLog.w(EXAMPLE_TAG, "特征提取失败", it)
                error = it.message ?: "特征提取失败"
            }
            busy = false
        }
    }

    val inferPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val model = extractor ?: return@rememberLauncherForActivityResult
        if (busy) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            runCatchingCancellable {
                val bitmap = withContext(Dispatchers.IO) { decodeImage(context, uri, 512) }
                    ?: error("无法读取所选图片")
                withContext(Dispatchers.IO) {
                    head.predict(model.extract(bitmap))
                }
            }.onSuccess {
                prediction = it
                status = "识别完成"
            }.onFailure {
                AppLog.w(EXAMPLE_TAG, "设备端识别失败", it)
                error = it.message ?: "识别失败"
            }
            busy = false
        }
    }

    val complete = (0 until CLASS_COUNT).all { label -> samples.any { it.label == label } }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = "设备端训练", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "冻结 MobileNetV2 特征，只训练最后的 4 类分类头（纯 Kotlin Adam）。" +
                    "图片先中心裁切到 224×224；每个类别至少一张，训练在设备上完成，图片不会上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "官方模型的 train 签名依赖 Flex 自定义算子，LiteRT 无法直接运行；" +
                    "这里只复用它的 load 特征提取器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (extractor == null && error == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            for (label in 0 until CLASS_COUNT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "类别 ${label + 1} · ${samples.count { it.label == label }} 张",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(
                        enabled = extractor != null && !busy,
                        onClick = {
                            pendingClass = label
                            addPicker.launch("image/*")
                        },
                    ) {
                        Text("添加图片")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = extractor != null && !busy && complete,
                    onClick = {
                        if (extractor == null) return@Button
                        busy = true
                        error = null
                        scope.launch {
                            runCatchingCancellable {
                                withContext(Dispatchers.IO) {
                                    val batches = trainingBatches(samples, samples.size)
                                    var lastLoss = 0f
                                    repeat(TRAIN_STEPS) { step ->
                                        lastLoss = head.train(batches[step % batches.size])
                                    }
                                    lastLoss
                                }
                            }.onSuccess {
                                loss = it
                                status = "训练完成（$TRAIN_STEPS 步）"
                            }.onFailure {
                                AppLog.w(EXAMPLE_TAG, "训练失败", it)
                                error = it.message ?: "训练失败"
                            }
                            busy = false
                        }
                    },
                ) {
                    Text(if (busy) "处理中…" else "训练")
                }
                Button(
                    enabled = extractor != null && !busy && complete && loss != null,
                    onClick = { inferPicker.launch("image/*") },
                ) {
                    Text("识别图片")
                }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        samples = emptyList()
                        loss = null
                        prediction = null
                        head.reset()
                        status = "已重置：请重新添加 4 类样本"
                    },
                ) {
                    Text("重置")
                }
            }
            if (!complete) {
                Text(
                    text = "训练与识别需要 4 个类别都有样本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            loss?.let {
                Text(
                    text = "最近一次 loss：${String.format(Locale.US, "%.4f", it)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            prediction?.let { scores ->
                val best = scores.indices.maxByOrNull { scores[it] } ?: 0
                Text(
                    text = "识别结果：类别 ${best + 1}（${formatConfidence(scores[best])}）",
                    style = MaterialTheme.typography.titleMedium,
                )
                scores.forEachIndexed { index, score ->
                    Text(
                        text = "类别 ${index + 1}：${formatConfidence(score)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (index == best) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private fun formatConfidence(value: Float): String =
    String.format(Locale.US, "%.1f%%", value * 100f)

/** runCatching swallows coroutine cancellation; a tab switch must not surface as an error. */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
