package com.xjw.qalbum.ui.sources

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.ui.cleanup.shareChooser
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.DeleteImagesDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.FloatingActionToolbar
import com.xjw.qalbum.ui.components.FullScreenImagePager
import com.xjw.qalbum.ui.components.LoadingState
import com.xjw.qalbum.ui.components.PhotoBadges
import com.xjw.qalbum.ui.components.PhotoGrid
import com.xjw.qalbum.ui.components.PreviewAction
import com.xjw.qalbum.ui.components.SaveToAlbumSheet
import com.xjw.qalbum.ui.components.SearchField
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedDefaults
import com.xjw.qalbum.ui.components.SelectionTopBar
import com.xjw.qalbum.ui.components.ToolbarIconAction
import com.xjw.qalbum.ui.components.ToolbarPrimaryAction
import com.xjw.qalbum.ui.components.WavyProgress
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private val SelectedIdsSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() })

/** 看图快照：打开时固定一份已加载列表，删除后从中移除并停在相邻图片 */
private data class SourcePreview(val images: List<ImageEntity>, val start: Int)

/**
 * 来源浏览页：
 * - 会话：顶部成员筛选条（群名片 / 昵称、图片数、占用空间），可展开全部成员并搜索；
 * - 发送人：按会话筛选；全部来源：不再细分；
 * - 排序：日期 / 大小 × 升序 / 降序，网格带分组标题；
 * - 长按进入多选（可拖动连续勾选），浮动工具栏收藏 / 保存 / 删除；
 * - 看图页可收藏、删除（移入回收站或永久删除）。
 */
