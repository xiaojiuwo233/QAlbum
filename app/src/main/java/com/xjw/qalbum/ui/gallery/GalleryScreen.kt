package com.xjw.qalbum.ui.gallery

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.GallerySort
import com.xjw.qalbum.service.cleanup.CompressionCoordinator
import com.xjw.qalbum.ui.anim.sharedContainer
import com.xjw.qalbum.ui.cleanup.PurgeProgressDialog
import com.xjw.qalbum.ui.common.deleteResultText
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConnectedChoiceRow
import com.xjw.qalbum.ui.components.DeleteImagesDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.FloatingActionToolbar
import com.xjw.qalbum.ui.components.LoadingState
import com.xjw.qalbum.ui.components.PhotoBadges
import com.xjw.qalbum.ui.components.PhotoGrid
import com.xjw.qalbum.ui.components.SaveToAlbumSheet
import com.xjw.qalbum.ui.components.SectionHeader
import com.xjw.qalbum.ui.components.SelectionTopBar
import com.xjw.qalbum.ui.components.ToolbarIconAction
import com.xjw.qalbum.ui.components.ToolbarPrimaryAction
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** 多选集合的 Saver：让 selectedIds 与弹窗开关一样经 rememberSaveable 恢复，二者成对存亡 */
private val SelectedIdsSaver = listSaver<Set<Long>, Long>(
    save = { it.toList() },
    restore = { it.toSet() },
)

