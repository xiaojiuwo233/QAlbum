package com.xjw.qalbum.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.log.AppLog
import com.xjw.qalbum.log.LogEntry
import com.xjw.qalbum.log.LogLevel
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConnectedChoiceRow
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.SearchField
import com.xjw.qalbum.ui.settings.SettingsCategory
import com.xjw.qalbum.ui.settings.settingsContainer
import com.xjw.qalbum.ui.settings.settingsSharedKey
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * 日志：按级别筛选、搜索，新日志自动跟到底部（往上翻看时不打扰，右下角「最新」回到底部并继续跟）。
 * 点一条展开全文，长按复制；右上角导出给别人看，菜单里开关详细日志、清空。从设置首页的入口「长大」打开。
 * 没开详细日志时，uid、QQ 号等已经去掉（见 LogRedactor），导出的日志可以直接附在反馈里。
 */
@Composable
fun LogScreen(
    onBack: () -> Unit,
    viewModel: LogViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val ui by viewModel.uiState.collectAsState()
    val detailed by viewModel.detailed.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDetailed by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 列表从底部（最新）开始，「回到顶部才展开」等于永远展不开：往下拉就展开、往上推就收起
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    fun notify(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
    fun export() {
        viewModel.export { uri ->
            if (uri == null) notify("导出失败")
            else runCatching { context.startActivity(shareLogChooser(uri)) }.onFailure { notify("没有能接收日志的应用") }
        }
    }

    // 跟随最新：打开时在底部；往上翻停下后不再跟，翻回底部或点「最新」再跟
    var follow by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .filter { !it }
            .collect { follow = !listState.canScrollForward }
    }
    LaunchedEffect(ui.entries, follow) {
        if (follow && ui.entries.isNotEmpty()) listState.scrollToItem(ui.entries.lastIndex)
    }

    Scaffold(
        modifier = Modifier
            .settingsContainer(settingsSharedKey(SettingsCategory.LOGS.route), sharedScope, animatedScope)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // 标题栏与搜索、级别是一整块：收起时一起换成容器色，搜索框不会像贴在标题栏下面
            val collapsed = FastOutLinearInEasing.transform(scrollBehavior.state.collapsedFraction)
            val colors = MaterialTheme.colorScheme
            Column(Modifier.background(lerp(colors.surface, colors.surfaceContainer, collapsed))) {
                LargeFlexibleTopAppBar(
                    title = { Text("日志") },
                    subtitle = {
                        Text(
                            (if (detailed) "详细日志 · " else "") +
                                if (ui.entries.size < ui.totalCount) "显示 ${formatCountExact(ui.entries.size)} / ${formatCountExact(ui.totalCount)} 条"
                                else "${formatCountExact(ui.totalCount)} 条 · 文件 ${formatBytes(ui.diskSizeBytes)}",
                        )
                    },
                    navigationIcon = { BackButton(onBack) },
                    actions = {
                        IconButton(
                            onClick = { if (detailed) confirmExport = true else export() },
                            shapes = IconButtonDefaults.shapes(),
                        ) { Icon(Icons.Outlined.IosShare, contentDescription = "导出") }
                        Box {
                            IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("详细日志") },
                                    leadingIcon = { Icon(Icons.Outlined.Visibility, contentDescription = null) },
                                    trailingIcon = { Switch(checked = detailed, onCheckedChange = null) },
                                    onClick = {
                                        menuOpen = false
                                        if (detailed) viewModel.setDetailed(false) else confirmDetailed = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("清空日志") },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        confirmClear = true
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                    scrollBehavior = scrollBehavior,
                )
                LogFilters(
                    ui,
                    fieldColor = lerp(colors.surfaceContainerHigh, colors.surfaceContainerHighest, collapsed),
                    onLevel = viewModel::setMinLevel,
                    onQuery = viewModel::setQuery,
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(visible = !follow && ui.entries.isNotEmpty(), enter = scaleIn(), exit = scaleOut()) {
                SmallExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(ui.entries.lastIndex)
                            follow = true
                        }
                    },
                    icon = { Icon(Icons.Outlined.KeyboardDoubleArrowDown, contentDescription = null) },
                    text = { Text("最新") },
                    modifier = Modifier.padding(WindowInsets.navigationBars.asPaddingValues()),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (ui.entries.isEmpty()) {
                EmptyState(
                    icon = if (ui.totalCount == 0) Icons.AutoMirrored.Outlined.Article else Icons.Outlined.SearchOff,
                    title = if (ui.totalCount == 0) "还没有日志" else "没有符合的日志",
                    message = if (ui.totalCount == 0) null else "换个级别或关键字试试",
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(ui.entries, key = { index, entry -> "${entry.timeMillis}-${entry.tid}-$index" }) { _, entry ->
                        LogEntryRow(entry, onCopy = {
                            copyToClipboard(context, AppLog.formatTime(entry.timeMillis) + " " + entry.level.char + " " + entry.tag + ": " + entry.message)
                            notify("已复制")
                        })
                    }
                }
            }
        }
    }

    if (confirmDetailed) {
        ConfirmDialog(
            icon = Icons.Outlined.Visibility,
            title = "开启详细日志？",
            text = "日志会原样记录 uid、QQ 数据库路径等信息，仅限开发调试时开启！",
            confirmLabel = "开启",
            onDismiss = { confirmDetailed = false },
            onConfirm = {
                confirmDetailed = false
                viewModel.setDetailed(true)
            },
        )
    }
    if (confirmExport) {
        ConfirmDialog(
            icon = Icons.Outlined.IosShare,
            title = "导出详细日志？",
            text = "详细日志导出的内容包含 uid、QQ 数据库路径等信息，请不要在公共场合发送！",
            confirmLabel = "导出",
            onDismiss = { confirmExport = false },
            onConfirm = {
                confirmExport = false
                export()
            },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            icon = Icons.Outlined.DeleteSweep,
            title = "清空日志？",
            text = "包括保存在手机上的日志文件，清空后不能恢复。",
            confirmLabel = "清空",
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                viewModel.clear()
                notify("已清空")
            },
        )
    }
}

