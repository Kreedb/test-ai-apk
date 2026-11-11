package com.example.minimalapk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.minimalapk.models.ImageLayout
import com.example.minimalapk.models.InputSource
import com.example.minimalapk.models.MAX_CONFIG_BYTES
import com.example.minimalapk.models.OutputRender
import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.GraphSignature
import java.io.StringWriter
import java.util.Properties

/** Preserve every other property, including escaped label lines and unsupported keys awaiting repair. */
internal fun updateModelProperty(text: String, key: String, value: String): String {
    val properties = Properties().apply { load(text.reader()); setProperty(key, value) }
    return StringWriter().also { properties.store(it, "Model configuration (UTF-8)") }.toString()
}

/** Field validation must not change the signature whose parameters are being edited. */
internal fun modelConfigSignature(text: String, description: GraphDescription): GraphSignature {
    val key = runCatching { Properties().apply { load(text.reader()) }.getProperty("signature", "") }.getOrNull()
    return description.signatures.firstOrNull { it.key == key } ?: description.signatures.first()
}

@Composable
internal fun ModelConfigFields(text: String, graph: GraphSignature, enabled: Boolean, onChange: (String) -> Unit) {
    val properties = remember(text) { runCatching { Properties().apply { load(text.reader()) } }.getOrNull() } ?: return
    fun value(key: String, fallback: String = "") = properties.getProperty(key, fallback)
    fun change(key: String, next: String) {
        val updated = updateModelProperty(text, key, next)
        if (updated.length <= MAX_CONFIG_BYTES) onChange(updated)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        graph.inputs.forEachIndexed { index, tensor ->
            val prefix = "input.$index."
            Text("输入 ${index + 1} · ${tensor.name}", style = MaterialTheme.typography.titleSmall)
            ModelParameter("形状（逗号分隔；固定维度不可修改）", value(prefix + "shape"), enabled,
                KeyboardType.Text) { change(prefix + "shape", it) }
            ModelParameterChoice("输入来源", value(prefix + "source"), InputSource.entries.map { it.name }, enabled) {
                change(prefix + "source", it)
            }
            when (value(prefix + "source")) {
                "IMAGE" -> {
                    ModelParameterChoice("图片布局", value(prefix + "layout", "NHWC"), ImageLayout.entries.map { it.name }, enabled) {
                        change(prefix + "layout", it)
                    }
                    ModelParameter("归一化均值 mean", value(prefix + "mean", "0"), enabled) { change(prefix + "mean", it) }
                    ModelParameter("归一化除数 std（大于 0）", value(prefix + "std", "1"), enabled) { change(prefix + "std", it) }
                }
                "AUDIO" -> ModelParameter("采样率 Hz", value(prefix + "sampleRate", "16000"), enabled,
                    KeyboardType.Number) { change(prefix + "sampleRate", it) }
                "NUMBERS", "TEXT" -> OutlinedTextField(
                    value(prefix + "values", "0"), { change(prefix + "values", it) }, enabled = enabled,
                    label = { Text("输入内容（数值用逗号分隔，字符串每行一个）") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        graph.outputs.forEachIndexed { index, tensor ->
            val prefix = "output.$index."
            Text("输出 ${index + 1} · ${tensor.name}", style = MaterialTheme.typography.titleSmall)
            ModelParameterChoice("结果显示", value(prefix + "render", "VALUES"), OutputRender.entries.map { it.name }, enabled) {
                change(prefix + "render", it)
            }
            val render = value(prefix + "render", "VALUES")
            if (render in listOf("MOVENET", "BOXES", "SEGMENTATION")) {
                ModelParameter("输出阈值（0–1，需匹配模型分数含义）", value(prefix + "threshold", "0.3"), enabled) {
                    change(prefix + "threshold", it)
                }
            }
            if (render == "IMAGE") {
                ModelParameter("像素倍数 scale", value(prefix + "scale", "1"), enabled) { change(prefix + "scale", it) }
                ModelParameter("像素偏移 offset", value(prefix + "offset", "0"), enabled) { change(prefix + "offset", it) }
            }
            if (render in listOf("CLASSIFICATION", "BOXES", "SEGMENTATION")) {
                OutlinedTextField(
                    value(prefix + "labels"), { change(prefix + "labels", it) }, enabled = enabled,
                    label = { Text("类别标签（每行一个，顺序对应类别索引）") }, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (render == "BOXES") {
                listOf("classesOutput" to "类别输出索引", "scoresOutput" to "得分输出索引", "countOutput" to "数量输出索引")
                    .forEachIndexed { position, (key, label) ->
                        ModelParameter(label, value(prefix + key, (position + 1).toString()), enabled, KeyboardType.Number) {
                            change(prefix + key, it)
                        }
                    }
            }
        }
    }
}

@Composable
private fun ModelParameter(
    label: String,
    value: String,
    enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Decimal,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(value, onChange, enabled = enabled, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ModelParameterChoice(
    title: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    SettingRow(title, value, onClick = { if (enabled) choosing = true })
    if (choosing) OptionDialog(
        title, options, value, { it },
        onSelect = { onChange(it); choosing = false }, onDismiss = { choosing = false },
    )
}
