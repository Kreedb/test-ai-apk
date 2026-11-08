package com.example.minimalapk.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.minimalapk.R
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.log.LogEntry
import com.example.minimalapk.log.LogLevel

@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = AppLog.entries
    var live by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val lastEntry = entries.lastOrNull()

    // Follow new lines only while live so paused users can read older entries. Key on the last
    // entry rather than the size: once the buffer hits its cap the size stops changing, and a
    // size key would silently stop following the tail.
    LaunchedEffect(live, lastEntry) {
        if (live && lastEntry != null) listState.scrollToItem(entries.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = "日志", onBack = onBack) {
            IconButton(onClick = { live = !live }) {
                if (live) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = "暂停")
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "继续")
                }
            }
            IconButton(onClick = AppLog::clear) {
                Icon(Icons.Filled.Delete, contentDescription = "清空")
            }
            IconButton(
                enabled = entries.isNotEmpty(),
                onClick = { context.shareLog(AppLog.snapshot()) },
            ) {
                Icon(Icons.Filled.Share, contentDescription = "分享")
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无日志",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(count = entries.size) { index ->
                        LogRow(entries[index])
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val colors = MaterialTheme.colorScheme
    val levelColor = when (entry.level) {
        LogLevel.INFO -> Color(0xFF2196F3)
        LogLevel.WARN -> Color(0xFFFF9800)
        LogLevel.ERROR -> colors.error
    }
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = colors.onSurfaceVariant)) {
                append("${entry.time} ")
            }
            withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold)) {
                append("${entry.level.letter} ")
            }
            withStyle(SpanStyle(color = colors.primary)) {
                append("[${entry.tag}] ")
            }
            withStyle(SpanStyle(color = colors.onSurface)) {
                append(entry.message)
            }
        },
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

private fun Context.shareLog(entries: List<LogEntry>) {
    // shortcut: share recent text instead of a file; move to FileProvider if full history matters.
    val text = entries.joinToString("\n", transform = AppLog::format)
        .takeLast(MAX_SHARE_CHARS)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { startActivity(Intent.createChooser(intent, "分享日志")) }
        .onFailure { Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show() }
}

private const val MAX_SHARE_CHARS = 200_000
