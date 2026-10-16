package com.xjw.qalbum.ui.cleanup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.service.cleanup.rules.RuleAction
import com.xjw.qalbum.service.cleanup.rules.RuleScope
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.FloatingActionToolbar
import com.xjw.qalbum.ui.components.FullScreenImagePager
import com.xjw.qalbum.ui.components.LoadingState
import com.xjw.qalbum.ui.components.PhotoBadges
import com.xjw.qalbum.ui.components.PhotoGrid
import com.xjw.qalbum.ui.components.PreviewAction
import com.xjw.qalbum.ui.components.SelectionTopBar
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.ToolbarIconAction
import com.xjw.qalbum.ui.components.ToolbarPrimaryAction
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.sources.SourceSortMenuButton
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private val SelectedSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() })

/**
 * 规则预览：命中的图片按日期或大小分组（排序同聊天来源页）。进来时什么都不选：长按（可拖动连选）或点分组标题选择，右上角全选；
 * 底部对选中的图归档、压缩或移入回收站，后台执行、通知栏显示进度。点开单张也能直接处理。
 */
@Composable
fun RulePreviewScreen(
    ruleId: String,
    onBack: () -> Unit,
    viewModel: RulePreviewViewModel = koinViewModel(key = "rule-$ruleId") { parametersOf(ruleId) },
) {
    val rule = viewModel.rule
    val items = viewModel.photos.collectAsLazyPagingItems()
    val option by viewModel.option.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val directory by viewModel.directory.collectAsState()
    val fastScroll by viewModel.fastScroll.collectAsState()
    val running by viewModel.running.collectAsState()
    val message by viewModel.message.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var selected by rememberSaveable(rule.id, stateSaver = SelectedSaver) { mutableStateOf(setOf<Long>()) }
    var confirmTrash by rememberSaveable { mutableStateOf(false) }
    var pendingTrash by remember { mutableStateOf<ImageEntity?>(null) }
    var preview by remember { mutableStateOf<Pair<List<ImageEntity>, Int>?>(null) }
    val selecting = selected.isNotEmpty()
    val count = stats?.count ?: 0
    val compressFirst = rule.action == RuleAction.COMPRESS
    val clearSelection = { selected = emptySet() }
    val selectAll: () -> Unit = { scope.launch { selected = viewModel.allIds() } }

    BackHandler(enabled = selecting) { clearSelection() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        scope.launch { snackbar.showSnackbar(text) }
    }
    // 换排序后回到顶部（旋转屏幕不算）
    var shownSort by rememberSaveable { mutableStateOf(sort.name) }
    LaunchedEffect(sort) {
        if (sort.name != shownSort) {
            shownSort = sort.name
            gridState.scrollToItem(0)
        }
    }
    // 看图时压缩了：快照里换成新的大小与压缩状态
    LaunchedEffect(viewModel) {
        viewModel.compressedChanges.collect { ids ->
            val current = preview ?: return@collect
            val refreshed = current.first.map { image -> if (image.id in ids) viewModel.entity(image.id) ?: image else image }
            preview = preview?.let { refreshed to it.second }
        }
    }

    val topEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val topExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    // 顶栏固定为小顶栏：进入多选时高度不变，网格不跳，长按后拖动连选的位置才对得上
    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(topEnter) togetherWith fadeOut(topExit) },
                label = "ruleTopBar",
            ) { selectingNow ->
                if (selectingNow) {
                    val all = count > 0 && selected.size >= count
                    SelectionTopBar(
                        count = selected.size,
                        subtitle = "共 ${formatCountExact(count)} 张",
                        onClose = clearSelection,
                        actions = {
                            IconButton(onClick = if (all) clearSelection else selectAll, shapes = IconButtonDefaults.shapes()) {
                                Icon(if (all) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, contentDescription = if (all) "取消全选" else "全选")
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = { Text(rule.title) },
                        subtitle = {
                            Text(
                                stats?.let { s ->
                                    if (s.count == 0) "没有可处理的图片"
                                    else "${formatCountExact(s.count)} 张 · ${formatBytes(s.bytes)}"
                                } ?: "统计中…",
                            )
                        },
                        navigationIcon = { BackButton(onBack) },
                        actions = {
                            SourceSortMenuButton(sort = sort, onSort = viewModel::setSort)
                            IconButton(onClick = selectAll, enabled = count > 0, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.SelectAll, contentDescription = "全选")
                            }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            when {
                items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> LoadingState()
                else -> PhotoGrid(
                    items = items,
                    columns = columns,
                    selectedIds = selected,
                    gridState = gridState,
                    badges = PhotoBadges(size = !sort.byDate, stack = rule.scope == RuleScope.PICTURE),
                    onOpen = { image ->
                        val loaded = items.itemSnapshotList.items.filterIsInstance<GalleryTimelineItem.Photo>().map { it.image }
                        val index = loaded.indexOfFirst { it.id == image.id }
                        if (index >= 0) preview = loaded to index
                    },
                    onSelectedIdsChange = { selected = it },
                    onColumnsChange = viewModel::setColumns,
                    sectionIds = viewModel::sectionIds,
                    directory = directory,
                    fastScroll = fastScroll,
                    contentPadding = PaddingValues(bottom = bottomInset + 96.dp),
                    leadingContent = {
                        item(key = "explain", span = { GridItemSpan(maxLineSpan) }, contentType = "explain") {
                            RuleHeader(
                                description = rule.description,
                                options = rule.options.map { it.value to it.label },
                                selected = option,
                                onSelect = { value -> clearSelection(); viewModel.setOption(value) },
                                showHint = count > 0 && !selecting,
                                running = running,
                                onCancel = viewModel::cancel,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                            )
                        }
                        if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
                            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().height(320.dp)) {
                                    EmptyState(icon = Icons.Outlined.CheckCircle, title = "没有可处理的图片", message = "换个条件试试")
                                }
                            }
                        }
                    },
                )
            }

            FloatingActionToolbar(visible = selecting, modifier = Modifier.align(Alignment.BottomCenter)) {
                ToolbarIconAction(Icons.Outlined.Archive, "归档", onClick = { viewModel.archive(selected, onDone = clearSelection) })
                if (compressFirst) {
                    ToolbarIconAction(Icons.Outlined.Delete, "移入回收站", onClick = { confirmTrash = true })
                    ToolbarPrimaryAction(Icons.Outlined.Compress, "压缩 ${formatCountExact(selected.size)} 张", onClick = {
                        viewModel.compress(selected)
                        clearSelection()
                    })
                } else {
                    if (rule.compressible) {
                        ToolbarIconAction(Icons.Outlined.Compress, "压缩", onClick = {
                            viewModel.compress(selected)
                            clearSelection()
                        })
                    }
                    ToolbarPrimaryAction(Icons.Outlined.Delete, "移入回收站 ${formatCountExact(selected.size)} 张", onClick = { confirmTrash = true })
                }
            }
        }
    }

    if (confirmTrash && selected.isNotEmpty()) {
        TrashConfirm(
            count = selected.size,
            note = rule.trashNote,
            onDismiss = { confirmTrash = false },
            onConfirm = {
                confirmTrash = false
                viewModel.trash(selected)
                clearSelection()
            },
        )
    }

    preview?.let { (list, start) ->
        FullScreenImagePager(
            images = list,
            startIndex = start,
            onClose = { preview = null },
            onShare = { id -> viewModel.share(id) { context.startActivity(shareChooser(it)) } },
            exportSubdirs = exportSubdirs,
            onCreateSubdir = viewModel::createSubdir,
            onSave = { id, subdir, _ -> viewModel.save(id, subdir) },
            bottomBar = { image ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PreviewAction(
                        icon = Icons.Outlined.Archive,
                        label = "归档",
                        onClick = {
                            viewModel.archive(listOf(image.id))
                            preview = preview?.without(image.id)
                        },
                    )
                    if (rule.compressible) {
                        PreviewAction(icon = Icons.Outlined.Compress, label = "压缩", onClick = { viewModel.compress(listOf(image.id)) })
                    }
                    PreviewAction(icon = Icons.Outlined.Delete, label = "移入回收站", onClick = { pendingTrash = image })
                }
                pendingTrash?.let { target ->
                    TrashConfirm(
                        count = 1,
                        note = rule.trashNote,
                        onDismiss = { pendingTrash = null },
                        onConfirm = {
                            pendingTrash = null
                            viewModel.trash(listOf(target.id))
                            preview = preview?.without(target.id)
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun TrashConfirm(count: Int, note: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        icon = Icons.Outlined.Delete,
        title = if (count == 1) "移入回收站？" else "移入回收站 ${formatCountExact(count)} 张？",
        text = "${note}到期前可在回收站恢复。",
        confirmLabel = "移入回收站",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** 规则说明（可展开）+ 参数选择 + 操作提示 + 本规则执行进度 */
@Composable
private fun RuleHeader(
    description: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    showHint: Boolean,
    running: com.xjw.qalbum.service.cleanup.rules.CleanupRunner.Running?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "expand")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            onClick = { expanded = !expanded },
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                ShapeIcon(
                    Icons.Outlined.CheckCircle,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    shape = MaterialShapes.Cookie7Sided.toShape(),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
        }
        if (options.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { (value, label) ->
                    FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
                }
            }
        }
        if (showHint) {
            Text(
                "长按选择，可拖动连选；点分组标题选整组。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        AnimatedVisibility(
            visible = running != null,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(),
        ) {
            running?.let { RunningCard(it, onCancel) }
        }
    }
}

/** 从看图快照移除一张并停在下一张（删的是末张则后退），删空后关闭 */
private fun Pair<List<ImageEntity>, Int>.without(id: Long): Pair<List<ImageEntity>, Int>? {
    val index = first.indexOfFirst { it.id == id }
    if (index < 0) return this
    val remaining = first.filterNot { it.id == id }
    if (remaining.isEmpty()) return null
    return remaining to index.coerceAtMost(remaining.lastIndex)
}
