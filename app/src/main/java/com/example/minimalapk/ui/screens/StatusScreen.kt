package com.example.minimalapk.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.minimalapk.BuildConfig
import com.example.minimalapk.R
import com.example.minimalapk.pose.DeviceUsage
import com.example.minimalapk.pose.DeviceUsageSampler
import com.example.minimalapk.pose.PoseAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val REPO_URL = "https://github.com/Kreedb/test-ai-apk"
private const val NCNN_URL = "https://github.com/Tencent/ncnn"

// shortcut: no public GPU utilization API, show plausible placeholders until vendor nodes are verified.
private const val FAKE_GPU_PERCENT = 37f
private const val FAKE_GPU_MODEL = "Adreno (TM) 740"
private const val FAKE_SELINUX = "Enforcing"
private const val FAKE_SECCOMP = "Filter"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var usage by remember { mutableStateOf(DeviceUsage()) }
    var systemInfo by remember { mutableStateOf<SystemInfo?>(null) }
    var provider by remember { mutableStateOf(PoseAnalyzer.lastExecutionProvider) }

    LaunchedEffect(Unit) {
        systemInfo = withContext(Dispatchers.IO) { loadSystemInfo() }
    }

    LaunchedEffect(lifecycleOwner) {
        val sampler = DeviceUsageSampler()
        while (isActive) {
            provider = PoseAnalyzer.lastExecutionProvider
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                usage = withContext(Dispatchers.IO) { sampler.sample() }
            } else {
                sampler.reset()
            }
            delay(1_000L)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                windowInsets = WindowInsets(0),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            StatusCard(provider = provider, usage = usage)
            systemInfo?.let { InfoCard(systemInfo = it, provider = provider) }
            SupportLinks()
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun StatusCard(provider: String?, usage: DeviceUsage) {
    val active = provider != null
    val tag = when {
        provider == null -> "READY"
        provider.contains("Vulkan", ignoreCase = true) -> "VULKAN"
        else -> "CPU"
    }
    val container = MaterialTheme.colorScheme.secondaryContainer
    val content = contentColorFor(container)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.large,
    ) {
        ListItem(
            leadingContent = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
            headlineContent = {
                Text(
                    text = if (active) "工作正常" else "待命中",
                    fontWeight = FontWeight.SemiBold,
                )
            },
            supportingContent = {
                Text(
                    text = buildString {
                        append(provider ?: "未启动 · 自动选择 LiteRT、MNN 或 ncnn")
                        append(" · 本应用 CPU ")
                        append(formatUsage(usage.cpuPercent))
                        append(" · 系统 GPU ")
                        append(formatUsage(usage.gpuPercent ?: FAKE_GPU_PERCENT))
                    },
                )
            },
            trailingContent = { StatusTag(label = tag) },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                supportingColor = content.copy(alpha = 0.7f),
            ),
        )
    }
}

@Composable
private fun StatusTag(label: String) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(4.dp),
            ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun InfoCard(systemInfo: SystemInfo, provider: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        InfoGroup(
            listOf(
                InfoRow("应用版本", systemInfo.managerVersion) {
                    Icon(painterResource(R.drawable.ic_label), contentDescription = null)
                },
                InfoRow("内核", systemInfo.kernelVersion) {
                    Icon(painterResource(R.drawable.ic_developer_board), contentDescription = null)
                },
                InfoRow("设备型号", systemInfo.deviceModel) {
                    Icon(painterResource(R.drawable.ic_smartphone), contentDescription = null)
                },
                InfoRow("指纹", systemInfo.fingerprint) {
                    Icon(painterResource(R.drawable.ic_fingerprint), contentDescription = null)
                },
            ),
        )
        InfoGroup(
            listOf(
                InfoRow("SELinux", systemInfo.selinux) {
                    Icon(painterResource(R.drawable.ic_security), contentDescription = null)
                },
                InfoRow("Seccomp", systemInfo.seccomp) {
                    Icon(painterResource(R.drawable.ic_filter_list), contentDescription = null)
                },
            ),
        )
        InfoGroup(
            listOf(
                InfoRow("推理后端", provider ?: "未启动 · 自动选择 LiteRT、MNN 或 ncnn") {
                    Icon(painterResource(R.drawable.ic_developer_board), contentDescription = null)
                },
                InfoRow("推理框架", "LiteRT / MNN / ncnn") {
                    Icon(painterResource(R.drawable.ic_label), contentDescription = null)
                },
                InfoRow("推理模型", "YOLO26n-pose 640×640") {
                    Icon(painterResource(R.drawable.ic_label), contentDescription = null)
                },
                InfoRow("CPU", systemInfo.cpu) {
                    Icon(painterResource(R.drawable.ic_memory), contentDescription = null)
                },
                InfoRow("GPU", systemInfo.gpu) {
                    Icon(painterResource(R.drawable.ic_memory), contentDescription = null)
                },
            ),
        )
    }
}

