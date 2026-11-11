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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.minimalapk.examples.DEFAULT_PERSONALIZATION_CONFIDENCE
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.MAX_CATEGORY_NAME_LENGTH
import com.example.minimalapk.examples.MAX_PERSONALIZATION_CONFIDENCE
import com.example.minimalapk.examples.MAX_SAMPLES_PER_CATEGORY
import com.example.minimalapk.examples.MAX_TRAIN_BATCH
import com.example.minimalapk.examples.MIN_PERSONALIZATION_CONFIDENCE
import com.example.minimalapk.examples.MIN_SAMPLES_PER_CATEGORY
import com.example.minimalapk.examples.PersonalizationProfile
import com.example.minimalapk.examples.SoftmaxHead
import com.example.minimalapk.examples.TRAIN_STEPS
import com.example.minimalapk.examples.TrainingSample
import com.example.minimalapk.examples.decodeImage
import com.example.minimalapk.examples.personalizationCategoryName
import com.example.minimalapk.examples.personalizationCategoryProblem
import com.example.minimalapk.examples.personalizationCheckpoint
import com.example.minimalapk.examples.personalizationPercent
import com.example.minimalapk.examples.personalizationResultText
import com.example.minimalapk.examples.trainingBatches
import com.example.minimalapk.examples.validatePersonalizationSamples
import com.example.minimalapk.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val PERSONALIZATION_TAG = "Personalization"

private data class CollectedPhotos(val samples: List<TrainingSample>, val duplicates: Int, val unreadable: Int)

