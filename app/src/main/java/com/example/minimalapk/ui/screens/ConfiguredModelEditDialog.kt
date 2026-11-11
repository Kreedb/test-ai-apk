package com.example.minimalapk.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredModelRunner
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.InputSource
import com.example.minimalapk.models.MAX_CONFIG_BYTES
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.ModelRunResult
import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.readCapped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ConfiguredModelEditDialog(model: ConfiguredModel, onDismiss: () -> Unit, onDelete: () -> Unit) {
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
    var showDetails by remember { mutableStateOf(false) }
    var showRawConfig by remember { mutableStateOf(false) }
    var showFileRun by remember { mutableStateOf(false) }
    val parsed = remember(text, description) { description?.let { runCatching { ModelConfig.decode(text, it) } } }
    val signature = remember(text, description) { description?.let { modelConfigSignature(text, it) } }

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
                AppLog.w("ModelConfig", "操作失败", e)
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
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(model.name) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("图片模型在列表中开启后会与其它启用模型一起运行。文件输入、配置导入/导出及单次测试均在此处完成。",
                    style = MaterialTheme.typography.bodySmall)
                parsed?.getOrNull()?.let { config ->
                    config.inputs.forEachIndexed { index, input ->
                        Text("输入 $index · ${input.source} · ${input.shape}", style = MaterialTheme.typography.bodySmall)
                    }
                    config.outputs.forEachIndexed { index, output ->
                        Text("输出 $index · ${output.render}" + if (output.labels.isEmpty()) "" else " · ${output.labels.size} 个标签",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                signature?.let { graph ->
                    ModelConfigFields(text, graph, !busy) {
                        text = it
                        result = null
                    }
                }
                TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "收起模型文件信息" else "模型文件信息与签名") }
                if (showDetails) {
                    description?.let { graph ->
                        val active = signature ?: graph.signatures.first()
                        SelectionContainer {
                            Text(buildString {
                                appendLine("可用签名：${graph.signatures.joinToString { it.key.ifEmpty { "主子图" } }}")
                                appendLine("签名：${active.key.ifEmpty { "主子图" }}")
                                active.inputs.forEachIndexed { index, tensor -> appendLine("输入 $index ${tensor.name} ${tensor.typeName} 形状 ${tensor.shape} 签名 ${tensor.signature} 量化 scale=${tensor.scale} zero-point=${tensor.zeroPoint}") }
                                active.outputs.forEachIndexed { index, tensor -> appendLine("输出 $index ${tensor.name} ${tensor.typeName} 形状 ${tensor.shape} 签名 ${tensor.signature}") }
                                if (graph.customOps.isNotEmpty()) appendLine("自定义算子：${graph.customOps.joinToString()}；如运行失败，请提供兼容运行时或重新导出。")
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("名称、形状、dtype、量化与签名读自模型文件；归一化、输出含义、标签和采样率请按模型说明确认。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = !busy, onClick = { showSignatures = true }) { Text("选择签名并生成配置") }
                        if (showSignatures) OptionDialog(
                            title = "签名（选择后重建当前配置）", options = graph.signatures, selected = active,
                            label = { it.key.ifEmpty { "主子图" } }, onSelect = {
                                text = ModelConfig.infer(it).encode()
                                files = emptyMap()
                                result = null
                                showSignatures = false
                                showRawConfig = true
                            }, onDismiss = { showSignatures = false },
                        )
                    }
                }
                TextButton(onClick = { showRawConfig = !showRawConfig }) { Text(if (showRawConfig) "收起配置文件" else "高级：编辑 properties 配置文件") }
                if (showRawConfig) {
                    Text("UTF-8 properties：IMAGE 按配置尺寸缩放，mean/std 控制 (像素−mean)/std；NUMBERS 单值填充或逗号分隔；" +
                        "TEXT 每行一个字符串；AUDIO 读取 PCM16 WAV；BINARY 为 little-endian 张量。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = text, onValueChange = { if (it.length <= MAX_CONFIG_BYTES) { text = it; result = null } },
                        enabled = !busy, label = { Text("模型配置") }, modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 420.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                parsed?.exceptionOrNull()?.let { Text("配置待修正：${it.message}", color = MaterialTheme.colorScheme.error) }
                Row {
                    TextButton(enabled = !busy && description != null, onClick = { configPicker.launch(arrayOf("*/*")) }) { Text("导入配置") }
                    TextButton(enabled = !busy && text.isNotEmpty(), onClick = { configExport.launch("${model.id.take(8)}.properties") }) { Text("导出配置") }
                }
                TextButton(onClick = { showFileRun = !showFileRun }) { Text(if (showFileRun) "收起文件测试" else "选择输入并测试") }
                if (showFileRun) {
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
                    } }) { Text(if (busy) "处理中…" else "运行当前参数") }
                }
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
        },
        confirmButton = {
            TextButton(enabled = !busy && parsed?.isSuccess == true, onClick = { action {
                val snapshot = text
                withContext(Dispatchers.IO) { ConfiguredModelStore.saveConfig(model, snapshot, checkNotNull(description)) }
                message = "配置已保存，相机自动更新"
            } }) { Text("保存") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } },
    )
}
