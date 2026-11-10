package com.example.minimalapk.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredModelRunner
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.InputSource
import com.example.minimalapk.models.MAX_CONFIG_BYTES
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.ModelRunResult
import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.ModelDownload
import com.example.minimalapk.pose.readCapped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ModelWorkbenchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(emptyList<ConfiguredModel>()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    val directory = remember { ConfiguredModelStore.directory(context) }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { ConfiguredModelStore.list(directory) } }
            .onSuccess { models = it }.onFailure { if (it is CancellationException) throw it; status = it.message }
    }
    fun install(load: () -> ConfiguredModel) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val model = withContext(Dispatchers.IO) { load() }
                models = withContext(Dispatchers.IO) { ConfiguredModelStore.list(directory) }
                status = "已保存 ${model.name}，请确认配置后运行"
                selectedId = model.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("ModelWorkbench", "导入失败", e)
                status = "导入失败：${e.message}"
            } finally {
                busy = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) install {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readCapped(ModelDownload.MAX_BYTES) }
                ?: error("无法读取模型")
            ConfiguredModelStore.install(directory, name, bytes)
        }
    }
    val model = selectedId?.let { id ->
        models.firstOrNull { it.id == id } ?: ConfiguredModel(id, "模型 ${id.take(8)}", File(directory, "$id.tflite"))
    }
    BackHandler(enabled = model != null) { selectedId = null }
    if (model != null) {
        ConfiguredModelScreen(model, onBack = { selectedId = null }, onDelete = {
            models = models.filterNot { it.id == model.id }
            selectedId = null
        })
        return
    }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("模型工作台", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("普通 TFLite、量化、多输入/输出和动态形状模型均先保存，再通过配置修正输入与输出。仅文件格式损坏会阻止解析；运行还取决于模型所需算子。", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("导入 TFLite") }
            OutlinedTextField(url, { url = it }, label = { Text("模型 https 地址") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy && url.startsWith("https://"), onClick = {
                val address = url.trim()
                install { ConfiguredModelStore.install(directory, address.substringAfterLast('/').substringBefore('?'), ModelDownload.fetch(address)) }
            }) { Text("从远程下载") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("示例模型 · 点击下载并配置", style = MaterialTheme.typography.titleMedium)
            ExampleCatalog.configurable.forEach { example ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    SettingRow(example.name, "${example.sizeBytes / 1024 / 1024} MB · 下载后离线 CPU 推理，图片模型可在相机页叠加", {
                        if (!busy) install { ExampleDownload.install(context, example) }
                    })
                }
            }
            Text("已导入", style = MaterialTheme.typography.titleMedium)
            models.forEach { entry ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    SettingRow(entry.name, "编辑/导入/导出配置，选择输入并运行", { if (!busy) selectedId = entry.id })
                }
            }
        }
    }
}

