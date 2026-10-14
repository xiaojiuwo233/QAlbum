package com.xjw.qalbum.ui.logs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.IconButtonDefaults
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.SearchField
import com.xjw.qalbum.log.AppLog
import com.xjw.qalbum.log.LogEntry
import com.xjw.qalbum.log.LogLevel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * 日志查看页（仿 logcat）：级别过滤 + 文字搜索 + 自动跟随最新 + 导出 / 清空。
 * 文本可长按选择复制。进入「设置 → 日志」打开；遵循各页自管状态栏 inset 的约定。
 */
@Composable
fun LogScreen(
    onBack: () -> Unit,
    viewModel: LogViewModel = koinViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    // 仅当已在底部附近时才自动跟随，避免用户上滑查看历史时被新日志拽回去。
    // 判定必须完全基于同一份 layoutInfo 快照（visibleItemsInfo 与 totalItemsCount 同源）：
    // entries 更新触发 LaunchedEffect 时布局尚未重算，若拿旧可见项对比新 lastIndex，
    // 一次采样批量进 ≥2 条日志就会误判「不在底部」，跟随从此永久失效
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(ui.entries) {
        if (nearBottom && ui.entries.isNotEmpty()) listState.scrollToItem(ui.entries.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日志") },
                subtitle = { Text("共 ${ui.totalCount} 条 · 占用 ${formatBytes(ui.diskSizeBytes)}") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (ui.entries.isNotEmpty()) listState.animateScrollToItem(ui.entries.lastIndex)
                            }
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Outlined.VerticalAlignBottom, contentDescription = "跳到最新")
                    }
                    IconButton(
                        onClick = {
                            viewModel.export { uri ->
                                if (uri != null) {
                                    runCatching { context.startActivity(shareLogChooser(uri)) }
                                        .onFailure { scope.launch { snackbarHostState.showSnackbar("没有可以接收日志的应用") } }
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("导出失败") }
                                }
                            }
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Outlined.IosShare, contentDescription = "导出")
                    }
                    IconButton(onClick = { showClearDialog = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topBarPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarPadding.calculateTopPadding()),
        ) {
            LogControls(
                ui = ui,
                onLevel = viewModel::setMinLevel,
                onQuery = viewModel::setQuery,
            )

            if (ui.entries.isEmpty()) {
                EmptyState(
                    icon = if (ui.totalCount == 0) Icons.AutoMirrored.Outlined.Article else Icons.Outlined.SearchOff,
                    title = if (ui.totalCount == 0) "暂无日志" else "没有匹配的日志",
                )
            } else {
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(ui.entries) { entry -> LogRow(entry) }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        ConfirmDialog(
            icon = Icons.Outlined.DeleteSweep,
            title = "清空日志？",
            text = "删除全部日志（含历史文件），无法恢复。",
            confirmLabel = "清空",
            destructive = true,
            onDismiss = { showClearDialog = false },
            onConfirm = {
                showClearDialog = false
                viewModel.clear()
                scope.launch { snackbarHostState.showSnackbar("已清空日志") }
            },
        )
    }
}

/** 过滤控件：级别 chips + 搜索框 */
@Composable
private fun LogControls(
    ui: LogUiState,
    onLevel: (LogLevel) -> Unit,
    onQuery: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchField(value = ui.query, onValueChange = onQuery, placeholder = "搜索 TAG 或内容")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LEVEL_FILTERS.forEach { (label, level) ->
                FilterChip(
                    selected = ui.minLevel == level,
                    onClick = { onLevel(level) },
                    label = { Text(label) },
                )
            }
        }
        if (ui.entries.size < ui.totalCount) {
            Text(
                "显示 ${ui.entries.size} / ${ui.totalCount} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单行日志：时间灰、级别色、TAG 次要色、正文按级别着色，等宽小字。 */
@Composable
private fun LogRow(entry: LogEntry) {
    val time = MaterialTheme.colorScheme.outline
    val tagColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lvlColor = levelColor(entry.level)
    val bodyColor = when (entry.level) {
        LogLevel.E, LogLevel.A -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = time)) { append(AppLog.formatTime(entry.timeMillis)) }
        append(' ')
        withStyle(SpanStyle(color = lvlColor, fontWeight = FontWeight.Bold)) { append(entry.level.char) }
        append(' ')
        withStyle(SpanStyle(color = tagColor)) { append(entry.tag) }
        withStyle(SpanStyle(color = tagColor)) { append(": ") }
        withStyle(SpanStyle(color = bodyColor)) { append(entry.message) }
    }
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.E, LogLevel.A -> MaterialTheme.colorScheme.error
    LogLevel.W -> MaterialTheme.colorScheme.tertiary
    LogLevel.I -> MaterialTheme.colorScheme.primary
    LogLevel.D -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.V -> MaterialTheme.colorScheme.outline
}

/** 级别过滤项：选中即「该级别及以上」。 */
private val LEVEL_FILTERS: List<Pair<String, LogLevel>> = listOf(
    "全部" to LogLevel.V,
    "调试" to LogLevel.D,
    "信息" to LogLevel.I,
    "警告" to LogLevel.W,
    "错误" to LogLevel.E,
)

private fun shareLogChooser(uri: Uri): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        "导出日志",
    )

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024f)
    else -> "%.1f MB".format(b / 1024f / 1024f)
}