@Composable
internal fun PersonalizationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checkpoint = remember { personalizationCheckpoint(context) }
    var modelFile by remember { mutableStateOf<File?>(null) }
    var head by remember { mutableStateOf(SoftmaxHead(BOTTLENECK_SIZE)) }
    var savedProfile by remember { mutableStateOf<PersonalizationProfile?>(null) }
    var trained by remember { mutableStateOf(false) }
    var names by remember { mutableStateOf(List(CLASS_COUNT) { "" }) }
    var threshold by remember { mutableFloatStateOf(DEFAULT_PERSONALIZATION_CONFIDENCE) }
    var samples by remember { mutableStateOf(emptyList<TrainingSample>()) }
    var pendingClass by rememberSaveable { mutableIntStateOf(0) }
    var prepareAttempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var trainingStep by remember { mutableIntStateOf(0) }
    var training by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在读取本机分类器…") }
    var checkpointError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var prediction by remember { mutableStateOf<FloatArray?>(null) }
    var confirmProfile by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(prepareAttempt) {
        busy = true
        error = null
        try {
            if (prepareAttempt == 0) {
                runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        if (checkpoint.isFile) SoftmaxHead(BOTTLENECK_SIZE).apply { restore(checkpoint) } else null
                    }
                }.onSuccess { restored ->
                    if (restored != null) {
                        head = restored
                        trained = true
                        savedProfile = restored.profile
                        names = restored.profile?.categoryNames ?: List(CLASS_COUNT) { "" }
                        threshold = restored.profile?.confidenceThreshold ?: DEFAULT_PERSONALIZATION_CONFIDENCE
                    }
                }.onFailure {
                    AppLog.w(PERSONALIZATION_TAG, "训练存档读取失败，原文件已保留", it)
                    checkpointError = "训练存档读取失败：${it.message}。原文件已保留；新建训练任务会删除它。"
                }
            }
            status = "正在准备识别模型（首次需下载约 ${ExampleCatalog.personalization.sizeBytes / 1024 / 1024} MB）…"
            runCatchingCancellable {
                withContext(Dispatchers.IO) { ExampleDownload.ensure(context, ExampleCatalog.personalization) }
            }.onSuccess {
                modelFile = it
                status = if (trained) "已恢复本机分类器，可直接测试照片" else "准备完成，请从第 1 步开始"
            }.onFailure {
                AppLog.e(PERSONALIZATION_TAG, "设备端学习模型加载失败", it)
                error = it.message ?: "模型加载失败"
                status = "识别模型尚未准备好，请重试下载"
            }
        } finally {
            busy = false
        }
    }

    val trimmedNames = names.map { it.trim() }
    val namesProblem = personalizationCategoryProblem(names)
    val counts = List(CLASS_COUNT) { label -> samples.count { it.label == label } }
    val enoughSamples = counts.all { it >= MIN_SAMPLES_PER_CATEGORY }
    val profileChanged = trained && (savedProfile?.categoryNames != trimmedNames || savedProfile?.confidenceThreshold != threshold)
    val canCollect = modelFile != null && !busy && checkpointError == null && namesProblem == null && !profileChanged

    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty() || !canCollect) return@rememberLauncherForActivityResult
        val file = modelFile ?: return@rememberLauncherForActivityResult
        val label = pendingClass
        val existing = samples
        val remaining = MAX_SAMPLES_PER_CATEGORY - counts[label]
        val selected = uris.distinct().take(remaining)
        scope.launch {
            busy = true
            error = null
            try {
                runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        val collected = mutableListOf<TrainingSample>()
                        val fingerprints = existing.mapTo(mutableSetOf()) { it.fingerprint }
                        var duplicates = 0
                        var unreadable = 0
                        BottleneckExtractor(file).use { extractor ->
                            selected.forEachIndexed { index, uri ->
                                currentCoroutineContext().ensureActive()
                                withContext(Dispatchers.Main) { status = "正在添加“${trimmedNames[label]}”的照片 ${index + 1}/${selected.size}…" }
                                runCatchingCancellable {
                                    val bitmap = decodeImage(context, uri, 512) ?: error("无法读取所选照片")
                                    try {
                                        TrainingSample(extractor.extract(bitmap), label)
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }.onSuccess { sample ->
                                    if (fingerprints.add(sample.fingerprint)) collected += sample else duplicates++
                                }.onFailure {
                                    unreadable++
                                    AppLog.w(PERSONALIZATION_TAG, "样本照片读取失败", it)
                                }
                            }
                        }
                        CollectedPhotos(collected, duplicates, unreadable)
                    }
                }.onSuccess { result ->
                    samples = existing + result.samples
                    status = "“${trimmedNames[label]}”新增 ${result.samples.size} 张不同照片"
                    if (result.duplicates > 0 || result.unreadable > 0 || uris.size > selected.size) {
                        status += "；跳过重复 ${result.duplicates} 张、读取失败 ${result.unreadable} 张、超出本次数量 ${uris.size - selected.size} 张"
                    }
                }.onFailure {
                    AppLog.w(PERSONALIZATION_TAG, "照片特征提取失败", it)
                    error = it.message ?: "照片处理失败"
                    status = "照片处理失败，可重新选择照片"
                }
            } finally {
                busy = false
            }
        }
    }

    val inferPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || busy || !trained) return@rememberLauncherForActivityResult
        val file = modelFile ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            prediction = null
            status = "正在识别测试照片…"
            try {
                runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        val bitmap = decodeImage(context, uri, 512) ?: error("无法读取所选照片")
                        try {
                            BottleneckExtractor(file).use { head.predict(it.extract(bitmap)) }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }.onSuccess {
                    prediction = it
                    status = "测试完成，使用的是已保存的类别与阈值"
                }.onFailure {
                    AppLog.w(PERSONALIZATION_TAG, "设备端识别失败", it)
                    error = it.message ?: "识别失败"
                    status = "测试未完成，可重新选择照片"
                }
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = "设备端学习 · 我的物品分类", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("为固定工位的物料、个人物品等建立自己的 4 类分类器。命名 → 添加已标注照片 → 训练并保存 → 相机识别。")
            Text(
                "识别画面中央的单个物品：照片会中心裁切为正方形，请让物品居中。照片不上传，训练在本机完成。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    training -> "训练中 · $trainingStep / $TRAIN_STEPS 步"
                    checkpointError != null -> "存档需要处理"
                    modelFile == null -> if (busy) "正在准备识别模型" else "识别模型未就绪"
                    trained && savedProfile == null -> "旧分类器已恢复 · 待确认类别名称"
                    trained -> "已就绪 · 4 个类别已保存"
                    else -> "未训练 · 本轮已收集 ${samples.size} 张照片"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (training) {
                LinearProgressIndicator(progress = { trainingStep.toFloat() / TRAIN_STEPS }, modifier = Modifier.fillMaxWidth())
            } else if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            checkpointError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (modelFile == null && !busy) {
                Button(onClick = { prepareAttempt++ }) { Text("重试准备模型") }
            }

            Text("1. 定义 4 个类别", style = MaterialTheme.typography.titleMedium)
            if (trained) {
                Text(
                    if (savedProfile == null) {
                        "旧训练结果已保留，但旧存档没有名称。可先在第 4 步测试照片，确认旧类别 1–4 各自含义，再按原顺序命名并确认保存。"
                    } else {
                        "名称与已学会的类别一一对应。修改名称仅用于更正名称；更换识别对象请在页底新建训练任务。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            names.forEachIndexed { index, name ->
                OutlinedTextField(
                    value = name,
                    onValueChange = { value -> names = names.mapIndexed { i, old -> if (i == index) value.take(MAX_CATEGORY_NAME_LENGTH) else old } },
                    label = { Text(if (trained && savedProfile == null) "旧类别 ${index + 1} 的名称" else "类别 ${index + 1} 的名称") },
                    enabled = !busy && samples.isEmpty() && checkpointError == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (namesProblem != null) Text(namesProblem, style = MaterialTheme.typography.bodySmall)
            if (samples.isNotEmpty()) {
                Text("本轮已有标注照片，名称已锁定；清空本轮照片后可修改。", style = MaterialTheme.typography.bodySmall)
            }

            Text("2. 为每类添加照片", style = MaterialTheme.typography.titleMedium)
            Text(
                "每类至少 $MIN_SAMPLES_PER_CATEGORY 张不同照片，最多 $MAX_SAMPLES_PER_CATEGORY 张。改变角度、距离、背景和光线，各类数量尽量接近。同一照片不能重复计数或归入不同类别。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "照片与特征只在本页临时使用，离开后会清空。继续训练时需重新添加每类照片；已保存的分类器会保留。",
                style = MaterialTheme.typography.bodySmall,
            )
            for (label in 0 until CLASS_COUNT) {
                Column {
                    Text(trimmedNames[label].ifEmpty { "类别 ${label + 1}（请先命名）" }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "本轮 ${counts[label]} / 至少 $MIN_SAMPLES_PER_CATEGORY 张" +
                            (savedProfile?.trainingSampleCounts?.let { " · 上次训练 ${it[label]} 张（照片未保存）" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = canCollect && counts[label] < MAX_SAMPLES_PER_CATEGORY,
                            onClick = { pendingClass = label; addPicker.launch("image/*") },
                        ) { Text("选择照片（可多选）") }
                        TextButton(
                            enabled = !busy && counts[label] > 0,
                            onClick = { samples = samples.filterNot { it.label == label } },
                        ) { Text("清空此类") }
                    }
                }
            }
            TextButton(enabled = !busy && samples.isNotEmpty(), onClick = { samples = emptyList() }) { Text("清空本轮照片") }

            Text("3. 训练并保存", style = MaterialTheme.typography.titleMedium)
            Text("未知判定阈值：${personalizationPercent(threshold)}")
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                valueRange = MIN_PERSONALIZATION_CONFIDENCE..MAX_PERSONALIZATION_CONFIDENCE,
                steps = 8,
                enabled = !busy && checkpointError == null,
            )
            Text(
                "低于阈值显示“未知”。建议从 75% 开始，用未参与训练的照片验证；陌生物品也可能高分，阈值不是准确率保证。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (trained) {
                Button(
                    enabled = !busy && namesProblem == null && profileChanged,
                    onClick = { confirmProfile = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (savedProfile == null) "确认旧类别名称并保存" else "保存名称与阈值") }
                if (profileChanged) {
                    Text("先确认并保存名称与阈值，再添加照片或继续训练。", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                if (enoughSamples) "4 类样本已齐，可以训练" else "还需：" + counts.mapIndexedNotNull { label, count ->
                    if (count < MIN_SAMPLES_PER_CATEGORY) "${trimmedNames[label].ifEmpty { "类别 ${label + 1}" }} ${MIN_SAMPLES_PER_CATEGORY - count} 张" else null
                }.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = canCollect && enoughSamples,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val trainingSamples = samples.toList()
                    val continuing = trained
                    val trainingNames = trimmedNames.toList()
                    val trainingThreshold = threshold
                    busy = true
                    training = true
                    trainingStep = 0
                    error = null
                    prediction = null
                    status = "正在训练，请保持本页打开；训练完成后自动保存"
                    scope.launch {
                        try {
                            runCatchingCancellable {
                                withContext(Dispatchers.Default) {
                                    val sampleCounts = validatePersonalizationSamples(trainingSamples)
                                    val profile = PersonalizationProfile(trainingNames, trainingThreshold, sampleCounts)
                                    // Train a candidate: failure/cancellation must not change the ready head.
                                    val candidate = SoftmaxHead(BOTTLENECK_SIZE)
                                    if (continuing) withContext(Dispatchers.IO) { candidate.restore(checkpoint) }
                                    val batches = trainingBatches(trainingSamples.shuffled(), MAX_TRAIN_BATCH)
                                    repeat(TRAIN_STEPS) { step ->
                                        currentCoroutineContext().ensureActive()
                                        candidate.train(batches[step % batches.size])
                                        if ((step + 1) % 5 == 0) withContext(Dispatchers.Main) { trainingStep = step + 1 }
                                    }
                                    currentCoroutineContext().ensureActive()
                                    withContext(Dispatchers.Main) { status = "训练完成，正在保存…" }
                                    // Complete the atomic save even if navigation cancels result delivery.
                                    withContext(NonCancellable + Dispatchers.IO) { candidate.save(checkpoint, profile) }
                                    candidate
                                }
                            }.onSuccess {
                                head = it
                                savedProfile = it.profile
                                trained = true
                                status = "训练完成并已保存。请用新照片测试，然后到插件列表启用相机识别。"
                            }.onFailure {
                                AppLog.w(PERSONALIZATION_TAG, "训练失败，原分类器已保留", it)
                                error = "训练未保存：${it.message}；原分类器已保留"
                                status = "训练未完成，可检查照片后重试"
                            }
                        } finally {
                            busy = false
                            training = false
                        }
                    }
                },
            ) { Text(if (training) "训练中 $trainingStep / $TRAIN_STEPS…" else if (trained) "继续训练并保存" else "训练并保存") }

            Text("4. 验证并用于相机", style = MaterialTheme.typography.titleMedium)
            Text(
                "先选择未参与训练的照片检查结果。满意后返回插件列表，打开“设备端学习”开关，再进入相机，将单个物品置于画面中央。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(enabled = modelFile != null && !busy && trained, onClick = { inferPicker.launch("image/*") }) {
                Text("选择新照片测试")
            }
            prediction?.let { scores ->
                Text(personalizationResultText(scores, savedProfile), style = MaterialTheme.typography.titleMedium)
                scores.forEachIndexed { index, score ->
                    Text("${personalizationCategoryName(savedProfile, index)}：${personalizationPercent(score)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(enabled = !busy, onClick = { confirmReset = true }) { Text("新建训练任务（删除现有分类器）") }
        }
    }

    if (confirmProfile) {
        AlertDialog(
            onDismissRequest = { confirmProfile = false },
            title = { Text("确认类别对应关系") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("只更正名称与阈值，保留原训练结果。请确认每个新名称仍表示原来学会的同一类物品：")
                    trimmedNames.forEachIndexed { index, name -> Text("${personalizationCategoryName(savedProfile, index)} → $name") }
                    Text("阈值：${personalizationPercent(threshold)}")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val profile = PersonalizationProfile(trimmedNames, threshold, savedProfile?.trainingSampleCounts)
                    confirmProfile = false
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            runCatchingCancellable {
                                withContext(NonCancellable + Dispatchers.IO) { head.save(checkpoint, profile) }
                            }.onSuccess {
                                savedProfile = profile
                                names = profile.categoryNames
                                prediction = null
                                status = "类别名称与阈值已保存，原训练结果已保留"
                            }.onFailure { error = "保存失败：${it.message}；原存档已保留" }
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("对应无误，保存") }
            },
            dismissButton = { TextButton(onClick = { confirmProfile = false }) { Text("取消") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("新建训练任务？") },
            text = { Text("这会删除已保存的类别、训练结果和本轮照片特征，无法撤销。原始照片不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            runCatchingCancellable {
                                withContext(NonCancellable + Dispatchers.IO) {
                                    check(!checkpoint.exists() || checkpoint.delete()) { "无法删除训练存档" }
                                }
                            }.onSuccess {
                                head = SoftmaxHead(BOTTLENECK_SIZE)
                                savedProfile = null
                                trained = false
                                names = List(CLASS_COUNT) { "" }
                                threshold = DEFAULT_PERSONALIZATION_CONFIDENCE
                                samples = emptyList()
                                prediction = null
                                checkpointError = null
                                status = "已新建训练任务，请为 4 个类别命名"
                            }.onFailure { error = "新建失败：${it.message}" }
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("删除并新建") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

/** runCatching swallows coroutine cancellation; a tab switch must not surface as an error. */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