@Composable
fun GalleryScreen(
    contentPadding: PaddingValues,
    onImageClick: (Long) -> Unit = {},
    onOpenArchive: () -> Unit = {},
    onOpenSources: () -> Unit = {},
    onSelectionModeChange: (Boolean) -> Unit = {},
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    imageShared: Boolean = false,
    viewModel: GalleryViewModel = koinViewModel(),
) {
    val items = viewModel.timeline.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    val columns by viewModel.galleryColumns.collectAsState()
    val sort by viewModel.gallerySort.collectAsState()
    val largeThreshold by viewModel.largeThresholdBytes.collectAsState()
    val stackVariants by viewModel.stackVariants.collectAsState()
    val directory by viewModel.timelineDirectory.collectAsState()
    val fastScroll by viewModel.fastScroll.collectAsState()
    val deleteProgress by viewModel.deleteProgress.collectAsState()
    val archiveVisible by viewModel.archiveVisible.collectAsState()
    // 换排序 / 合并方式后回到顶部（网格带占位、知道总数，不回顶会停在新列表的中间）；旋转屏幕不算
    var shownOrder by rememberSaveable { mutableStateOf("${sort.name}/$stackVariants") }
    LaunchedEffect(sort, stackVariants) {
        val order = "${sort.name}/$stackVariants"
        if (order != shownOrder) {
            shownOrder = order
            gridState.scrollToItem(0)
        }
    }
    // 多选状态与 rememberSaveable 的弹窗开关成对存亡：否则旋转 / 进程重建后
    // 确认框恢复显示而选中集已清空，出现「删除 0 张」的幽灵对话框
    var selectedIds by rememberSaveable(stateSaver = SelectedIdsSaver) { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showPrefsSheet by rememberSaveable { mutableStateOf(false) }
    var showSaveSheet by rememberSaveable { mutableStateOf(false) }
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val compression = koinInject<CompressionCoordinator>()
    val selectionMode = selectedIds.isNotEmpty()
    val clearSelection = { selectedIds = emptySet() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // 顶栏盖在网格上面：网格从屏幕顶端排起、上方留出顶栏的高度，顶栏收起或换成多选顶栏时网格都不动，
    // 长按后拖动连选的位置才对得上
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopAppBarDefaults.TopAppBarExpandedHeight

    // 多选时外层隐藏底部导航，把位置让给浮动操作栏
    val latestSelectionCallback by rememberUpdatedState(onSelectionModeChange)
    LaunchedEffect(selectionMode) { latestSelectionCallback(selectionMode) }
    DisposableEffect(Unit) { onDispose { latestSelectionCallback(false) } }
    BackHandler(enabled = selectionMode) { clearSelection() }

    val topBarEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val topBarExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn(topBarEnter) togetherWith fadeOut(topBarExit) },
                label = "galleryTopBar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(count = selectedIds.size, onClose = clearSelection)
                } else {
                    TopAppBar(
                        title = { Text("图库") },
                        actions = {
                            IconButton(
                                onClick = onOpenArchive,
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.sharedContainer("archive-bar", sharedScope, animatedScope),
                            ) { Icon(Icons.Outlined.Archive, contentDescription = "归档") }
                            IconButton(onClick = onOpenSources, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.Forum, contentDescription = "按聊天来源浏览")
                            }
                            IconButton(onClick = { showPrefsSheet = true }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.Tune, contentDescription = "显示与排序")
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                items.loadState.refresh is LoadState.Loading && items.itemCount == 0 ->
                    LoadingState(Modifier.padding(top = topInset, bottom = contentPadding.calculateBottomPadding()))
                items.itemCount == 0 -> EmptyState(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = "还没有图片",
                    message = "到「概览」点「全量扫描」导入 QQ 聊天图片",
                    modifier = Modifier.padding(top = topInset, bottom = contentPadding.calculateBottomPadding()),
                )
                else -> RefreshableGrid(items = items, topInset = topInset) {
                    PhotoGrid(
                        items = items,
                        columns = columns,
                        selectedIds = selectedIds,
                        gridState = gridState,
                        onOpen = { onImageClick(it.id) },
                        onSelectedIdsChange = { selectedIds = it },
                        onColumnsChange = viewModel::setColumns,
                        minColumns = SettingsRepository.MIN_GALLERY_COLUMNS,
                        maxColumns = SettingsRepository.MAX_GALLERY_COLUMNS,
                        badges = PhotoBadges(largeThresholdBytes = largeThreshold, stack = stackVariants),
                        sectionIds = viewModel::sectionIds,
                        directory = directory,
                        fastScroll = fastScroll,
                        contentPadding = PaddingValues(top = topInset, bottom = contentPadding.calculateBottomPadding()),
                        sharedScope = if (imageShared) sharedScope else null,
                        animatedScope = animatedScope,
                    )
                }
            }

            FloatingActionToolbar(visible = selectionMode, modifier = Modifier.align(Alignment.BottomCenter)) {
                ToolbarIconAction(Icons.Outlined.Archive, "归档", onClick = {
                    val ids = selectedIds
                    viewModel.archiveImages(ids) { count ->
                        Toast.makeText(context, archivedText(count, archiveVisible), Toast.LENGTH_SHORT).show()
                        clearSelection()
                        items.refresh()
                    }
                })
                ToolbarIconAction(Icons.Outlined.Compress, "压缩", onClick = {
                    compression.start(selectedIds)
                    clearSelection()
                })
                ToolbarIconAction(Icons.Outlined.Download, "保存到相册", onClick = { showSaveSheet = true })
                ToolbarPrimaryAction(Icons.Outlined.Delete, "删除", onClick = { showDeleteConfirm = true })
            }
        }
    }

    if (showDeleteConfirm && selectedIds.isNotEmpty()) {
        val ids = selectedIds
        DeleteImagesDialog(
            imageIds = ids,
            onDismiss = { showDeleteConfirm = false },
            onTrash = { options ->
                showDeleteConfirm = false
                viewModel.moveImagesToTrash(ids, options) { count ->
                    Toast.makeText(context, deleteResultText("已移入回收站", count, ids.size), Toast.LENGTH_SHORT).show()
                    clearSelection()
                    items.refresh()
                }
            },
            onPermanent = { options ->
                showDeleteConfirm = false
                viewModel.deleteImagesPermanently(ids, options) { count ->
                    Toast.makeText(context, deleteResultText("已永久删除", count, ids.size), Toast.LENGTH_SHORT).show()
                    clearSelection()
                    items.refresh()
                }
            },
        )
    }

    if (showPrefsSheet) {
        GalleryPreferenceSheet(
            columns = columns,
            sort = sort,
            thresholdBytes = largeThreshold,
            onColumns = viewModel::setColumns,
            onSort = viewModel::setSort,
            onThreshold = viewModel::setLargeThreshold,
            onDismiss = { showPrefsSheet = false },
        )
    }

    if (showSaveSheet && selectedIds.isNotEmpty()) {
        SaveToAlbumSheet(
            imageCount = selectedIds.size,
            subdirs = exportSubdirs,
            onCreateSubdir = viewModel::createSubdir,
            onDismiss = { showSaveSheet = false },
            onConfirm = { subdir, alsoTrash ->
                val ids = selectedIds
                showSaveSheet = false
                viewModel.saveImages(ids, subdir, alsoTrash) { saved, failed ->
                    Toast.makeText(context, saveResultMessage(saved, failed), Toast.LENGTH_SHORT).show()
                    clearSelection()
                    items.refresh()
                }
            },
        )
    }

    deleteProgress?.let { PurgeProgressDialog(it) }
}

