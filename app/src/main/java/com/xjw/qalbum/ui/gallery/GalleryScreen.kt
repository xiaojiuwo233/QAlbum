package com.xjw.qalbum.ui.gallery

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen(
    contentPadding: PaddingValues,
    onImageClick: (Long) -> Unit = {},
    viewModel: GalleryViewModel = koinViewModel(),
) {
    val items = viewModel.timeline.collectAsLazyPagingItems()
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
        topBar = {
            if (selectedIds.isEmpty()) {
                TimelineTopBar(columns = columns, onRefresh = { items.refresh() })
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
                        viewModel.favoriteImages(ids) { count ->
                            Toast.makeText(context, "已收藏 $count 张", Toast.LENGTH_SHORT).show()
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
                else -> TimelineGrid(
                    items = items,
                    gridState = gridState,
                    columns = columns,
                    selectedIds = selectedIds,
                    contentPadding = contentPadding,
                    selectionMode = selectedIds.isNotEmpty(),
                    onZoomColumns = { columns = it.coerceIn(2, 6) },
                    onImageClick = onImageClick,
                    onToggleSelection = toggleSelection,
                    onSelect = selectOne,
                    onApplySelection = applySelection,
                )
            }

            if (showDeleteConfirm) {
                DeleteSelectionDialog(
                    count = selectedIds.size,
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
private fun TimelineTopBar(columns: Int, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                "时间线 · ${columns}列",
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
private fun TimelineGrid(
    items: LazyPagingItems<GalleryTimelineItem>,
    gridState: LazyGridState,
    columns: Int,
    selectedIds: Set<Long>,
    contentPadding: PaddingValues,
    selectionMode: Boolean,
    onZoomColumns: (Int) -> Unit,
    onImageClick: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelect: (Long) -> Unit,
    onApplySelection: (Long, Boolean) -> Unit,
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
            start = 2.dp,
            end = 2.dp,
            top = 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
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
            .pointerInput(selectionMode, columns) {
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
                    is GalleryTimelineItem.Header -> "header-${item.timestampMs}"
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
                is GalleryTimelineItem.Header -> DateHeader(item.timestampMs)
                is GalleryTimelineItem.Photo -> TimelinePhoto(
                    image = item.image,
                    selected = item.image.id in selectedIds,
                    selectionMode = selectionMode,
                    onBounds = { photoBounds[item.image.id] = it },
                    onClick = {
                        if (selectionMode) onToggleSelection(item.image.id) else onImageClick(item.image.id)
                    },
                    onLongClick = {
                        onSelect(item.image.id)
                    },
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
private fun DateHeader(timestampMs: Long) {
    Text(
        text = dayLabel(timestampMs),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 14.dp, top = 18.dp, end = 14.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelinePhoto(
    image: ImageEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onBounds: (Rect) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
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
            modifier = Modifier.fillMaxSize(),
        )
        if (selectionMode || selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (selected) Color.Black.copy(alpha = 0.38f) else Color.Transparent),
            )
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.36f)),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun DeleteSelectionDialog(
    count: Int,
    onDismiss: () -> Unit,
    onTrash: () -> Unit,
    onPermanent: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 $count 张图片？") },
        text = { Text("可以先移动到回收站，或直接永久删除。永久删除后不可恢复。") },
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

private val dateFormatter = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

private fun dayLabel(timestampMs: Long): String {
    val target = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val today = Calendar.getInstance()
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(target, today) -> "今天"
        sameDay(target, yesterday) -> "昨天"
        else -> dateFormatter.format(Date(timestampMs))
    }
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
