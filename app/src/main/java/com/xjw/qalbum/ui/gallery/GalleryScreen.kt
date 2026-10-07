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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.GallerySort
import com.xjw.qalbum.ui.anim.sharedContainer
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

/** 多选集合的 Saver：让 selectedIds 与弹窗开关一样经 rememberSaveable 恢复，二者成对存亡 */
private val SelectedIdsSaver = listSaver<Set<Long>, Long>(
    save = { it.toList() },
    restore = { it.toSet() },
)

@Composable
fun GalleryScreen(
    contentPadding: PaddingValues,
    onImageClick: (Long) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
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
    val enabledPhases by viewModel.enabledPhases.collectAsState()
    // 多选状态与 rememberSaveable 的弹窗开关成对存亡：否则旋转 / 进程重建后
    // 确认框恢复显示而选中集已清空，出现「删除 0 张」的幽灵对话框
    var selectedIds by rememberSaveable(stateSaver = SelectedIdsSaver) { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showPrefsSheet by rememberSaveable { mutableStateOf(false) }
    var showSaveSheet by rememberSaveable { mutableStateOf(false) }
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()
    val clearSelection = { selectedIds = emptySet() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

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
                        subtitle = { Text("${sort.label} · 每行 $columns 张") },
                        actions = {
                            IconButton(
                                onClick = onOpenSearch,
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.sharedContainer("search-bar", sharedScope, animatedScope),
                            ) { Icon(Icons.Outlined.Search, contentDescription = "搜索") }
                            IconButton(
                                onClick = onOpenFavorites,
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.sharedContainer("favorites-bar", sharedScope, animatedScope),
                            ) { Icon(Icons.Outlined.FavoriteBorder, contentDescription = "收藏") }
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
    ) { topPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(top = topPadding.calculateTopPadding())) {
            when {
                items.loadState.refresh is LoadState.Loading && items.itemCount == 0 ->
                    LoadingState(Modifier.padding(bottom = contentPadding.calculateBottomPadding()))
                items.itemCount == 0 -> EmptyState(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = "还没有图片",
                    message = "到「概览」点击「全量扫描」，QAlbum 会把 QQ 聊天图片目录索引进来。",
                    modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
                )
                else -> RefreshableGrid(items = items) {
                    PhotoGrid(
                        items = items,
                        columns = columns,
                        selectedIds = selectedIds,
                        gridState = gridState,
                        onOpen = { onImageClick(it.id) },
                        onSelectionChange = { id, selected ->
                            selectedIds = if (selected) selectedIds + id else selectedIds - id
                        },
                        onColumnsChange = viewModel::setColumns,
                        minColumns = SettingsRepository.MIN_GALLERY_COLUMNS,
                        maxColumns = SettingsRepository.MAX_GALLERY_COLUMNS,
                        badges = PhotoBadges(largeThresholdBytes = largeThreshold, analyzedPhases = enabledPhases),
                        contentPadding = contentPadding,
                        sharedScope = if (imageShared) sharedScope else null,
                        animatedScope = animatedScope,
                    )
                }
            }

            FloatingActionToolbar(visible = selectionMode, modifier = Modifier.align(Alignment.BottomCenter)) {
                ToolbarIconAction(Icons.Outlined.FavoriteBorder, "收藏", onClick = {
                    val ids = selectedIds
                    viewModel.addFavorites(ids) { count ->
                        Toast.makeText(context, if (count > 0) "已收藏 $count 张" else "所选图片已在收藏中", Toast.LENGTH_SHORT).show()
                        clearSelection()
                        items.refresh()
                    }
                })
                ToolbarIconAction(Icons.Outlined.Download, "保存到相册", onClick = { showSaveSheet = true })
                ToolbarPrimaryAction(Icons.Outlined.Delete, "删除", onClick = { showDeleteConfirm = true })
            }
        }
    }

    if (showDeleteConfirm && selectedIds.isNotEmpty()) {
        val ids = selectedIds
        DeleteImagesDialog(
            count = ids.size,
            imageIds = ids,
            onTrashOptions = { options ->
                showDeleteConfirm=false
                viewModel.moveImagesToTrash(ids,options) { count ->
                    Toast.makeText(context,"已移入回收站 $count 张；未处理的图片保留",Toast.LENGTH_SHORT).show()
                    clearSelection();items.refresh()
                }
            },
            onPermanentOptions = { options ->
                showDeleteConfirm=false
                viewModel.deleteImagesPermanently(ids,options) { count ->
                    Toast.makeText(context,"已永久删除 $count 张；未处理的图片保留",Toast.LENGTH_SHORT).show()
                    clearSelection();items.refresh()
                }
            },
            sizeOf = { viewModel.selectedSize(ids) },
            onDismiss = { showDeleteConfirm = false },
            onTrash = {
                showDeleteConfirm = false
                viewModel.moveImagesToTrash(ids) { count ->
                    Toast.makeText(context, "已移入回收站 $count 张", Toast.LENGTH_SHORT).show()
                    clearSelection()
                    items.refresh()
                }
            },
            onPermanent = {
                showDeleteConfirm = false
                viewModel.deleteImagesPermanently(ids) { count ->
                    Toast.makeText(context, "已永久删除 $count 张", Toast.LENGTH_SHORT).show()
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
}

/**
 * 下拉刷新：只在用户主动下拉时显示 Expressive 加载指示器。
 * 扫描 / 分析期间分页源频繁失效也会进入 Loading，不应每次都把指示器拽出来。
 */
@Composable
private fun RefreshableGrid(items: LazyPagingItems<*>, content: @Composable () -> Unit) {
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
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) { content() }
}

/** 保存结果文案：失败的图不会被移入回收站，必须显式告知张数 */
private fun saveResultMessage(saved: Int, failed: Int): String =
    if (failed > 0) "已保存 $saved 张，$failed 张保存失败（未移入回收站）" else "已保存 $saved 张"

@Composable
fun FavoriteGalleryScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onImageClick: (Long) -> Unit = {},
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    imageShared: Boolean = false,
    viewModel: GalleryViewModel = koinViewModel(),
) {
    val items = viewModel.favoriteTimeline.collectAsLazyPagingItems()
    val favoriteCount by viewModel.favoriteCount.collectAsState(initial = 0)
    val largeThreshold by viewModel.largeThresholdBytes.collectAsState()
    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    var columns by rememberSaveable { mutableIntStateOf(4) }
    var selectedIds by rememberSaveable(stateSaver = SelectedIdsSaver) { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showSaveSheet by rememberSaveable { mutableStateOf(false) }
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()
    val clearSelection = { selectedIds = emptySet() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    BackHandler(enabled = selectionMode) { clearSelection() }

    val topBarEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val topBarExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Scaffold(
        modifier = Modifier
            .sharedContainer("favorites-bar", sharedScope, animatedScope)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn(topBarEnter) togetherWith fadeOut(topBarExit) },
                label = "favoritesTopBar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(count = selectedIds.size, onClose = clearSelection)
                } else {
                    TopAppBar(
                        title = { Text("收藏") },
                        subtitle = { Text("$favoriteCount 张图片 · 不会被清理") },
                        navigationIcon = { BackButton(onBack) },
                        scrollBehavior = scrollBehavior,
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
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "还没有收藏",
                    message = "在图库或看图页点收藏后会集中显示在这里，收藏的图片不会被任何清理流程删除。",
                )
                else -> PhotoGrid(
                    items = items,
                    columns = columns,
                    selectedIds = selectedIds,
                    gridState = gridState,
                    onOpen = { onImageClick(it.id) },
                    onSelectionChange = { id, selected ->
                        selectedIds = if (selected) selectedIds + id else selectedIds - id
                    },
                    onColumnsChange = { columns = it },
                    maxColumns = 6,
                    badges = PhotoBadges(favorite = false, largeThresholdBytes = largeThreshold),
                    contentPadding = contentPadding,
                    sharedScope = if (imageShared) sharedScope else null,
                    animatedScope = animatedScope,
                )
            }

            FloatingActionToolbar(visible = selectionMode, modifier = Modifier.align(Alignment.BottomCenter)) {
                ToolbarIconAction(Icons.Outlined.HeartBroken, "取消收藏", onClick = {
                    val ids = selectedIds
                    viewModel.removeFavorites(ids) { count ->
                        Toast.makeText(context, "已取消收藏 $count 张", Toast.LENGTH_SHORT).show()
                        clearSelection()
                        items.refresh()
                    }
                })
                ToolbarIconAction(Icons.Outlined.Download, "保存到相册", onClick = { showSaveSheet = true })
                ToolbarPrimaryAction(Icons.Outlined.Delete, "删除", onClick = { showDeleteConfirm = true })
            }
        }
    }

    if (showDeleteConfirm && selectedIds.isNotEmpty()) {
        val ids = selectedIds
        DeleteImagesDialog(
            count = ids.size,
            imageIds = ids,
            onTrashOptions = { options ->
                showDeleteConfirm=false
                viewModel.moveImagesToTrash(ids,options) { count ->
                    Toast.makeText(context,"已移入回收站 $count 张；未处理的图片保留",Toast.LENGTH_SHORT).show()
                    clearSelection();items.refresh()
                }
            },
            onPermanentOptions = { options ->
                showDeleteConfirm=false
                viewModel.deleteImagesPermanently(ids,options) { count ->
                    Toast.makeText(context,"已永久删除 $count 张；未处理的图片保留",Toast.LENGTH_SHORT).show()
                    clearSelection();items.refresh()
                }
            },
            sizeOf = { viewModel.selectedSize(ids) },
            note = "收藏的图片受保护：请先取消收藏，再执行删除。",
            onDismiss = { showDeleteConfirm = false },
            onTrash = {
                showDeleteConfirm = false
                viewModel.moveImagesToTrash(ids) { count ->
                    Toast.makeText(context, "已移入回收站 $count 张", Toast.LENGTH_SHORT).show()
                    clearSelection()
                    items.refresh()
                }
            },
            onPermanent = {
                showDeleteConfirm = false
                viewModel.deleteImagesPermanently(ids) { count ->
                    Toast.makeText(context, "已永久删除 $count 张", Toast.LENGTH_SHORT).show()
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
                viewModel.saveImages(ids, subdir, alsoTrash) { saved, failed ->
                    Toast.makeText(context, saveResultMessage(saved, failed), Toast.LENGTH_SHORT).show()
                    clearSelection()
                    items.refresh()
                }
            },
        )
    }
}

/** 图库显示与排序：每行张数、排列顺序（连接式按钮组）、大图标记阈值 */
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