/** 搜索框 + 级别（选中的级别及以上，连接式按钮组，一排放得下不用横滑） */
@Composable
private fun LogFilters(ui: LogUiState, fieldColor: Color, onLevel: (LogLevel) -> Unit, onQuery: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchField(value = ui.query, onValueChange = onQuery, placeholder = "搜索标签或内容", containerColor = fieldColor)
        ConnectedChoiceRow(
            options = LEVEL_FILTERS.map { it.second },
            selected = ui.minLevel,
            label = { level -> LEVEL_FILTERS.first { it.second == level }.first },
            onSelect = onLevel,
        )
    }
}

/**
 * 一条日志：左边是级别色块，上面一行是标签与时间，下面是正文（等宽字体，长的先收起，点开看全文）。
 * 错误与崩溃整条用错误色底，一眼能找到。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogEntryRow(entry: LogEntry, onCopy: () -> Unit) {
    var expanded by rememberSaveable(entry.timeMillis, entry.message.hashCode()) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val severe = entry.level == LogLevel.E || entry.level == LogLevel.A
    val (badge, onBadge) = levelColors(entry.level)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (severe) colors.errorContainer.copy(alpha = 0.45f) else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = { expanded = !expanded }, onLongClick = onCopy),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(badge),
                contentAlignment = Alignment.Center,
            ) {
                Text(entry.level.char.toString(), style = MaterialTheme.typography.labelMedium, color = onBadge)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.tag,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (severe) colors.error else colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(AppLog.formatTime(entry.timeMillis), style = MaterialTheme.typography.labelSmall, color = colors.outline)
                }
                Text(
                    entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (severe) colors.onErrorContainer else colors.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 收起时正文最多几行 */
private const val COLLAPSED_LINES = 4

@Composable
private fun levelColors(level: LogLevel): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (level) {
        LogLevel.E, LogLevel.A -> colors.error to colors.onError
        LogLevel.W -> colors.tertiaryContainer to colors.onTertiaryContainer
        LogLevel.I -> colors.primaryContainer to colors.onPrimaryContainer
        LogLevel.D -> colors.secondaryContainer to colors.onSecondaryContainer
        LogLevel.V -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
}

/** 级别筛选：选中即「这一级及以上」 */
private val LEVEL_FILTERS: List<Pair<String, LogLevel>> = listOf(
    "全部" to LogLevel.V,
    "调试" to LogLevel.D,
    "信息" to LogLevel.I,
    "警告" to LogLevel.W,
    "错误" to LogLevel.E,
)

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("QAlbum 日志", text))
}

private fun shareLogChooser(uri: Uri): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        "导出日志",
    )