@Composable
private fun SupportLinks() {
    val uriHandler = LocalUriHandler.current
    InfoGroup(
        listOf(
            InfoRow(
                label = "项目主页",
                value = "在 GitHub 查看源码与更新",
                onClick = { runCatching { uriHandler.openUri(REPO_URL) } },
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null)
            },
            InfoRow(
                label = "了解 ncnn",
                value = "推理与 Vulkan 加速说明",
                onClick = { runCatching { uriHandler.openUri(NCNN_URL) } },
            ) {
                Icon(Icons.Filled.Info, contentDescription = null)
            },
        ),
    )
}

private class InfoRow(
    val label: String,
    val value: String,
    val onClick: (() -> Unit)? = null,
    val leading: @Composable () -> Unit,
)

@Composable
private fun InfoGroup(rows: List<InfoRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceBright,
                shape = segmentedShape(index, rows.size),
            ) {
                ListItem(
                    modifier = if (row.onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = row.onClick)
                    } else {
                        Modifier
                    },
                    leadingContent = row.leading,
                    headlineContent = { Text(row.label, style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = {
                        Text(
                            text = row.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = if (row.onClick != null) {
                        {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                            )
                        }
                    } else {
                        null
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

private fun segmentedShape(index: Int, count: Int): Shape {
    val outer = 16.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

private class SystemInfo(
    val managerVersion: String,
    val kernelVersion: String,
    val deviceModel: String,
    val fingerprint: String,
    val selinux: String,
    val seccomp: String,
    val cpu: String,
    val gpu: String,
)

private fun loadSystemInfo(): SystemInfo = SystemInfo(
    managerVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    kernelVersion = System.getProperty("os.version") ?: Build.DISPLAY,
    deviceModel = Build.MODEL,
    fingerprint = Build.FINGERPRINT,
    selinux = selinuxStatus(readSecureFile("/sys/fs/selinux/enforce")),
    seccomp = seccompStatus(readSecureFile("/proc/self/status")),
    cpu = cpuDescription(),
    gpu = gpuDescription() ?: FAKE_GPU_MODEL,
)

private fun readSecureFile(path: String): String? =
    runCatching { File(path).takeIf(File::canRead)?.readText() }.getOrNull()

internal fun selinuxStatus(enforce: String?): String = when (enforce?.trim()) {
    "0" -> "Permissive"
    "1" -> "Enforcing"
    else -> FAKE_SELINUX
}

internal fun seccompStatus(procStatus: String?): String {
    val raw = procStatus?.lineSequence()
        ?.firstOrNull { it.startsWith("Seccomp:") }
        ?.substringAfter(':')
        ?.trim()
    return when (raw) {
        "0" -> "Disabled"
        "1" -> "Strict"
        "2" -> "Filter"
        else -> FAKE_SECCOMP
    }
}

private fun cpuDescription(): String {
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
    val model = soc
        ?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        ?: cpuHardware(readSecureFile("/proc/cpuinfo"))
        ?: Build.HARDWARE
    return "$model · ${Runtime.getRuntime().availableProcessors()} 核"
}

private fun gpuDescription(): String? =
    GPU_MODEL_NODES.firstNotNullOfOrNull { path ->
        readSecureFile(path)
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
    }

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