/**
 * 下拉刷新：只在用户主动下拉时显示 Expressive 加载指示器。
 * 扫描 / 分析期间分页源频繁失效也会进入 Loading，不应每次都把指示器拽出来。
 */
@Composable
private fun RefreshableGrid(items: LazyPagingItems<*>, topInset: Dp = 0.dp, content: @Composable () -> Unit) {
    var userRefreshing by remember { mutableStateOf(false) }
    val refreshing = items.loadState.refresh is LoadState.Loading
    LaunchedEffect(refreshing) { if (!refreshing) userRefreshing = false }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = userRefreshing,
        onRefresh = {
            userRefreshing = true
            items.refresh()
        },
        state = state,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = state,
                isRefreshing = userRefreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
            )
        },
    ) { content() }
}

/** 保存结果文案：失败的图不会被移入回收站，必须显式告知张数 */
private fun saveResultMessage(saved: Int, failed: Int): String =
    if (failed > 0) "已保存 $saved 张，$failed 张保存失败（未移入回收站）" else "已保存 $saved 张"

/**
 * 归档：归档的图片（每张图一格）。归档的图片在别处不会被任何清理删除，只有在这里可以明确删除
 * （移入回收站 / 永久删除 / 保存后移入回收站；从回收站恢复会回到归档）；
 * 「归档的图片也在图库中显示」关闭时，它们只在这里出现。
 */
@Composable
fun ArchiveScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onImageClick: (Long) -> Unit = {},
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    imageShared: Boolean = false,
    viewModel: GalleryViewModel = koinViewModel(),
) {
    val items = viewModel.archiveTimeline.collectAsLazyPagingItems()
    val archiveVisible by viewModel.archiveVisible.collectAsState()
    val deleteProgress by viewModel.deleteProgress.collectAsState()
    val directory by viewModel.archiveDirectory.collectAsState()
    val fastScroll by viewModel.fastScroll.collectAsState()
    val largeThreshold by viewModel.largeThresholdBytes.collectAsState()
    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    var columns by rememberSaveable { mutableIntStateOf(4) }
    var selectedIds by rememberSaveable(stateSaver = SelectedIdsSaver) { mutableStateOf(setOf<Long>()) }
    var showSaveSheet by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val compression = koinInject<CompressionCoordinator>()
    val selectionMode = selectedIds.isNotEmpty()
    val clearSelection = { selectedIds = emptySet() }

    BackHandler(enabled = selectionMode) { clearSelection() }

    val topBarEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val topBarExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    // 顶栏固定：进入多选时高度不变，网格不跳
    Scaffold(
        modifier = Modifier.sharedContainer("archive-bar", sharedScope, animatedScope),
        topBar = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn(topBarEnter) togetherWith fadeOut(topBarExit) },
                label = "archiveTopBar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(count = selectedIds.size, onClose = clearSelection)
                } else {
                    TopAppBar(
                        title = { Text("归档") },
                        navigationIcon = { BackButton(onBack) },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(top = topPadding.calculateTopPadding())) {
            when {
                items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> LoadingState()
                items.itemCount == 0 -> EmptyState(
                    icon = Icons.Outlined.Archive,
                    title = "还没有归档的图片",
                    message = "归档的图不会被清理，默认也不在图库里显示。在图库、看图页或聊天来源里点「归档」即可。",
                )
                else -> PhotoGrid(
                    items = items,
                    columns = columns,
                    selectedIds = selectedIds,
                    gridState = gridState,
                    onOpen = { onImageClick(it.id) },
                    onSelectedIdsChange = { selectedIds = it },
                    onColumnsChange = { columns = it },
                    maxColumns = 6,
                    badges = PhotoBadges(archived = false, largeThresholdBytes = largeThreshold),
                    sectionIds = viewModel::archiveSectionIds,
                    directory = directory,
                    fastScroll = fastScroll,
                    contentPadding = contentPadding,
                    sharedScope = if (imageShared) sharedScope else null,
                    animatedScope = animatedScope,
                )
            }

            FloatingActionToolbar(visible = selectionMode, modifier = Modifier.align(Alignment.BottomCenter)) {
                ToolbarIconAction(Icons.Outlined.Unarchive, "取消归档", onClick = {
                    val ids = selectedIds
                    viewModel.unarchiveImages(ids) { count ->
                        Toast.makeText(context, "已取消归档 $count 张", Toast.LENGTH_SHORT).show()
                        clearSelection()
                        items.refresh()
                    }
                })
                ToolbarIconAction(Icons.Outlined.Compress, "压缩", onClick = {
                    compression.start(selectedIds)
                    clearSelection()
                })
                ToolbarIconAction(Icons.Outlined.Download, "保存到相册", onClick = { showSaveSheet = true })
                ToolbarPrimaryAction(Icons.Outlined.Delete, "删除", onClick = { showDeleteConfirm = true })
            }
        }
    }

    if (showDeleteConfirm && selectedIds.isNotEmpty()) {
        val ids = selectedIds
        DeleteImagesDialog(
            imageIds = ids,
            includeArchived = true,
            onDismiss = { showDeleteConfirm = false },
            onTrash = { options ->
                showDeleteConfirm = false
                viewModel.moveImagesToTrash(ids, options) { count ->
                    Toast.makeText(context, deleteResultText("已移入回收站", count, ids.size), Toast.LENGTH_SHORT).show()
                    clearSelection()
                    items.refresh()
                }
            },
            onPermanent = { options ->
                showDeleteConfirm = false
                viewModel.deleteImagesPermanently(ids, options) { count ->
                    Toast.makeText(context, deleteResultText("已永久删除", count, ids.size), Toast.LENGTH_SHORT).show()
                    clearSelection()
                    items.refresh()
                }
            },
        )
    }

    if (showSaveSheet && selectedIds.isNotEmpty()) {
        SaveToAlbumSheet(
            imageCount = selectedIds.size,
            subdirs = exportSubdirs,
            onCreateSubdir = viewModel::createSubdir,
            onDismiss = { showSaveSheet = false },
            onConfirm = { subdir, alsoTrash ->
                val ids = selectedIds
                showSaveSheet = false
                viewModel.saveImages(ids, subdir, alsoTrash, includeArchived = true) { saved, failed ->
                    Toast.makeText(context, saveResultMessage(saved, failed), Toast.LENGTH_SHORT).show()
                    clearSelection()
                    items.refresh()
                }
            },
        )
    }

    deleteProgress?.let { PurgeProgressDialog(it) }
}