@Composable
private fun ConfiguredModelScreen(model: ConfiguredModel, onBack: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var description by remember(model.id) { mutableStateOf<GraphDescription?>(null) }
    var text by remember(model.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ModelRunResult?>(null) }
    var files by remember { mutableStateOf(emptyMap<Int, Uri>()) }
    var pendingInput by rememberSaveable { mutableIntStateOf(0) }
    var showSignatures by remember { mutableStateOf(false) }
    val parsed = remember(text, description) { description?.let { runCatching { ModelConfig.decode(text, it) } } }

    LaunchedEffect(model.id) {
        try {
            val loaded = withContext(Dispatchers.IO) { ConfiguredModelStore.describe(model) to model.configFile.readText() }
            description = loaded.first
            text = loaded.second
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { message = "读取失败：${e.message}" }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { block()
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                AppLog.w("ModelWorkbench", "操作失败", e)
                message = "操作失败：${e.message}\n请检查签名、固定/动态维度、输入来源及归一化；缺失 Flex/自定义算子需要兼容运行时或重新导出，配置不能增加算子。"
            } finally { busy = false }
        }
    }
    val inputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { files = files + (pendingInput to uri); result = null }
    }
    val configPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            val loaded = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readCapped(MAX_CONFIG_BYTES) }?.toString(Charsets.UTF_8)
                    ?: error("无法读取配置")
            }
            ModelConfig.decode(loaded, checkNotNull(description))
            text = loaded
            files = emptyMap()
            result = null
            message = "已导入配置，点击保存后持久化"
        }
    }
    val configExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) action {
            val snapshot = text
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(snapshot.toByteArray()) } ?: error("无法写入配置")
            }
            message = "配置已导出"
        }
    }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(model.name, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            description?.let { graph ->
                val active = graph.signatures.firstOrNull { it.key == parsed?.getOrNull()?.signature } ?: graph.signatures.first()
                SelectionContainer {
                    Text(buildString {
                        appendLine("签名：${active.key.ifEmpty { "主子图" }}")
                        active.inputs.forEachIndexed { index, tensor -> appendLine("输入 $index ${tensor.name} ${tensor.typeName} ${tensor.shape}（动态签名 ${tensor.signature}，量化 ${tensor.scale}/${tensor.zeroPoint}）") }
                        active.outputs.forEachIndexed { index, tensor -> appendLine("输出 $index ${tensor.name} ${tensor.typeName} ${tensor.shape}") }
                        if (graph.customOps.isNotEmpty()) appendLine("自定义算子：${graph.customOps.joinToString()}；如运行失败，请提供兼容运行时或重新导出。")
                    }, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !busy, onClick = { showSignatures = true }) { Text("选择签名并生成配置") }
                if (showSignatures) OptionDialog(
                    title = "签名（选择后重建当前配置）", options = graph.signatures, selected = active,
                    label = { it.key.ifEmpty { "主子图" } }, onSelect = {
                        text = ModelConfig.infer(it).encode(); files = emptyMap(); result = null; showSignatures = false
                    }, onDismiss = { showSignatures = false },
                )
            }
            Text("UTF-8 properties 配置：IMAGE 按配置尺寸缩放，mean/std 控制 (像素−mean)/std，量化参数取自模型；NUMBERS 单值填充或逗号分隔；TEXT 每行一个字符串；AUDIO 读取 PCM16 WAV；BINARY 为原始 little-endian 张量。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = text, onValueChange = { if (it.length <= MAX_CONFIG_BYTES) { text = it; result = null } },
                enabled = !busy, label = { Text("模型配置") }, modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 420.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            parsed?.exceptionOrNull()?.let { Text("配置待修正：${it.message}", color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(enabled = !busy && parsed?.isSuccess == true, onClick = { action {
                    val snapshot = text
                    withContext(Dispatchers.IO) { ConfiguredModelStore.saveConfig(model, snapshot, checkNotNull(description)) }
                    message = "配置已保存"
                } }) { Text("保存") }
                TextButton(enabled = !busy && description != null, onClick = { configPicker.launch(arrayOf("*/*")) }) { Text("导入配置") }
                TextButton(enabled = !busy && text.isNotEmpty(), onClick = { configExport.launch("${model.id.take(8)}.properties") }) { Text("导出配置") }
            }
            parsed?.getOrNull()?.inputs?.forEachIndexed { index, input ->
                if (input.source in listOf(InputSource.IMAGE, InputSource.AUDIO, InputSource.BINARY)) {
                    TextButton(enabled = !busy, onClick = {
                        pendingInput = index
                        inputPicker.launch(when (input.source) {
                            InputSource.IMAGE -> arrayOf("image/*")
                            InputSource.AUDIO -> arrayOf("audio/*", "application/octet-stream")
                            else -> arrayOf("*/*")
                        })
                    }) { Text("输入 $index ${input.source}：${if (files.containsKey(index)) "已选择（点击更换）" else "选择文件"}") }
                }
            }
            Button(enabled = !busy && parsed?.isSuccess == true, onClick = { action {
                result = null
                val config = parsed!!.getOrThrow()
                val selected = files.toMap()
                result = withContext(Dispatchers.IO) { ConfiguredModelRunner.run(context, model, config, selected) }
                message = "CPU 推理完成"
            } }) { Text(if (busy) "处理中…" else "运行") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            result?.let { output ->
                SelectionContainer { Text(output.text, style = MaterialTheme.typography.bodySmall) }
                output.images.forEach { (label, bitmap) ->
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Image(bitmap.asImageBitmap(), label, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                }
            }
            TextButton(enabled = !busy, onClick = { action {
                withContext(Dispatchers.IO) { ConfiguredModelStore.delete(model) }
                onDelete()
            } }) { Text("删除此模型及配置") }
        }
    }
}
