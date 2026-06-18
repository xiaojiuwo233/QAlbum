package com.xjw.qalbum.ui.gallery

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.GallerySort
import com.xjw.qalbum.ui.anim.sharedContainer
import com.xjw.qalbum.ui.anim.sharedImage
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun GalleryScreen(
    contentPadding: PaddingValues,
    onImageClick: (Long) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
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
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showPrefsSheet by rememberSaveable { mutableStateOf(false) }

    val clearSelection = { selectedIds = emptySet() }
    val toggleSelection: (Long) -> Unit = { id ->
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    val selectOne: (Long) -> Unit = { id ->
        selectedIds = selectedIds + id
    }
    val applySelection: (Long, Boolean) -> Unit = { id, selected ->
        selectedIds = if (selected) selectedIds + id else selectedIds - id
    }

    Scaffold(
        topBar = {
            if (selectedIds.isEmpty()) {
                TimelineTopBar(
                    columns = columns,
                    sortLabel = sort.label,
                    onOpenSearch = onOpenSearch,
                    onOpenFavorites = onOpenFavorites,
                    onOpenSettings = { showPrefsSheet = true },
                    sharedScope = sharedScope,
                    animatedScope = animatedScope,
                )
            } else {
                SelectionTopBar(
                    count = selectedIds.size,
                    onClose = clearSelection,
                    onExport = {
                        val ids = selectedIds
                        viewModel.exportImages(ids) { count ->
                            Toast.makeText(context, "已导出 $count 张", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFavorite = {
                        val ids = selectedIds
                        viewModel.addFavorites(ids) { count ->
                            val message = if (count > 0) "已收藏 $count 张" else "所选图片已在收藏中"
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            clearSelection()
                            items.refresh()
                        }
                    },
                    onDelete = { showDeleteConfirm = true },
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding()),
        ) {
            when {
                items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> CenteredLoading()
                items.itemCount == 0 -> EmptyState()
                else -> PullToRefreshBox(
                    isRefreshing = items.loadState.refresh is LoadState.Loading,
                    onRefresh = { items.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    TimelineGrid(
                        items = items,
                        gridState = gridState,
                        columns = columns,
                        selectedIds = selectedIds,
                        contentPadding = contentPadding,
                        selectionMode = selectedIds.isNotEmpty(),
                        largeThresholdBytes = largeThreshold,
                        onZoomColumns = { viewModel.setColumns(it) },
                        onImageClick = onImageClick,
                        onToggleSelection = toggleSelection,
                        onSelect = selectOne,
                        onApplySelection = applySelection,
                        sharedScope = if (imageShared) sharedScope else null,
                        animatedScope = animatedScope,
                    )
                }
            }

            if (showDeleteConfirm) {
                DeleteSelectionDialog(
                    count = selectedIds.size,
                    ids = selectedIds,
                    sizeProvider = viewModel::selectedSize,
                    onDismiss = { showDeleteConfirm = false },
                    onTrash = {
                        val ids = selectedIds
                        showDeleteConfirm = false
                        viewModel.moveImagesToTrash(ids) { count ->
                            Toast.makeText(context, "已移到回收站 $count 张", Toast.LENGTH_SHORT).show()
                            clearSelection()
                            items.refresh()
                        }
                    },
                    onPermanent = {
                        val ids = selectedIds
                        showDeleteConfirm = false
                        viewModel.deleteImagesPermanently(ids) { count ->
                            Toast.makeText(context, "已永久删除 $count 张", Toast.LENGTH_SHORT).show()
                            clearSelection()
                            items.refresh()
                        }
                    },
                )
            }
        }
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
}

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
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    val clearSelection = { selectedIds = emptySet() }
    val toggleSelection: (Long) -> Unit = { id ->
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    val selectOne: (Long) -> Unit = { id ->
        selectedIds = selectedIds + id
    }
    val applySelection: (Long, Boolean) -> Unit = { id, selected ->
        selectedIds = if (selected) selectedIds + id else selectedIds - id
    }

    Scaffold(
        modifier = Modifier.sharedContainer("favorites-bar", sharedScope, animatedScope),
        topBar = {
            if (selectedIds.isEmpty()) {
                FavoritesTopBar(
                    count = favoriteCount,
                    onBack = onBack,
                    onRefresh = { items.refresh() },
                )
            } else {
                FavoriteSelectionTopBar(
                    count = selectedIds.size,
                    onClose = clearSelection,
                    onExport = {
                        val ids = selectedIds
                        viewModel.exportImages(ids) { count ->
                            Toast.makeText(context, "已导出 $count 张", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRemoveFavorite = {
                        val ids = selectedIds
                        viewModel.removeFavorites(ids) { count ->
                            Toast.makeText(context, "已取消收藏 $count 张", Toast.LENGTH_SHORT).show()
                            clearSelection()
                            items.refresh()
                        }
                    },
                    onDelete = { showDeleteConfirm = true },
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding()),
        ) {
            when {
                items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> CenteredLoading()
                items.itemCount == 0 -> FavoritesEmptyState()
                else -> TimelineGrid(
                    items = items,
                    gridState = gridState,
                    columns = columns,
                    selectedIds = selectedIds,
                    contentPadding = contentPadding,
                    selectionMode = selectedIds.isNotEmpty(),
                    largeThresholdBytes = largeThreshold,
                    onZoomColumns = { columns = it.coerceIn(2, 6) },
                    onImageClick = onImageClick,
                    onToggleSelection = toggleSelection,
                    onSelect = selectOne,
                    onApplySelection = applySelection,
                    sharedScope = if (imageShared) sharedScope else null,
                    animatedScope = animatedScope,
                )
            }

            if (showDeleteConfirm) {
                DeleteSelectionDialog(
                    count = selectedIds.size,
                    ids = selectedIds,
                    sizeProvider = viewModel::selectedSize,
                    onDismiss = { showDeleteConfirm = false },
                    onTrash = {
                        val ids = selectedIds
                        showDeleteConfirm = false
                        viewModel.moveImagesToTrash(ids) { count ->
                            Toast.makeText(context, "已移到回收站 $count 张", Toast.LENGTH_SHORT).show()
                            clearSelection()
                            items.refresh()
                        }
                    },
                    onPermanent = {
                        val ids = selectedIds
                        showDeleteConfirm = false
                        viewModel.deleteImagesPermanently(ids) { count ->
                            Toast.makeText(context, "已永久删除 $count 张", Toast.LENGTH_SHORT).show()
                            clearSelection()
                            items.refresh()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TimelineTopBar(
    columns: Int,
    sortLabel: String,
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "图库",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "$sortLabel · ${columns}列",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onOpenSearch,
            modifier = Modifier.sharedContainer("search-bar", sharedScope, animatedScope),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索")
        }
        IconButton(
            onClick = onOpenFavorites,
            modifier = Modifier.sharedContainer("favorites-bar", sharedScope, animatedScope),
        ) {
            Icon(Icons.Outlined.FavoriteBorder, contentDescription = "收藏图库")
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Tune, contentDescription = "图库偏好")
        }
    }
}

@Composable
private fun FavoritesTopBar(
    count: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, top = 10.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "收藏",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "$count 张图片",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
        }
    }
}

@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onExport: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "取消选择")
        }
        Text(
            "已选择 $count 张",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onExport) {
            Icon(Icons.Outlined.IosShare, contentDescription = "导出")
        }
        IconButton(onClick = onFavorite) {
            Icon(Icons.Outlined.Favorite, contentDescription = "收藏")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除")
        }
    }
}

@Composable
private fun FavoriteSelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onExport: () -> Unit,
    onRemoveFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "取消选择")
        }
        Text(
            "已选择 $count 张",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onExport) {
            Icon(Icons.Outlined.IosShare, contentDescription = "导出")
        }
        IconButton(onClick = onRemoveFavorite) {
            Icon(Icons.Outlined.FavoriteBorder, contentDescription = "取消收藏")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除")
        }
    }
}

@Composable
private fun TimelineGrid(
    items: LazyPagingItems<GalleryTimelineItem>,
    gridState: LazyGridState,
    columns: Int,
    selectedIds: Set<Long>,
    contentPadding: PaddingValues,
    selectionMode: Boolean,
    largeThresholdBytes: Long,
    onZoomColumns: (Int) -> Unit,
    onImageClick: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelect: (Long) -> Unit,
    onApplySelection: (Long, Boolean) -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val photoBounds = remember { mutableStateMapOf<Long, Rect>() }
    var containerBounds by remember { mutableStateOf(Rect.Zero) }
    var dragSelectTarget by remember { mutableStateOf<Boolean?>(null) }
    val draggedIds = remember { mutableSetOf<Long>() }

    fun idAt(rootOffset: Offset): Long? {
        val visibleIds = gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }.toSet()
        return photoBounds.entries.firstOrNull { (id, bounds) ->
            id in visibleIds && bounds.contains(rootOffset)
        }?.key
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            start = 1.5.dp,
            end = 1.5.dp,
            top = 2.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { containerBounds = it.boundsInRoot() }
            .pointerInput(columns) {
                awaitEachGesture {
                    var startDistance = 0f
                    var changedColumns = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size < 2) break
                        val distance = (pressed[0].position - pressed[1].position).getDistance()
                        if (startDistance == 0f) startDistance = distance
                        val ratio = if (startDistance == 0f) 1f else distance / startDistance
                        if (!changedColumns) {
                            when {
                                ratio > 1.16f -> {
                                    onZoomColumns(columns - 1)
                                    changedColumns = true
                                }
                                ratio < 0.86f -> {
                                    onZoomColumns(columns + 1)
                                    changedColumns = true
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(columns) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val rootOffset = offset + Offset(containerBounds.left, containerBounds.top)
                        val id = idAt(rootOffset) ?: return@detectDragGesturesAfterLongPress
                        val target = id !in selectedIds
                        dragSelectTarget = target
                        draggedIds.clear()
                        draggedIds.add(id)
                        onApplySelection(id, target)
                    },
                    onDrag = { change, _ ->
                        val rootOffset = change.position + Offset(containerBounds.left, containerBounds.top)
                        val id = idAt(rootOffset)
                        val target = dragSelectTarget
                        if (id != null && target != null && draggedIds.add(id)) {
                            onApplySelection(id, target)
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        dragSelectTarget = null
                        draggedIds.clear()
                    },
                    onDragCancel = {
                        dragSelectTarget = null
                        draggedIds.clear()
                    },
                )
            },
    ) {
        items(
            count = items.itemCount,
            key = { index ->
                when (val item = items.peek(index)) {
                    is GalleryTimelineItem.Header -> item.key
                    is GalleryTimelineItem.Photo -> item.image.id
                    null -> "loading-$index"
                }
            },
            span = { index ->
                if (items.peek(index) is GalleryTimelineItem.Header) {
                    GridItemSpan(maxLineSpan)
                } else {
                    GridItemSpan(1)
                }
            },
        ) { index ->
            when (val item = items[index]) {
                is GalleryTimelineItem.Header -> SectionHeader(item.label)
                is GalleryTimelineItem.Photo -> TimelinePhoto(
                    image = item.image,
                    selected = item.image.id in selectedIds,
                    selectionMode = selectionMode,
                    largeThresholdBytes = largeThresholdBytes,
                    onBounds = { photoBounds[item.image.id] = it },
                    onClick = {
                        if (selectionMode) onToggleSelection(item.image.id) else onImageClick(item.image.id)
                    },
                    onLongClick = { onSelect(item.image.id) },
                    sharedScope = sharedScope,
                    animatedScope = animatedScope,
                )
                null -> Unit
            }
        }

        if (items.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelinePhoto(
    image: ImageEntity,
    selected: Boolean,
    selectionMode: Boolean,
    largeThresholdBytes: Long,
    onBounds: (Rect) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val selectedScale by animateFloatAsState(
        targetValue = if (selected) 0.88f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "selectScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .graphicsLayer {
                scaleX = selectedScale
                scaleY = selectedScale
            }
            .clip(RoundedCornerShape(if (selected) 8.dp else 0.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(ImageEntityModel(image))
                .crossfade(true)
                .build(),
            contentDescription = image.filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .sharedImage("image-${image.id}", sharedScope, animatedScope),
        )
        if (image.isFavorite && !selectionMode && !selected) {
            Icon(
                Icons.Outlined.Favorite,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(3.dp),
                tint = Color.White,
            )
        }
        // 大图标记：超过阈值则在右上角显示 MB+，与收藏 ♥ 并列存在
        if (image.size > largeThresholdBytes && !selectionMode && !selected) {
            Text(
                text = "MB+",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (selectionMode || selected) {
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.36f)),
                tint = Color.White,
            )
        }
    }
}

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
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                "图库偏好",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(20.dp))
            SheetSectionLabel("每行图片数")
            Spacer(Modifier.height(8.dp))
            val columnOptions = remember {
                (SettingsRepository.MIN_GALLERY_COLUMNS..SettingsRepository.MAX_GALLERY_COLUMNS).toList()
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                columnOptions.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = value == columns,
                        onClick = { onColumns(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, columnOptions.size),
                    ) { Text("$value") }
                }
            }

            Spacer(Modifier.height(20.dp))
            SheetSectionLabel("排列顺序")
            Spacer(Modifier.height(8.dp))
            val sortOptions = GallerySort.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                sortOptions.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = value == sort,
                        onClick = { onSort(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, sortOptions.size),
                    ) { Text(value.label) }
                }
            }

            Spacer(Modifier.height(20.dp))
            SheetSectionLabel("大图标记阈值（超过显示 MB+）")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                THRESHOLD_PRESETS.forEach { bytes ->
                    FilterChip(
                        selected = bytes == thresholdBytes,
                        onClick = { onThreshold(bytes) },
                        label = { Text(thresholdLabel(bytes)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val THRESHOLD_PRESETS = listOf(
    512L * 1024,
    1L * 1024 * 1024,
    2L * 1024 * 1024,
    5L * 1024 * 1024,
    10L * 1024 * 1024,
)

private fun thresholdLabel(bytes: Long): String =
    if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)}MB" else "${bytes / 1024}KB"

@Composable
private fun DeleteSelectionDialog(
    count: Int,
    ids: Set<Long>,
    sizeProvider: suspend (Set<Long>) -> Long,
    onDismiss: () -> Unit,
    onTrash: () -> Unit,
    onPermanent: () -> Unit,
) {
    // 异步统计选中图片的总大小，未算出前显示「计算中…」
    val bytes by produceState(initialValue = -1L, ids) { value = sizeProvider(ids) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 $count 张图片？") },
        text = {
            Column {
                Text("可以先移动到回收站，或直接永久删除。永久删除后不可恢复。")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (bytes >= 0) {
                        "$count 张图片 · ${formatBytes(bytes)}"
                    } else {
                        "$count 张图片 · 计算中…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onTrash) { Text("移动到回收站") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onPermanent) { Text("永久删除") }
            }
        },
    )
}

/** 字节数格式化为人类可读（与清理页一致） */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.1f %s", value, units[exp])
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "还没有图片",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "先到「概览」或「设置」触发扫描，QAlbum 会把 QQ chatpic 下的图片索引进来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun FavoritesEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "还没有收藏",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "在图库或图片详情中点收藏后，会集中显示在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}
