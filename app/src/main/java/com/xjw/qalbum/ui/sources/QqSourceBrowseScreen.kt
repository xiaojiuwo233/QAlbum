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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
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
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.xjw.qalbum.data.db.dao.QqArchiveCounts
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import com.xjw.qalbum.ui.cleanup.PurgeProgressDialog
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
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.SelectionTopBar
import com.xjw.qalbum.ui.components.ToolbarIconAction
import com.xjw.qalbum.ui.components.ToolbarPrimaryAction
import com.xjw.qalbum.ui.components.WavyProgress
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.gallery.archivedText
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private val SelectedIdsSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() })

/** 看图快照：打开时固定一份已加载列表，删除后从中移除并停在相邻图片 */
private data class SourcePreview(val images: List<ImageEntity>, val start: Int)

/**
 * 来源浏览页：
 * - 内容切换：图片 / QQ 缩略图，各自显示数量与占用；
 * - 会话：顶部成员筛选条（群名片 / 昵称、数量、占用空间），可展开全部成员并搜索；
 * - 发送人：按会话筛选；全部来源：不再细分；
 * - 排序：日期 / 大小 × 升序 / 降序，网格带分组标题；
 * - 长按进入多选（可拖动连续勾选），浮动工具栏归档 / 保存 / 删除；右上角菜单可把这里（或当前筛选）的图片整体归档或清理；
 * - 看图页可归档、删除，上滑查看详情。
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
    val lookup by viewModel.lookup.collectAsState()
    val base by viewModel.baseFacet.collectAsState()
    val content by viewModel.content.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val current by viewModel.currentFacet.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val deleteScope by viewModel.scope.collectAsState()
    val filterOptions by viewModel.filterOptions.collectAsState()
    val confirmation by viewModel.confirmation.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val deleteProgress by viewModel.deleteProgress.collectAsState()
    val archiveRequest by viewModel.archiveRequest.collectAsState()
    val archiveVisible by viewModel.archiveVisible.collectAsState()
    val message by viewModel.message.collectAsState()
    val repositoryMessage by viewModel.repositoryMessage.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val stacked by viewModel.stacked.collectAsState()
    val directory by viewModel.directory.collectAsState()
    val fastScroll by viewModel.fastScroll.collectAsState()
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
    // 只删缩略图时默认不连带原图 / 预览图；删图片时默认整组处理
    val deleteDefaults = PictureDeleteOptions(linkedVersions = content != QqContent.THUMBNAILS)

    BackHandler(enabled = selectionMode) { clearSelection() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        // 在页面作用域里显示：清空消息会重启本副作用，直接 show 会被立刻取消
        scope.launch { snackbar.showSnackbar(text) }
    }
    LaunchedEffect(repositoryMessage) {
        val text = repositoryMessage ?: return@LaunchedEffect
        viewModel.acknowledgeRepositoryMessage()
        scope.launch { snackbar.showSnackbar(text) }
    }
    // 换内容后旧的选择不再对应当前网格
    LaunchedEffect(content) { clearSelection() }
    // 换筛选 / 内容 / 排序 / 合并方式后回到顶部（网格带占位、知道总数，不回顶会停在新列表的中间）；旋转屏幕不算
    val browseKey = "${filter?.id}/$content/$sort/$stacked"
    var shownKey by rememberSaveable { mutableStateOf(browseKey) }
    LaunchedEffect(browseKey) {
        if (browseKey != shownKey) {
            shownKey = browseKey
            gridState.scrollToItem(0)
        }
    }

    val filterNoun = when {
        target.kind == SourceKind.SENDER -> "会话"
        base?.chatType == 1 -> "发送人"
        base?.chatType == 3 -> "转发者"
        else -> "成员"
    }
    val title = base?.name?.ifBlank { null } ?: when (target.kind) {
        SourceKind.SENDER -> "发送人名称未缓存"
        SourceKind.CONVERSATION -> if (base?.chatType == 1) "会话名称未缓存" else "群名称未缓存"
        SourceKind.ALL -> "全部来源图片"
    }
    val noun = if (content == QqContent.THUMBNAILS) "缩略图" else "图片"
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
                                            text = { Text(if (filter == null) "归档这里的全部图片…" else "归档当前筛选的图片…") },
                                            leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                                            enabled = progress == null && deleteProgress == null && base != null,
                                            onClick = { menuOpen = false; viewModel.requestScopeArchive() },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (filter == null) "清理这里的全部图片…" else "清理当前筛选的图片…") },
                                            leadingIcon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
                                            enabled = progress == null && deleteProgress == null && base != null,
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
                val currentBase = base
                AnimatedVisibility(
                    visible = currentBase != null && (currentBase.thumbCount > 0 || content == QqContent.THUMBNAILS),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    if (currentBase != null) {
                        ContentToggle(
                            facet = current ?: currentBase,
                            selected = content,
                            enabled = !selectionMode,
                            onSelect = viewModel::setContent,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                }
                val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val found = lookup
                when {
                    found == null -> LoadingState()
                    found.facet == null && target.kind != SourceKind.ALL -> EmptyState(
                        icon = Icons.Outlined.HourglassTop,
                        title = if (progress != null) "来源统计整理中" else "没有找到这个来源",
                        message = if (progress != null) "整理完成后会自动显示" else "可能已被清除，请返回来源列表重新进入",
                    )
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
                        onSelectedIdsChange = { selectedIds = it },
                        onColumnsChange = viewModel::setColumns,
                        badges = PhotoBadges(size = !sort.byDate, stack = stacked && content == QqContent.IMAGES),
                        sectionIds = viewModel::sectionIds,
                        directory = directory,
                        fastScroll = fastScroll,
                        contentPadding = PaddingValues(bottom = bottomInset + if (selectionMode) 88.dp else 0.dp),
                        leadingContent = {
                            if (target.kind != SourceKind.ALL) {
                                item(key = "filters", span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
                                    FilterChipsRow(
                                        noun = filterNoun,
                                        kind = target.kind,
                                        content = content,
                                        options = filterOptions,
                                        selected = filter,
                                        totalCount = base?.countOf(content),
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
                                            title = "没有$noun",
                                            message = if (filter != null) "换一个${filterNoun}看看" else "这里的${noun}可能已被清理或尚未扫描",
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
                ToolbarIconAction(Icons.Outlined.Archive, "归档", onClick = {
                    val ids = selectedIds
                    viewModel.setArchived(ids, true) { visible ->
                        scope.launch { snackbar.showSnackbar(archivedText(ids.size, visible)) }
                        clearSelection()
                        items.refresh()
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
            imageIds = ids,
            sourceScope = deleteScope,
            initialOptions = deleteDefaults,
            noun = noun,
            onDismiss = { showDelete = false },
            onTrash = { options -> showDelete = false; viewModel.moveToTrash(ids, options = options) { clearSelection(); items.refresh() } },
            onPermanent = { options -> showDelete = false; viewModel.deletePermanently(ids, options = options) { clearSelection(); items.refresh() } },
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
            content = content,
            options = filterOptions,
            selected = filter,
            search = viewModel.filterSearch.collectAsState().value,
            totalCount = base?.countOf(content),
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

    archiveRequest?.let { request ->
        ScopeArchiveDialog(
            counts = request.counts,
            title = title,
            filterName = filter?.let { facetDisplayName(it, if (target.kind == SourceKind.SENDER) SourceKind.CONVERSATION else SourceKind.SENDER) },
            hidden = !archiveVisible,
            onConfirm = viewModel::confirmScopeArchive,
            onDismiss = viewModel::dismissScopeArchive,
        )
    }

    confirmation?.let { request ->
        ScopeTrashDialog(
            request = request,
            title = title,
            filterName = filter?.let { facetDisplayName(it, if (target.kind == SourceKind.SENDER) SourceKind.CONVERSATION else SourceKind.SENDER) },
            available = current,
            running = progress != null,
            onChange = viewModel::updateScopeTrash,
            onConfirm = viewModel::confirmScopeTrash,
            onDismiss = viewModel::dismissScopeTrash,
        )
    }

    preview?.let { current ->
        var confirmDeleteFor by remember { mutableStateOf<ImageEntity?>(null) }
        // 归档状态变了要重新取版本条（显示的可能是同一张图的其他版本）
        var revision by remember { mutableIntStateOf(0) }
        FullScreenImagePager(
            images = current.images,
            startIndex = current.start,
            variantsOf = viewModel::versionsOf,
            refreshKey = revision,
            onClose = { preview = null },
            onShare = { id -> viewModel.share(id) { context.startActivity(shareChooser(it)) } },
            exportSubdirs = exportSubdirs,
            onCreateSubdir = viewModel::createSubdir,
            onSave = { id, subdir, alsoTrash ->
                viewModel.save(listOf(id), subdir, alsoTrash) { saved ->
                    if (alsoTrash && saved > 0) {
                        preview = preview?.withoutPage(id)
                        items.refresh()
                    }
                }
            },
            onLoadOcr = viewModel::ocr,
            bottomBar = { image ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PreviewAction(
                        icon = if (image.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                        label = if (image.isArchived) "取消归档" else "归档",
                        onClick = {
                            val next = !image.isArchived
                            viewModel.setArchived(listOf(image.id), next) { visible ->
                                if (visible) {
                                    revision++
                                    preview = preview?.let { state ->
                                        state.copy(images = state.images.map { if (it.id == image.id) it.copy(isArchived = next) else it })
                                    }
                                } else {
                                    // 归档后不在这里显示：像删除那样移出预览
                                    Toast.makeText(context, archivedText(1, visible = false), Toast.LENGTH_SHORT).show()
                                    preview = preview?.withoutImage(image)
                                    items.refresh()
                                }
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
                    fun removed(count: Int, action: String) {
                        Toast.makeText(context, removalMessage(action, count, 1), Toast.LENGTH_SHORT).show()
                        if (count > 0) {
                            preview = preview?.withoutImage(pending)
                            items.refresh()
                        }
                    }
                    DeleteImagesDialog(
                        imageIds = listOf(pending.id),
                        sourceScope = deleteScope,
                        initialOptions = deleteDefaults,
                        noun = noun,
                        onDismiss = { confirmDeleteFor = null },
                        onTrash = { options ->
                            confirmDeleteFor = null
                            viewModel.moveToTrash(listOf(pending.id), notify = false, options = options) { removed(it, "已移入回收站") }
                        },
                        onPermanent = { options ->
                            confirmDeleteFor = null
                            viewModel.deletePermanently(listOf(pending.id), notify = false, options = options) { removed(it, "已永久删除") }
                        },
                    )
                }
            },
        )
    }

    deleteProgress?.let { PurgeProgressDialog(it) }
}

/** 从看图快照移除一张：保持当前位置，即显示下一张（删的是末张则后退一张），删空后关闭 */
private fun SourcePreview.withoutPage(id: Long): SourcePreview? = removeAt(images.indexOfFirst { it.id == id })

