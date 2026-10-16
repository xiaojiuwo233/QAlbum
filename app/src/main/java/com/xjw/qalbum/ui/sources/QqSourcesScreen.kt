package com.xjw.qalbum.ui.sources

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.db.dao.QqSourceSummary
import com.xjw.qalbum.data.db.entity.QqAutoArchiveEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import com.xjw.qalbum.domain.model.SourceFacetOrder
import com.xjw.qalbum.qq.QqSourceProgress
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.common.formatDateTime
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.SearchField
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedDefaults
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.WavyProgress
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * QQ 来源首页：读取聊天记录的只读副本后，按群聊 / 私聊 / 发送人列出图片分类，
 * 每类显示图片数、最近时间与占用空间（附占比条）。点分类进入浏览页再细分筛选与清理。
 */
@Composable
fun QqSourcesScreen(
    onBack: () -> Unit,
    onBrowse: (SourceBrowseTarget) -> Unit,
    viewModel: QqSourcesViewModel = koinViewModel(),
) {
    val tab by viewModel.tab.collectAsState()
    val search by viewModel.search.collectAsState()
    val order by viewModel.order.collectAsState()
    val facets by viewModel.facets.collectAsState()
    val tabCounts by viewModel.tabCounts.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val message by viewModel.message.collectAsState()
    val report by viewModel.report.collectAsState()
    val sourcesComplete by viewModel.sourcesComplete.collectAsState(initial = true)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var showAutoArchive by remember { mutableStateOf(false) }
    val autoArchive by viewModel.autoArchive.collectAsState()
    val autoRefresh by viewModel.autoRefresh.collectAsState()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        viewModel.acknowledgeMessage()
        // 在页面作用域里显示：清空消息会重启本副作用，直接 show 会被立刻取消
        scope.launch { snackbar.showSnackbar(text) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("聊天来源") },
                subtitle = { Text("按群聊、私聊和发送人整理 QQ 图片") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = progress == null,
                        shapes = IconButtonDefaults.shapes(),
                    ) { Icon(Icons.Outlined.Refresh, contentDescription = "重新读取来源") }
                    Box {
                        IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("完整读取") },
                                enabled = progress == null,
                                onClick = { menuOpen = false; viewModel.refreshFull() },
                            )
                            DropdownMenuItem(
                                text = { Text("每天自动读取新消息") },
                                trailingIcon = { Checkbox(checked = autoRefresh, onCheckedChange = null) },
                                onClick = { menuOpen = false; viewModel.setAutoRefresh(!autoRefresh) },
                            )
                            DropdownMenuItem(
                                text = { Text("自动归档名单" + if (autoArchive.isNotEmpty()) "（${autoArchive.size}）" else "") },
                                onClick = { menuOpen = false; showAutoArchive = true },
                            )
                            DropdownMenuItem(
                                text = { Text("清除来源索引") },
                                enabled = progress == null && !accounts.isNullOrEmpty(),
                                onClick = { menuOpen = false; clearConfirm = true },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = bottomInset + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(SegmentedDefaults.Gap),
        ) {
            item(key = "status", contentType = "status") {
                StatusCard(
                    progress = progress,
                    summary = summary,
                    accounts = accounts,
                    report = report,
                    complete = sourcesComplete,
                    onRefresh = viewModel::refresh,
                    onCancel = viewModel::cancel,
                    onBrowseAll = { onBrowse(SourceBrowseTarget.All) },
                    modifier = Modifier.animateItem().padding(bottom = 12.dp),
                )
            }
            if (!accounts.isNullOrEmpty()) {
                item(key = "controls", contentType = "controls") {
                    FacetControls(
                        tab = tab,
                        counts = tabCounts,
                        search = search,
                        order = order,
                        onTab = viewModel::selectTab,
                        onSearch = { viewModel.search.value = it },
                        onOrder = viewModel::setOrder,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                val list = facets
                when {
                    list == null -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.LoadingIndicator()
                        }
                    }
                    list.isEmpty() -> item(key = "empty-${tab.name}") {
                        EmptyFacets(
                            searching = search.isNotBlank(),
                            tab = tab,
                            organizing = summary?.dirty != false,
                            modifier = Modifier.animateItem(),
                        )
                    }
                    else -> {
                        val maxBytes = list.maxOf { it.totalBytes }
                        val kind = if (tab == SourceTab.SENDERS) SourceKind.SENDER else SourceKind.CONVERSATION
                        itemsIndexed(list, key = { _, facet -> "${tab.name}-${facet.key}" }) { index, facet ->
                            SourceFacetRow(
                                facet = facet,
                                kind = kind,
                                position = SegmentPosition(index, list.size),
                                maxBytes = maxBytes,
                                showAccount = (accounts?.size ?: 0) > 1,
                                onClick = { onBrowse(SourceBrowseTarget(kind, facet.key)) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAutoArchive) {
        AutoArchiveDialog(entries = autoArchive, onRemove = viewModel::removeAutoArchive, onDismiss = { showAutoArchive = false })
    }

    if (clearConfirm) {
        ConfirmDialog(
            title = "清除来源索引？",
            text = "只清除 QAlbum 里的来源关联，不动图片和 QQ 数据，可随时重新读取。",
            confirmLabel = "清除",
            destructive = true,
            onConfirm = { clearConfirm = false; viewModel.clearIndex() },
            onDismiss = { clearConfirm = false },
        )
    }
}

/** 自动归档名单：这些群 / 人以后的新图在读取来源和扫描后自动归档；移出名单不影响已经归档的图 */
@Composable
private fun AutoArchiveDialog(entries: List<QqAutoArchiveEntity>, onRemove: (QqAutoArchiveEntity) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
        title = { Text("自动归档名单") },
        text = {
            if (entries.isEmpty()) {
                Text("还没有。在群或人的页面里「归档」时勾选「以后的新图也自动归档」。")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(entries, key = { it.conversationKey + "\u0000" + it.senderKey }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.label.ifBlank { "已清除的来源" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemove(entry) }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.Close, contentDescription = "移出名单")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun StatusCard(
    progress: QqSourceProgress?,
    summary: QqSourceSummary?,
    accounts: List<QqSourceAccountEntity>?,
    report: String?,
    complete: Boolean,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onBrowseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = when {
        progress != null -> 0
        accounts == null -> 3
        accounts.isEmpty() -> 1
        else -> 2
    }
    val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = { fadeIn(enterSpec) togetherWith fadeOut(exitSpec) },
        label = "sourceStatus",
    ) { current ->
        when (current) {
            0 -> progress?.let { RunningCard(it, onCancel) } ?: Spacer(Modifier.height(1.dp))
            1 -> IntroCard(onRefresh)
            3 -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.LoadingIndicator()
            }
            else -> SummaryCard(summary, accounts.orEmpty(), report, complete, onBrowseAll)
        }
    }
}

@Composable
private fun IntroCard(onRefresh: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShapeIcon(
                Icons.Outlined.Forum,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                size = 64.dp,
                iconSize = 30.dp,
            )
            Text("还没有读取聊天来源", style = MaterialTheme.typography.titleLargeEmphasized)
            Text(
                "用 Root 读取 QQ 数据库的副本，把图片关联到群聊、私聊和发送人。不会改动 QQ 的任何数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRefresh, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("读取聊天来源")
            }
        }
    }
}

@Composable
private fun RunningCard(progress: QqSourceProgress, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    progress.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (progress.cancellable) TextButton(onClick = onCancel) { Text("停止") }
            }
            WavyProgress(
                fraction = if (progress.total > 0) progress.done.toFloat() / progress.total else null,
            )
            Text(
                if (progress.label == "整理来源统计") "读取新来源后要整理一次，之后统计自动更新。"
                else "可离开本页，后台继续。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun SummaryCard(
    summary: QqSourceSummary?,
    accounts: List<QqSourceAccountEntity>,
    report: String?,
    complete: Boolean,
    onBrowseAll: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("聊天来源占用", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    summary?.let { formatBytes(it.imageBytes + it.thumbBytes) } ?: "…",
                    style = MaterialTheme.typography.displaySmallEmphasized,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    summary?.let { "${formatCountExact(it.imageCount)} 张" } ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            summary?.takeIf { it.thumbCount > 0 }?.let {
                Text(
                    "图片 ${formatBytes(it.imageBytes)} · QQ 缩略图 ${formatCountExact(it.thumbCount)} 张 ${formatBytes(it.thumbBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val updated = accounts.maxOfOrNull { it.importedAt } ?: 0L
            Text(
                "${accounts.size} 个账号 · 更新于 ${formatDateTime(updated)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            AnimatedVisibility(
                visible = summary?.dirty == true,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Text(
                    "统计待整理，数字可能不是最新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            if (report != null) {
                Text(report, style = MaterialTheme.typography.bodySmall)
            }
            if (!complete) {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "部分来源未能读取，筛选和清理只覆盖已关联的图片。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(onClick = onBrowseAll, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("浏览全部已关联图片")
            }
        }
    }
}

/** 页签（连接式按钮组）+ 搜索 + 排序 */
@Composable
private fun FacetControls(
    tab: SourceTab,
    counts: Map<SourceTab, Int>,
    search: String,
    order: SourceFacetOrder,
    onTab: (SourceTab) -> Unit,
    onSearch: (String) -> Unit,
    onOrder: (SourceFacetOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            val tabs = SourceTab.entries
            tabs.forEachIndexed { index, option ->
                ToggleButton(
                    checked = tab == option,
                    onCheckedChange = { onTab(option) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        tabs.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    colors = ToggleButtonDefaults.toggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { role = Role.RadioButton },
                ) {
                    if (tab == option) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    val count = counts[option]
                    Text(if (count != null && count > 0) "${option.label} $count" else option.label, maxLines = 1)
                }
            }
        }
        SearchField(
            value = search,
            onValueChange = onSearch,
            placeholder = when (tab) {
                SourceTab.GROUPS -> "搜索群聊"
                SourceTab.PRIVATE -> "搜索私聊"
                SourceTab.SENDERS -> "搜索昵称或群名片"
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (tab) {
                    SourceTab.GROUPS -> "群聊"
                    SourceTab.PRIVATE -> "私聊"
                    SourceTab.SENDERS -> "发送人"
                },
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
            OrderMenu(order, onOrder)
        }
    }
}

@Composable
private fun OrderMenu(order: SourceFacetOrder, onOrder: (SourceFacetOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("按${order.label}")
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SourceFacetOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = if (option == order) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = { open = false; onOrder(option) },
                )
            }
        }
    }
}

@Composable
private fun EmptyFacets(searching: Boolean, tab: SourceTab, organizing: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            when {
                searching -> "没有匹配的${tab.label}"
                organizing -> "来源统计整理中，完成后显示"
                else -> "没有已关联图片的${tab.label}"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!searching && !organizing) {
            Text(
                "QQ 有新消息后，点右上角的刷新重新读取",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