/** 归档结果：不在图库显示归档时告诉用户去哪里找 */
internal fun archivedText(count: Int, visible: Boolean): String =
    if (visible) "已归档 $count 张，不会被清理" else "已归档 $count 张，可在「归档」里查看"

/** 图库显示与排序：每行张数、排列顺序（连接式按钮组）、大图标记阈值（合并与快速滚动在「设置 → 外观与导出」） */
@Composable
private fun GalleryPreferenceSheet(
    columns: Int,
    sort: GallerySort,
    thresholdBytes: Long,
    onColumns: (Int) -> Unit,
    onSort: (GallerySort) -> Unit,
    onThreshold: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, bottom = 32.dp),
        ) {
            Text(
                "显示与排序",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SectionHeader("每行图片数")
            val columnOptions = remember {
                (SettingsRepository.MIN_GALLERY_COLUMNS..SettingsRepository.MAX_GALLERY_COLUMNS).toList()
            }
            ConnectedChoiceRow(
                options = columnOptions,
                selected = columns,
                label = { "$it" },
                onSelect = onColumns,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SectionHeader("排列顺序")
            ConnectedChoiceRow(
                options = GallerySort.entries.toList(),
                selected = sort,
                label = { it.label },
                onSelect = onSort,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SectionHeader("大图标记（超过时显示「MB+」）")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                THRESHOLD_PRESETS.forEach { bytes ->
                    FilterChip(
                        selected = bytes == thresholdBytes,
                        onClick = { onThreshold(bytes) },
                        label = { Text(thresholdLabel(bytes)) },
                        leadingIcon = if (bytes == thresholdBytes) {
                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private val THRESHOLD_PRESETS = listOf(
    512L * 1024,
    1L * 1024 * 1024,
    2L * 1024 * 1024,
    5L * 1024 * 1024,
    10L * 1024 * 1024,
)

private fun thresholdLabel(bytes: Long): String =
    if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)} MB" else "${bytes / 1024} KB"
