package com.xjw.qalbum.ui.video

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.xjw.qalbum.data.db.entity.VideoEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.FloatingActionToolbar
import com.xjw.qalbum.ui.components.PhotoBadges
import com.xjw.qalbum.ui.components.PhotoGrid
import com.xjw.qalbum.ui.components.SelectionTopBar
import com.xjw.qalbum.ui.components.ToolbarPrimaryAction
import com.xjw.qalbum.ui.components.WavyProgress
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.sources.SourceSortMenuButton
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private val SelectedSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() })

/**
 * 视频：QQ 聊天里的视频与收到的视频文件。网格同图库（日期 / 大小分组、长按拖动多选、点标题选整组、捏合改列数），
 * 格子上显示时长；点开全屏播放，可保存到相册或删除。删除是永久删除。
 */
@Composable
fun VideoScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: VideoViewModel = koinViewModel(),
) {
    val items = viewModel.videos.collectAsLazyPagingItems()
    val sort by viewModel.sort.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val directory by viewModel.directory.collectAsState()
    val fastScroll by viewModel.fastScroll.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val noAccess by viewModel.noAccess.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val includeFiles by viewModel.includeFiles.collectAsState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var selected by rememberSaveable(stateSaver = SelectedSaver) { mutableStateOf(setOf<Long>()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<List<VideoEntity>, Int>?>(null) }
    val stats = sources.orEmpty()
    val shownCount = (if (filter.source == null) stats else stats.filter { it.source == filter.source }).sumOf { it.count }
    val selecting = selected.isNotEmpty()
    val clearSelection = { selected = emptySet() }
    val selectAll: () -> Unit = { scope.launch { selected = viewModel.allIds() } }

    BackHandler(enabled = selecting) { clearSelection() }
    // 筛选条、扫描进度出现在最上面时：还在顶部附近就滚回顶部（列表默认停在原来的第一项，新加的项会藏在上面）
    val showFilters = stats.size > 1
    LaunchedEffect(showFilters, scanning) {
        if (gridState.firstVisibleItemIndex <= 2) gridState.scrollToItem(0)
    }
    // 换排序或筛选后回到顶部（旋转屏幕不算）
    val order = "${sort.name}/${filter.name}"
    var shownOrder by rememberSaveable { mutableStateOf(order) }
    LaunchedEffect(order) {
        if (order != shownOrder) {
            shownOrder = order
            gridState.scrollToItem(0)
        }
    }

    val topEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val topExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(topEnter) togetherWith fadeOut(topExit) },
                label = "videoTopBar",
            ) { selectingNow ->
                if (selectingNow) {
                    val all = shownCount > 0 && selected.size >= shownCount
                    SelectionTopBar(
                        count = selected.size,
                        unit = "个",
                        onClose = clearSelection,
                        actions = {
                            IconButton(onClick = if (all) clearSelection else selectAll, shapes = IconButtonDefaults.shapes()) {
                                Icon(if (all) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, contentDescription = if (all) "取消全选" else "全选")
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = { Text("视频") },
                        navigationIcon = { BackButton(onBack) },
                        actions = {
                            SourceSortMenuButton(sort = sort, onSort = viewModel::setSort)
                            IconButton(onClick = viewModel::refresh, enabled = !scanning, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "重新查找")
                            }
                            IconButton(onClick = selectAll, enabled = shownCount > 0, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.SelectAll, contentDescription = "全选")
                            }
                            VideoMenu(includeFiles = includeFiles, onIncludeFiles = { include ->
                                clearSelection()
                                viewModel.setIncludeFiles(include)
                            })
                        },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            PhotoGrid(
                items = items,
                columns = columns,
                selectedIds = selected,
                gridState = gridState,
                badges = PhotoBadges(archived = false, size = !sort.byDate),
                onOpen = { image ->
                    val loaded = items.itemSnapshotList.items.filterIsInstance<GalleryTimelineItem.Photo>().mapNotNull { it.tag as? VideoEntity }
                    val index = loaded.indexOfFirst { it.id == image.id }
                    if (index >= 0) preview = loaded to index
                },
                onSelectedIdsChange = { selected = it },
                onColumnsChange = viewModel::setColumns,
                minColumns = SettingsRepository.MIN_GALLERY_COLUMNS,
                maxColumns = SettingsRepository.MAX_GALLERY_COLUMNS,
                sectionIds = viewModel::sectionIds,
                directory = directory,
                fastScroll = fastScroll,
                tileModel = viewModel::tileModel,
                // 时长放右上角：按大小排时右下角是大小
                tileOverlay = { photo -> DurationBadge((photo.tag as? VideoEntity)?.durationMs ?: 0, Modifier.align(Alignment.TopEnd)) },
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 96.dp),
                leadingContent = {
                    if (scanning) {
                        item(key = "scanning", span = { GridItemSpan(maxLineSpan) }, contentType = "scanning") {
                            WavyProgress(fraction = null, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    // 两种来源都有时才需要筛选
                    if (showFilters) {
                        item(key = "filters", span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                VideoFilter.entries.forEach { option ->
                                    FilterChip(
                                        selected = option == filter,
                                        onClick = {
                                            clearSelection()
                                            viewModel.setFilter(option)
                                        },
                                        label = { Text(VideoViewModel.filterLabel(option, stats)) },
                                    )
                                }
                            }
                        }
                    }
                    if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading && !scanning && sources != null) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                            Box(Modifier.fillMaxWidth().height(400.dp)) {
                                if (noAccess) {
                                    EmptyState(icon = Icons.Outlined.FolderOff, title = "需要文件访问", message = "在设置里连接 Root 或 Shizuku")
                                } else {
                                    EmptyState(icon = Icons.Outlined.VideoLibrary, title = "没有找到视频")
                                }
                            }
                        }
                    }
                },
            )

            FloatingActionToolbar(visible = selecting, modifier = Modifier.align(Alignment.BottomCenter)) {
                ToolbarPrimaryAction(
                    Icons.Outlined.DeleteForever,
                    "删除 ${formatCountExact(selected.size)} 个",
                    enabled = !busy,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete) {
        val ids = selected
        var bytes by remember { mutableLongStateOf(-1L) }
        LaunchedEffect(ids) { bytes = viewModel.selectionBytes(ids) }
        if (ids.isEmpty()) {
            LaunchedEffect(Unit) { confirmDelete = false }
        } else {
            ConfirmDialog(
                icon = Icons.Outlined.DeleteForever,
                title = "删除 ${formatCountExact(ids.size)} 个视频？",
                text = (if (bytes >= 0) "共 ${formatBytes(bytes)}，" else "") + "删除后无法恢复。",
                confirmLabel = "删除",
                destructive = true,
                onDismiss = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    viewModel.delete(ids, onDone = clearSelection)
                },
            )
        }
    }

    preview?.let { (list, start) ->
        VideoPlayerPager(
            videos = list,
            startIndex = start,
            onClose = { preview = null },
            open = viewModel::open,
            posterOf = { it.frameModel() },
            exportSubdirs = exportSubdirs,
            onCreateSubdir = viewModel::addExportSubdir,
            onSave = { video, subdir -> viewModel.export(video.id, subdir) },
            onDelete = { video ->
                viewModel.delete(listOf(video.id))
                preview = preview?.without(video.id)
            },
        )
    }
    // 进度对话框与结果提示由 CleanupScreen 根部统一显示
}

/** 右上角菜单：要不要包含收到的文件（QQfile_recv 里多是手动下载的群文件），默认不包含 */
@Composable
private fun VideoMenu(includeFiles: Boolean, onIncludeFiles: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("包含收到的文件")
                        Text("QQfile_recv 里的视频", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailingIcon = { Switch(checked = includeFiles, onCheckedChange = null) },
                onClick = {
                    open = false
                    onIncludeFiles(!includeFiles)
                },
            )
        }
    }
}

/** 格子右上角：播放图标 + 时长（还没读出时长时只有图标） */
@Composable
private fun DurationBadge(durationMs: Long, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(start = 3.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        formatDuration(durationMs)?.let {
            Spacer(Modifier.width(2.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
        }
    }
}

/** 从看视频的快照移除一个并停在下一个（删的是末个则后退），删空后关闭 */
private fun Pair<List<VideoEntity>, Int>.without(id: Long): Pair<List<VideoEntity>, Int>? {
    val index = first.indexOfFirst { it.id == id }
    if (index < 0) return this
    val remaining = first.filterNot { it.id == id }
    if (remaining.isEmpty()) return null
    return remaining to index.coerceAtMost(remaining.lastIndex)
}
