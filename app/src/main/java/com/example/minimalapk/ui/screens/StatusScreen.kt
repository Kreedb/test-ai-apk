package com.example.minimalapk.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.minimalapk.BuildConfig
import com.example.minimalapk.pose.DeviceUsage
import com.example.minimalapk.pose.DeviceUsageSampler
import com.example.minimalapk.pose.PoseAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun StatusScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    var usage by remember { mutableStateOf(DeviceUsage()) }
    var cpu by remember { mutableStateOf("N/A") }
    var gpu by remember { mutableStateOf("N/A") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cpu = cpuDescription()
            gpu = gpuDescription()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        val sampler = DeviceUsageSampler()
        while (isActive) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                usage = withContext(Dispatchers.IO) { sampler.sample() }
            } else {
                sampler.reset()
            }
            delay(1_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = "主页",
            subtitle = "推理后端与环境信息",
        )

        val provider = PoseAnalyzer.lastExecutionProvider
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = if (provider != null) {
                                    Color(0xFF2E7D32)
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape,
                            ),
                    )
                    Text(
                        text = "推理后端",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = provider ?: "未启动 · ncnn Vulkan 优先，无可用 Vulkan 时回退 CPU",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                InfoRow("CPU 占用（本应用）", formatUsage(usage.cpuPercent))
                InfoRow("GPU 占用（系统）", formatUsage(usage.gpuPercent))
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                InfoRow("应用版本", BuildConfig.VERSION_NAME)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                InfoRow("CPU", cpu)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                InfoRow("GPU", gpu)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                InfoRow("设备型号", Build.MODEL)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                InfoRow("系统版本", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                InfoRow("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                InfoRow("推理框架", "ncnn")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                InfoRow("推理模型", "YOLO26n-pose 640×640")
            }
        }
    }
}

private fun cpuDescription(): String {
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
    val model = soc
        ?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        ?: cpuHardware(runCatching { File("/proc/cpuinfo").readText() }.getOrNull())
        ?: Build.HARDWARE
    return "$model · ${Runtime.getRuntime().availableProcessors()} 核"
}

private fun gpuDescription(): String =
    GPU_MODEL_NODES.firstNotNullOfOrNull { path ->
        runCatching { File(path).takeIf(File::canRead)?.readText() }.getOrNull()
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
    } ?: "N/A"

internal fun cpuHardware(cpuinfo: String?): String? =
    cpuinfo?.lineSequence()
        ?.firstOrNull { it.substringBefore(':').trim().equals("Hardware", ignoreCase = true) }
        ?.substringAfter(':', "")
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private val GPU_MODEL_NODES = listOf(
    "/sys/class/kgsl/kgsl-3d0/gpu_model",
    "/sys/class/kgsl/kgsl-3d0/devfreq/device/of_node/gpu-model",
    "/sys/class/misc/mali0/device/gpuinfo",
    "/sys/kernel/gpu/gpu_model",
)

private fun formatUsage(value: Float?): String =
    value?.let { String.format(Locale.US, "%.1f%%", it) } ?: "N/A"

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.End,
        )
    }
}