@Composable
fun QqSourceBrowseScreen(
    target: SourceBrowseTarget,
    onBack: () -> Unit,
    onBrowse: (SourceBrowseTarget) -> Unit,
    viewModel: QqSourceBrowseViewModel = koinViewModel(key = "source-${target.kind.arg}-${target.key}") { parametersOf(target) },
) {
    val context = LocalContext.current
    val items = viewModel.photos.collectAsLazyPagingItems()
    val info by viewModel.info.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val totalStats by viewModel.totalStats.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val filterOptions by viewModel.filterOptions.collectAsState()
    val confirmation by viewModel.confirmation.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val message by viewModel.message.collectAsState()
    val repositoryMessage by viewModel.repositoryMessage.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    var selectedIds by rememberSaveable(stateSaver = SelectedIdsSaver) { mutableStateOf(setOf<Long>()) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<SourcePreview?>(null) }
    val selectionMode = selectedIds.isNotEmpty()
    val clearSelection = { selectedIds = emptySet() }

    BackHandler(enabled = selectionMode) { clearSelection() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        snackbar.showSnackbar(text)
    }
    LaunchedEffect(repositoryMessage) {
        val text = repositoryMessage ?: return@LaunchedEffect
        viewModel.acknowledgeRepositoryMessage()
        snackbar.showSnackbar(text)
    }

    val filterNoun = when {
        target.kind == SourceKind.SENDER -> "会话"
        info?.chatType == 1 -> "发送人"
        info?.chatType == 3 -> "转发者"
        else -> "成员"
    }
    val title = info?.name?.ifBlank { null } ?: when (target.kind) {
        SourceKind.SENDER -> "发送人名称未缓存"
        SourceKind.CONVERSATION -> if (info?.chatType == 1) "会话名称未缓存" else "群名称未缓存"
        SourceKind.ALL -> "全部来源图片"
    }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val topBarEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val topBarExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn(topBarEnter) togetherWith fadeOut(topBarExit) },
                label = "sourceTopBar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(
                        count = selectedIds.size,
                        onClose = clearSelection,
                        actions = {
                            IconButton(
                                onClick = { scope.launch { selectedIds = viewModel.allIds() } },
                                shapes = IconButtonDefaults.shapes(),
                            ) { Icon(Icons.Outlined.SelectAll, contentDescription = "全选") }
                        },
                    )
                } else {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        subtitle = {
                            Text(
                                buildString {
                                    append(statsText(stats?.count, stats?.bytes))
                                    append(" · ")
                                    append(sort.label)
                                },
                                maxLines = 1,
                            )
                        },
                        navigationIcon = { BackButton(onBack) },
                        actions = {
                            if (target.kind != SourceKind.ALL) {
                                IconButton(onClick = { showFilterSheet = true }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Outlined.FilterList, contentDescription = "按${filterNoun}筛选")
                                }
                            }
                            SourceSortMenuButton(sort = sort, onSort = viewModel::setSort)
                            if (viewModel.canTrashScope) {
                                Box {
                                    IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text(if (filter == null) "清理这里的全部图片…" else "清理当前筛选的图片…") },
                                            leadingIcon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
                                            enabled = progress == null,
                                            onClick = { menuOpen = false; viewModel.requestScopeTrash() },
                                        )
                                    }
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = progress != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    val running = progress
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(running?.label.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        WavyProgress(
                            fraction = running?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total },
                        )
                    }
                }
                val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                when {
                    items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> LoadingState()
                    items.loadState.refresh is LoadState.Error && items.itemCount == 0 -> EmptyState(
                        icon = Icons.Outlined.ImageNotSupported,
                        title = "图片加载失败",
                        action = { TextButton(onClick = { items.retry() }) { Text("重试") } },
                    )
                    else -> PhotoGrid(
                        items = items,
                        columns = columns,
                        selectedIds = selectedIds,
                        gridState = gridState,
                        onOpen = { image ->
                            val loaded = items.itemSnapshotList.items
                                .filterIsInstance<GalleryTimelineItem.Photo>()
                                .map { it.image }
                            val index = loaded.indexOfFirst { it.id == image.id }
                            if (index >= 0) preview = SourcePreview(loaded, index)
                        },
                        onSelectionChange = { id, selected ->
                            selectedIds = if (selected) selectedIds + id else selectedIds - id
                        },
                        onColumnsChange = viewModel::setColumns,
                        badges = PhotoBadges(size = !sort.byDate),
                        contentPadding = PaddingValues(bottom = bottomInset + if (selectionMode) 88.dp else 0.dp),
                        leadingContent = {
                            if (target.kind != SourceKind.ALL) {
                                item(key = "filters", span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
                                    FilterChipsRow(
                                        noun = filterNoun,
                                        kind = target.kind,
                                        options = filterOptions,
                                        selected = filter,
                                        totalCount = totalStats?.count,
                                        onSelect = viewModel::selectFilter,
                                        onShowAll = { showFilterSheet = true },
                                    )
                                }
                            }
                            if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
                                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                                        EmptyState(
                                            icon = Icons.Outlined.ImageNotSupported,
                                            title = "没有图片",
                                            message = if (filter != null) "换一个${filterNoun}看看" else "这里的图片可能已被清理或尚未扫描",
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }

            FloatingActionToolbar(
                visible = selectionMode,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ToolbarIconAction(Icons.Outlined.FavoriteBorder, "收藏", onClick = {
                    val ids = selectedIds
                    viewModel.setFavorite(ids, true) { count ->
                        scope.launch { snackbar.showSnackbar(if (count > 0) "已收藏 $count 张" else "所选图片都已收藏") }
                        clearSelection()
                    }
                })
                ToolbarIconAction(Icons.Outlined.Download, "保存到相册", onClick = { showSave = true })
                ToolbarPrimaryAction(Icons.Outlined.Delete, "删除", onClick = { showDelete = true })
            }
        }
    }

    if (showDelete && selectedIds.isNotEmpty()) {
        val ids = selectedIds
        DeleteImagesDialog(
            count = ids.size,
            sizeOf = { viewModel.sizeOf(ids) },
            note = "收藏或标记为保留的图片会被跳过。删除 QQ 缓存后，聊天里对应的图片可能需要重新下载。",
            onDismiss = { showDelete = false },
            onTrash = {
                showDelete = false
                viewModel.moveToTrash(ids) { clearSelection(); items.refresh() }
            },
            onPermanent = {
                showDelete = false
                viewModel.deletePermanently(ids) { clearSelection(); items.refresh() }
            },
        )
    }

    if (showSave && selectedIds.isNotEmpty()) {
        SaveToAlbumSheet(
            imageCount = selectedIds.size,
            subdirs = exportSubdirs,
            onCreateSubdir = viewModel::createSubdir,
            onDismiss = { showSave = false },
            onConfirm = { subdir, alsoTrash ->
                val ids = selectedIds
                showSave = false
                viewModel.save(ids, subdir, alsoTrash) { clearSelection(); items.refresh() }
            },
        )
    }

    if (showFilterSheet) {
        FilterSheet(
            noun = filterNoun,
            kind = target.kind,
            options = filterOptions,
            selected = filter,
            search = viewModel.filterSearch.collectAsState().value,
            totalCount = totalStats?.count,
            onSearch = { viewModel.filterSearch.value = it },
            onSelect = {
                viewModel.selectFilter(it)
                showFilterSheet = false
            },
            onOpenSender = if (target.kind == SourceKind.CONVERSATION) {
                { facet -> showFilterSheet = false; onBrowse(SourceBrowseTarget(SourceKind.SENDER, facet.key)) }
            } else {
                { facet -> showFilterSheet = false; onBrowse(SourceBrowseTarget(SourceKind.CONVERSATION, facet.key)) }
            },
            onDismiss = { showFilterSheet = false },
        )
    }

    confirmation?.let { request ->
        ScopeTrashDialog(
            request = request,
            title = title,
            filterName = filter?.let { facetDisplayName(it, if (target.kind == SourceKind.SENDER) SourceKind.CONVERSATION else SourceKind.SENDER) },
            running = progress != null,
            onIncludeShared = viewModel::updateShared,
            onConfirm = viewModel::confirmScopeTrash,
            onDismiss = viewModel::dismissScopeTrash,
        )
    }

    preview?.let { current ->
        var confirmDeleteFor by remember { mutableStateOf<ImageEntity?>(null) }
        FullScreenImagePager(
            images = current.images,
            startIndex = current.start,
            onClose = { preview = null },
            onShare = { id -> viewModel.share(id) { context.startActivity(shareChooser(it)) } },
            exportSubdirs = exportSubdirs,
            onCreateSubdir = viewModel::createSubdir,
            onSave = { id, subdir, alsoTrash ->
                viewModel.save(listOf(id), subdir, alsoTrash) { saved ->
                    if (alsoTrash && saved > 0) {
                        preview = preview?.withoutImage(id)
                        items.refresh()
                    }
                }
            },
            onLoadOcr = viewModel::ocr,
            bottomBar = { image ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PreviewAction(
                        icon = if (image.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        label = if (image.isFavorite) "已收藏" else "收藏",
                        onClick = {
                            val next = !image.isFavorite
                            viewModel.setFavorite(listOf(image.id), next)
                            preview = preview?.let { state ->
                                state.copy(images = state.images.map { if (it.id == image.id) it.copy(isFavorite = next) else it })
                            }
                        },
                    )
                    PreviewAction(
                        icon = Icons.Outlined.Delete,
                        label = "删除",
                        onClick = { confirmDeleteFor = image },
                    )
                }
                confirmDeleteFor?.let { pending ->
                    DeleteImagesDialog(
                        count = 1,
                        note = if (pending.isFavorite || pending.userKeep == true) "这张图片已收藏或标记为保留，不会被删除；请先取消收藏。" else null,
                        onDismiss = { confirmDeleteFor = null },
                        onTrash = {
                            confirmDeleteFor = null
                            viewModel.moveToTrash(listOf(pending.id), notify = false) { moved ->
                                Toast.makeText(context, removalMessage("已移入回收站", moved, 1), Toast.LENGTH_SHORT).show()
                                if (moved > 0) {
                                    preview = preview?.withoutImage(pending.id)
                                    items.refresh()
                                }
                            }
                        },
                        onPermanent = {
                            confirmDeleteFor = null
                            viewModel.deletePermanently(listOf(pending.id), notify = false) { deleted ->
                                Toast.makeText(context, removalMessage("已永久删除", deleted, 1), Toast.LENGTH_SHORT).show()
                                if (deleted > 0) {
                                    preview = preview?.withoutImage(pending.id)
                                    items.refresh()
                                }
                            }
                        },
                    )
                }
            },
        )
    }
}

