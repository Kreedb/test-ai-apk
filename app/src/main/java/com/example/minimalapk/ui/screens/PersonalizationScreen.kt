package com.example.minimalapk.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.minimalapk.examples.BOTTLENECK_SIZE
import com.example.minimalapk.examples.BottleneckExtractor
import com.example.minimalapk.examples.CLASS_COUNT
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.SoftmaxHead
import com.example.minimalapk.examples.TRAIN_STEPS
import com.example.minimalapk.examples.TrainingSample
import com.example.minimalapk.examples.decodeImage
import com.example.minimalapk.examples.personalizationCheckpoint
import com.example.minimalapk.examples.trainingBatches
import com.example.minimalapk.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val PERSONALIZATION_TAG = "Personalization"

@Composable
internal fun PersonalizationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var modelFile by remember { mutableStateOf<File?>(null) }
    val head = remember { SoftmaxHead(BOTTLENECK_SIZE) }
    val checkpoint = remember { personalizationCheckpoint(context) }
    var trained by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf(emptyList<TrainingSample>()) }
    var pendingClass by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("正在准备模型（首次需下载约 18 MB）…") }
    var error by remember { mutableStateOf<String?>(null) }
    var loss by remember { mutableStateOf<Float?>(null) }
    var prediction by remember { mutableStateOf<FloatArray?>(null) }

    LaunchedEffect(Unit) {
        runCatchingCancellable {
            withContext(Dispatchers.IO) {
                ExampleDownload.ensure(context, ExampleCatalog.personalization)
            }
        }.onSuccess {
            modelFile = it
            status = "模型就绪：为 4 个类别各添加至少一张图片后训练"
        }.onFailure {
            AppLog.e(PERSONALIZATION_TAG, "设备端训练模型加载失败", it)
            error = it.message ?: "模型加载失败"
            status = "模型不可用"
        }
        runCatchingCancellable {
            withContext(Dispatchers.IO) {
                if (checkpoint.isFile) { head.restore(checkpoint); true } else false
            }
        }.onSuccess {
            trained = it
            if (it) status = "已恢复训练结果，可直接识别图片；继续训练请重新添加样本"
        }.onFailure { error = "训练存档读取失败：${it.message}；可重置后重新训练" }
        busy = false
    }

    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val file = modelFile ?: return@rememberLauncherForActivityResult
        if (busy) return@rememberLauncherForActivityResult
        val label = pendingClass
        scope.launch {
            busy = true
            error = null
            runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    val bitmap = decodeImage(context, uri, 512) ?: error("无法读取所选图片")
                    BottleneckExtractor(file).use { it.extract(bitmap) }
                }
            }.onSuccess { bottleneck ->
                samples = samples + TrainingSample(bottleneck, label)
                status = "已添加类别 ${label + 1} 的样本（共 ${samples.size} 张）"
            }.onFailure {
                AppLog.w(PERSONALIZATION_TAG, "特征提取失败", it)
                error = it.message ?: "特征提取失败"
            }
            busy = false
        }
    }

    val inferPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val file = modelFile ?: return@rememberLauncherForActivityResult
        if (busy) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            prediction = null
            runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    val bitmap = decodeImage(context, uri, 512) ?: error("无法读取所选图片")
                    BottleneckExtractor(file).use { head.predict(it.extract(bitmap)) }
                }
            }.onSuccess {
                prediction = it
                status = "识别完成"
            }.onFailure {
                AppLog.w(PERSONALIZATION_TAG, "设备端识别失败", it)
                error = it.message ?: "识别失败"
            }
            busy = false
        }
    }

    val complete = (0 until CLASS_COUNT).all { label -> samples.any { it.label == label } }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = "设备端学习", onBack = onBack)
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
            Text("训练完成后自动保存分类头与 Adam 状态，重新进入页面可继续识别；样本图片不保存。", style = MaterialTheme.typography.bodySmall)
            if (modelFile == null && error == null) {
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
                        enabled = modelFile != null && !busy && samples.size < 200,
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
                    enabled = modelFile != null && !busy && complete,
                    onClick = {
                        if (modelFile == null) return@Button
                        busy = true
                        error = null
                        scope.launch {
                            runCatchingCancellable {
                                withContext(Dispatchers.IO) {
                                    val batches = trainingBatches(samples, samples.size)
                                    var lastLoss = 0f
                                    repeat(TRAIN_STEPS) { step ->
                                        currentCoroutineContext().ensureActive()
                                        lastLoss = head.train(batches[step % batches.size])
                                    }
                                    // Complete the atomic save even if navigation cancels result delivery.
                                    withContext(NonCancellable) { head.save(checkpoint) }
                                    lastLoss
                                }
                            }.onSuccess {
                                loss = it
                                trained = true
                                prediction = null
                                status = "训练完成并已保存（$TRAIN_STEPS 步）"
                            }.onFailure {
                                AppLog.w(PERSONALIZATION_TAG, "训练失败", it)
                                error = it.message ?: "训练失败"
                            }
                            busy = false
                        }
                    },
                ) {
                    Text(if (busy) "处理中…" else "训练")
                }
                Button(
                    enabled = modelFile != null && !busy && trained,
                    onClick = { inferPicker.launch("image/*") },
                ) {
                    Text("识别图片")
                }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatchingCancellable {
                                withContext(Dispatchers.IO) {
                                    check(!checkpoint.exists() || checkpoint.delete()) { "无法删除训练存档" }
                                    head.reset()
                                }
                            }.onSuccess {
                                samples = emptyList()
                                loss = null
                                prediction = null
                                trained = false
                                status = "已重置：请重新添加 4 类样本"
                            }.onFailure { error = "重置失败：${it.message}" }
                            busy = false
                        }
                    },
                ) {
                    Text("重置")
                }
            }
            if (!complete) {
                Text(
                    text = "训练需要 4 个类别都有样本（最多 200 张）；已保存的分类头可直接识别。",
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