/** 删的可能是这一页显示的其他版本：按图片家族找到它所在的页 */
private fun SourcePreview.withoutImage(image: ImageEntity): SourcePreview? = removeAt(
    images.indexOfFirst { it.id == image.id || (image.variantGroup != null && it.variantGroup == image.variantGroup) },
)

private fun SourcePreview.removeAt(index: Int): SourcePreview? {
    if (index < 0) return this
    val remaining = images.filterIndexed { i, _ -> i != index }
    if (remaining.isEmpty()) return null
    return SourcePreview(remaining, index.coerceAtMost(remaining.lastIndex))
}

/** 图片 / 缩略图切换（连接式按钮组），各自带数量与占用 */
@Composable
private fun ContentToggle(
    facet: QqSourceFacet,
    selected: QqContent,
    enabled: Boolean,
    onSelect: (QqContent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        QqContent.entries.forEachIndexed { index, option ->
            val checked = option == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(option) },
                enabled = enabled,
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                colors = ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                if (checked) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                }
                Text(
                    "${if (option == QqContent.IMAGES) "图片" else "缩略图"} · ${formatBytes(facet.bytesOf(option))}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 网格顶部的筛选条：「全部」+ 占用最多的若干个，末尾「全部成员」打开完整列表 */
@Composable
private fun FilterChipsRow(
    noun: String,
    kind: SourceKind,
    content: QqContent,
    options: List<QqSourceFacet>?,
    selected: QqSourceFacet?,
    totalCount: Int?,
    onSelect: (QqSourceFacet?) -> Unit,
    onShowAll: () -> Unit,
) {
    val optionKind = if (kind == SourceKind.SENDER) SourceKind.CONVERSATION else SourceKind.SENDER
    val top = remember(options, selected) {
        val list = options.orEmpty().take(12)
        if (selected != null && list.none { it.id == selected.id }) listOf(selected) + list else list
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
            val isSelected = option.id == selected?.id
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else option) },
                label = {
                    Text(
                        "${facetDisplayName(option, optionKind)} · ${formatBytes(option.bytesOf(content))}",
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

/** 完整的二级筛选列表：可搜索；每项显示数量与占用空间，并可直接跳到该成员 / 会话的全部图片 */
@Composable
private fun FilterSheet(
    noun: String,
    kind: SourceKind,
    content: QqContent,
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
            val maxBytes = list.maxOfOrNull { it.bytesOf(content) } ?: 0L
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(SegmentedDefaults.Gap),
            ) {
                item(key = "all") {
                    SegmentedListItem(
                        position = SegmentPosition(0, list.size + 1),
                        selected = selected == null,
                        onClick = { onSelect(null) },
                        headline = { Text("全部${noun}") },
                        supporting = { Text(if (totalCount != null) "${formatCountExact(totalCount)} 张" else "…") },
                    )
                }
                itemsIndexed(list, key = { _, facet -> facet.id }) { index, facet ->
                    SourceFacetRow(
                        facet = facet,
                        kind = optionKind,
                        position = SegmentPosition(index + 1, list.size + 1),
                        maxBytes = maxBytes,
                        showAccount = false,
                        selected = facet.id == selected?.id,
                        content = content,
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

/**
 * 按范围整体移入回收站的确认：选择清理图片 / 缩略图，是否包含共用图片、是否连带同一张图的其他缓存版本。
 * 数字直接查库得出；逐个文件的核对放到后台执行时，不在弹窗里等待。
 */
/** 按范围整体归档的确认：共用的图片照样归档，同一张图的其他版本一起 */
@Composable
private fun ScopeArchiveDialog(
    counts: QqArchiveCounts?,
    title: String,
    filterName: String?,
    hidden: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
        title = { Text("归档这些图片？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (filterName != null) "「$title」中「$filterName」的全部图片" else "「$title」中的全部图片",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(4.dp))
                if (counts == null) {
                    WavyProgress(fraction = null)
                } else if (counts.images + counts.thumbs == 0) {
                    InfoLine("这里的图片都已归档")
                } else {
                    InfoLine(
                        "图片 ${formatCountExact(counts.images)} 张 · ${formatBytes(counts.imageBytes)}，" +
                            "QQ 缩略图 ${formatCountExact(counts.thumbs)} 张 · ${formatBytes(counts.thumbBytes)}",
                        MaterialTheme.colorScheme.primary,
                    )
                    if (counts.shared > 0) InfoLine("其中 ${formatCountExact(counts.shared)} 张其他聊天也在用，一并归档")
                }
                Text(
                    "同一张图的原图、预览图和缩略图一起归档，之后任何清理都不会删除它们" +
                        if (hidden) "；归档后只在「图库 → 归档」里显示。" else "。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = counts != null && counts.images + counts.thumbs > 0) {
                Text(if (counts == null) "统计中…" else "归档")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ScopeTrashDialog(
    request: QqTrashConfirmation,
    title: String,
    filterName: String?,
    available: QqSourceFacet?,
    running: Boolean,
    onChange: ((QqTrashConfirmation) -> QqTrashConfirmation) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val counts = request.counts
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
        title = { Text("清理这些图片？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (filterName != null) "「$title」中「$filterName」的内容" else "「$title」中的全部内容",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(4.dp))
                QqContent.entries.forEach { option ->
                    val checked = option in request.contents
                    OptionRow(
                        checked = checked,
                        label = buildString {
                            append(if (option == QqContent.IMAGES) "图片" else "QQ 缩略图")
                            available?.let { append("（${formatCountExact(it.countOf(option))} 张 · ${formatBytes(it.bytesOf(option))}）") }
                        },
                        onChange = { on -> onChange { it.copy(contents = if (on) it.contents + option else it.contents - option) } },
                    )
                }
                OptionRow(
                    checked = request.includeShared,
                    label = "也清理与其他会话 / 发送人共用的图片",
                    onChange = { on -> onChange { it.copy(includeShared = on) } },
                )
                OptionRow(
                    checked = request.linkedVersions,
                    label = "同时清理同一张图的其他缓存版本（原图、预览图、缩略图）",
                    onChange = { on -> onChange { it.copy(linkedVersions = on) } },
                )
                Spacer(Modifier.height(4.dp))
                if (counts == null) {
                    WavyProgress(fraction = null)
                } else {
                    InfoLine("可清理 ${formatCountExact(counts.removable)} 张 · ${formatBytes(counts.removableBytes)}", MaterialTheme.colorScheme.primary)
                    if (counts.shared > 0) {
                        InfoLine(
                            "${formatCountExact(counts.shared)} 张也出现在其他会话或发送人中" +
                                if (request.includeShared) "，将一并清理" else "，将跳过",
                        )
                    }
                    if (counts.protectedCount > 0) InfoLine("${formatCountExact(counts.protectedCount)} 张已归档或保留，将跳过")
                }
                Text(
                    "先移入回收站，保留期内可恢复；在后台进行，可以离开本页。删除 QQ 缓存后，聊天里对应的图片可能需要重新下载，过期的将无法再查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = counts != null && counts.removable > 0 && !running) {
                Text(
                    if (counts == null) "统计中…" else "移入回收站",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun OptionRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