/** 从看图快照移除一张：保持当前位置（删的是末张则后退一张），删空后关闭 */
private fun SourcePreview.withoutImage(id: Long): SourcePreview? {
    val index = images.indexOfFirst { it.id == id }
    if (index < 0) return this
    val remaining = images.filterNot { it.id == id }
    if (remaining.isEmpty()) return null
    return SourcePreview(remaining, index.coerceAtMost(remaining.lastIndex))
}

/** 网格顶部的筛选条：「全部」+ 占用最多的若干个，末尾「全部成员」打开完整列表 */
@Composable
private fun FilterChipsRow(
    noun: String,
    kind: SourceKind,
    options: List<QqSourceFacet>?,
    selected: QqSourceFacet?,
    totalCount: Int?,
    onSelect: (QqSourceFacet?) -> Unit,
    onShowAll: () -> Unit,
) {
    val optionKind = if (kind == SourceKind.SENDER) SourceKind.CONVERSATION else SourceKind.SENDER
    val top = remember(options, selected) {
        val list = options.orEmpty().take(12)
        if (selected != null && list.none { it.key == selected.key }) listOf(selected) + list else list
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(if (totalCount != null) "全部 · ${formatCountExact(totalCount)}" else "全部") },
        )
        top.forEach { option ->
            val isSelected = option.key == selected?.key
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else option) },
                label = {
                    Text(
                        "${facetDisplayName(option, optionKind)} · ${formatBytes(option.bytes)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    SourceAvatar(
                        facetDisplayName(option, optionKind),
                        option.key,
                        group = optionKind == SourceKind.CONVERSATION && option.chatType == 2,
                        size = FilterChipDefaults.IconSize,
                    )
                },
                trailingIcon = if (isSelected) {
                    { Icon(Icons.Outlined.Close, contentDescription = "取消筛选", modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
        if ((options?.size ?: 0) > 0) {
            TextButton(onClick = onShowAll) {
                Text("全部${noun}")
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

/** 完整的二级筛选列表：可搜索；每项显示图片数与占用空间，并可直接跳到该成员 / 会话的全部图片 */
@Composable
private fun FilterSheet(
    noun: String,
    kind: SourceKind,
    options: List<QqSourceFacet>?,
    selected: QqSourceFacet?,
    search: String,
    totalCount: Int?,
    onSearch: (String) -> Unit,
    onSelect: (QqSourceFacet?) -> Unit,
    onOpenSender: (QqSourceFacet) -> Unit,
    onDismiss: () -> Unit,
) {
    val optionKind = if (kind == SourceKind.SENDER) SourceKind.CONVERSATION else SourceKind.SENDER
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("按${noun}筛选", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
            Text(
                if (kind == SourceKind.SENDER) "只看此人在某个会话里发的图片" else "只看某位${noun}发的图片，占用空间按去重后的图片计算",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            SearchField(
                value = search,
                onValueChange = onSearch,
                placeholder = if (kind == SourceKind.SENDER) "搜索会话" else "搜索群名片或昵称",
            )
            Spacer(Modifier.height(12.dp))
        }
        val list = options
        if (list == null) {
            Box(Modifier.fillMaxWidth().height(200.dp)) { LoadingState() }
        } else {
            val maxBytes = list.maxOfOrNull { it.bytes } ?: 0L
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(SegmentedDefaults.Gap),
            ) {
                item(key = "all") {
                    com.xjw.qalbum.ui.components.SegmentedListItem(
                        position = SegmentPosition(0, list.size + 1),
                        selected = selected == null,
                        onClick = { onSelect(null) },
                        headline = { Text("全部${noun}") },
                        supporting = { Text(if (totalCount != null) "${formatCountExact(totalCount)} 张" else "…") },
                    )
                }
                itemsIndexed(list, key = { _, facet -> facet.key }) { index, facet ->
                    SourceFacetRow(
                        facet = facet,
                        kind = optionKind,
                        position = SegmentPosition(index + 1, list.size + 1),
                        maxBytes = maxBytes,
                        showAccount = false,
                        selected = facet.key == selected?.key,
                        onClick = { onSelect(facet) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (list.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "没有匹配的$noun",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                selected?.let { current ->
                    item(key = "jump") {
                        TextButton(onClick = { onOpenSender(current) }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                if (kind == SourceKind.SENDER) "打开「${facetDisplayName(current, optionKind)}」的全部图片"
                                else "查看「${facetDisplayName(current, optionKind)}」在所有会话里的图片",
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 按范围整体移入回收站的确认（含共用、受保护统计） */
@Composable
private fun ScopeTrashDialog(
    request: QqTrashConfirmation,
    title: String,
    filterName: String?,
    running: Boolean,
    onIncludeShared: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val counts = request.counts
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
        title = { Text("清理这些图片？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (filterName != null) "「$title」中「$filterName」的图片" else "「$title」中的全部图片",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (counts == null) {
                    WavyProgress(fraction = null)
                } else {
                    InfoLine("共 ${formatCountExact(counts.total)} 张 · ${formatBytes(counts.totalBytes)}")
                    InfoLine("其中 ${formatCountExact(counts.shared)} 张也出现在其他会话或发送人中")
                    InfoLine("${formatCountExact(counts.protectedCount)} 张已收藏或保留，将跳过")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = request.includeShared, onCheckedChange = onIncludeShared)
                    Text("也清理与其他会话共用的图片", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "图片会先移入回收站，可在保留期内恢复。删除 QQ 缓存后，聊天里对应的图片可能需要重新下载，过期的将无法再查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = counts != null && counts.removable > 0 && !running) {
                Text(
                    if (counts == null) "统计中…" else "移入回收站 ${formatCountExact(counts.removable)} 张（${formatBytes(counts.removableBytes)}）",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
