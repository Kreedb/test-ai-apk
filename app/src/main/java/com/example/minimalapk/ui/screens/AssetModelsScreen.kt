package com.example.minimalapk.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minimalapk.litert.lm.LocalTextModels
import com.example.minimalapk.models.AssetCatalog
import com.example.minimalapk.models.AssetImportAccessException
import com.example.minimalapk.models.AssetImportSource
import com.example.minimalapk.models.AssetProgress
import com.example.minimalapk.models.AssetResourceStore
import com.example.minimalapk.models.AssetSet
import com.example.minimalapk.models.AssetTransfer
import com.example.minimalapk.models.HttpAssetTransport
import com.example.minimalapk.models.runAssetTransfer
import com.example.minimalapk.pose.GitHubMirrorMode
import com.example.minimalapk.pose.GitHubMirrorPreference
import com.example.minimalapk.pose.rewriteGitHubUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Appears inside the existing remote-model menu, sharing its settings and file storage. */
@Composable
internal fun AssetCatalogSection(enabled: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { AssetResourceStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    var sets by remember { mutableStateOf(emptyList<AssetSet>()) }
    var ready by remember { mutableStateOf(emptySet<String>()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(store, revision) {
        try {
            val loaded = withContext(Dispatchers.IO) { AssetCatalog.load(context) + LocalTextModels.assetSets(context) }
            sets = loaded
            ready = withContext(Dispatchers.IO) {
                loaded.filter { store.installedDirectory(it) != null }.map { it.id }.toSet()
            }
            loadError = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            loadError = "读取资源目录失败：${error.message}"
        }
    }
    Text("LiteRT 完整资源集", style = MaterialTheme.typography.titleSmall)
    Text("大模型按声明大小流式下载；也可选择已获授权的整套文件导入。资源安装完成不代表该场景已通过推理验证。",
        style = MaterialTheme.typography.bodySmall)
    loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    sets.forEach { set ->
        ElevatedCard(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { selected = set.id }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(set.name, style = MaterialTheme.typography.titleSmall)
                Text("${set.group} · ${set.files.size} 文件 · ${assetByteSize(set.totalBytes)} · ${set.licenseName}",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (set.id in ready) "完整资源已校验 · 点击管理/删除" else "未安装/版本待校验 · 点击下载或导入",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    sets.firstOrNull { it.id == selected }?.let { set ->
        AssetSetDialog(set, store, onDismiss = { selected = null })
    }
}

@Composable
private fun AssetSetDialog(set: AssetSet, store: AssetResourceStore, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mirror = remember(context) { GitHubMirrorPreference.load(context) }
    val usesMirror = set.files.any { rewriteGitHubUrl(it.url, mirror.prefix) != it.url }
    val revision by store.revision.collectAsStateWithLifecycle()
    val progressFlow = remember(set.id) { MutableStateFlow<AssetProgress?>(null) }
    val progress by progressFlow.collectAsStateWithLifecycle()
    var installed by remember(set.id) { mutableStateOf(false) }
    var hasFiles by remember(set.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(store, set, revision, busy) {
        try {
            val state = withContext(Dispatchers.IO) {
                val ready = store.installedDirectory(set) != null
                val exists = listOf(set.id, ".${set.id}.partial", ".${set.id}.staging", ".${set.id}.backup", ".${set.id}.deleted")
                    .any { java.io.File(store.root, it).exists() }
                ready to exists
            }
            installed = state.first
            hasFiles = state.second
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            installed = false
            message = "读取资源状态失败：${error.message}"
            failed = true
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) job?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }

    fun execute(success: String, action: (AssetTransfer, (AssetProgress) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        cancelling = false
        message = null
        failed = false
        progressFlow.value = null
        job = scope.launch {
            try {
                runAssetTransfer { transfer -> action(transfer) { progressFlow.value = it } }
                message = success
            } catch (error: CancellationException) {
                message = "已取消未提交的操作；已校验文件与有效 HTTP 断点可在下次继续。最终资源状态见上方。"
                throw error
            } catch (error: Exception) {
                message = error.message ?: "资源操作失败"
                failed = true
            } finally {
                busy = false
                cancelling = false
                job = null
                progressFlow.value = null
            }
        }
    }

    fun download(preference: GitHubMirrorPreference) = execute("完整资源安装并校验完成") { transfer, report ->
        store.install(set, HttpAssetTransport(preference.prefix), transfer, report)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picking = false
        if (uris.isNotEmpty()) execute("所选完整资源已导入并校验") { transfer, report ->
            require(uris.size == set.files.size) { "需要完整的 ${set.files.size} 个文件，已选择 ${uris.size} 个" }
            val sources = try {
                uris.map { uri ->
                    transfer.checkpoint()
                    context.assetImportSource(uri)
                }
            } catch (error: SecurityException) {
                throw AssetImportAccessException(error)
            }
            store.importFiles(set, sources, transfer, report)
        }
    }

    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        title = { Text(set.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${set.id} · ${assetByteSize(set.totalBytes)} · 声明后端 ${set.backend}", style = MaterialTheme.typography.bodySmall)
                Text("许可：${set.licenseName}\n${set.licenseUrl}", style = MaterialTheme.typography.bodySmall)
                Text("来源：${set.modelCardUrl}", style = MaterialTheme.typography.bodySmall)
                Text(if (usesMirror) "GitHub 下载源：${mirror.summary}" else "下载源：官方 HTTPS 直连（非 GitHub 资源不使用镜像）",
                    style = MaterialTheme.typography.bodySmall)
                set.files.forEach { file ->
                    Text("${file.name} · ${assetByteSize(file.bytes)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(if (installed) "完整资源已校验，可供对应场景加载" else "未安装/版本待校验；缺任一文件均不能标为已安装",
                    style = MaterialTheme.typography.bodySmall)
                Text("选择文件时需一次选齐上列文件，保留实际文件名。授权受限资源请先在来源站点依法获取；应用不接收访问 token。" +
                    "安装前检查空间，下载/导入失败保留旧资源。",
                    style = MaterialTheme.typography.bodySmall)
                progress?.let { value ->
                    LinearProgressIndicator(
                        progress = { (value.completedBytes.toDouble() / value.totalBytes.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${value.stage}${value.fileName?.let { " · $it" }.orEmpty()}\n" +
                        "${assetByteSize(value.completedBytes)} / ${assetByteSize(value.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (busy && progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall) }
                if (busy) {
                    TextButton(enabled = !cancelling, onClick = { cancelling = true; job?.cancel() }) {
                        Text(if (cancelling) "正在取消并清理…" else "取消传输")
                    }
                } else {
                    TextButton(enabled = !picking, onClick = { picking = true; picker.launch(arrayOf("*/*")) }) {
                        Text("选择完整文件集导入")
                    }
                    if (failed && usesMirror) TextButton(onClick = {
                        download(GitHubMirrorPreference(mode = GitHubMirrorMode.DIRECT))
                    }) { Text("直连重试") }
                    if (hasFiles) TextButton(onClick = { confirmDelete = true }) { Text("删除此资源集与下载断点") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !picking, onClick = { download(mirror) }) {
                Text(if (installed) "重新核验" else "下载 / 继续")
            }
        },
        dismissButton = { TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("关闭") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("删除 ${set.name}？") },
        text = { Text("使用此资源的模型会先释放。删除全部文件、下载断点和临时目录后可重新下载或导入。") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            execute("资源集已删除") { transfer, _ -> store.delete(set.id, transfer) }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

private fun Context.assetImportSource(uri: Uri): AssetImportSource {
    val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    val metadata = contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
        require(cursor.moveToFirst()) { "无法读取所选文件信息，请重新选择" }
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        require(nameIndex >= 0 && !cursor.isNull(nameIndex)) { "文件提供程序未提供实际文件名" }
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0L } else null
        cursor.getString(nameIndex) to size
    } ?: error("无法读取所选文件信息")
    return AssetImportSource(metadata.first, metadata.second) {
        contentResolver.openInputStream(uri) ?: error("无法打开 ${metadata.first}")
    }
}

@Composable
internal fun ModelTransferProgress(progress: Pair<Long, Long?>?, committing: Boolean) {
    val total = progress?.second
    if (!committing && total != null && total > 0) {
        LinearProgressIndicator(progress = { (progress.first.toDouble() / total).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth())
    } else LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(if (committing) "正在完成本地模型与配置登记…" else progress?.let {
        assetByteSize(it.first) + (it.second?.let { total -> " / ${assetByteSize(total)}" } ?: "（上限 64 MiB）")
    } ?: "正在读取模型…", style = MaterialTheme.typography.bodySmall)
}

internal fun assetByteSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GiB", bytes.toDouble() / (1024L * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MiB", bytes.toDouble() / (1024L * 1024))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes.toDouble() / 1024)
    else -> "$bytes B"
}
