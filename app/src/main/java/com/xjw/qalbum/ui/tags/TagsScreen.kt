package com.xjw.qalbum.ui.tags

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.ui.cleanup.PurgeProgressDialog
import com.xjw.qalbum.ui.cleanup.shareChooser
import com.xjw.qalbum.ui.components.FullScreenImagePager
import com.xjw.qalbum.ui.components.PreviewAction
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import org.koin.androidx.compose.koinViewModel

/**
 * 标签管理页
 *
 * - 一级：所有标签列表（AI 标签 + 「用户保留」），带活跃图片数，可按名搜索筛选。
 * - 二级：点某标签 → 该标签下图片**按时间分组**的网格（布局与图库页一致：日期表头、共用列数、可捏合调列）；
 *   长按全屏预览（与审核页一致：左右滑动、缩放、详情、保存、分享），并可「收藏 / 取消标签」。
 *   点选子集后「移入回收站 已选 N」/批量「取消标签」，未选时「移入回收站·全部 N」（按 tag 分块全删）。
 */
@Composable
fun TagsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: TagsViewModel = koinViewModel(),
) {
    val tags by viewModel.tags.collectAsState()
    val progress by viewModel.progress.collectAsState()
    var selected by remember { mutableStateOf<TagDao.TagWithCount?>(null) }

    // 列表数据刷新后，同步被选中标签的最新计数（删完一部分后数字会变）
    val activeTag = remember(selected, tags) {
        selected?.let { sel -> tags.firstOrNull { it.id == sel.id } }
    }

    BackHandler(enabled = selected != null) { selected = null }

    if (activeTag == null) {
        TagListContent(
            tags = tags,
            contentPadding = contentPadding,
            onBack = onBack,
            onSelect = { selected = it },
        )
    } else {
        TagImagesContent(
            tag = activeTag,
            contentPadding = contentPadding,
            viewModel = viewModel,
            onBack = { selected = null },
        )
    }

    progress?.let { PurgeProgressDialog(it) }
}

@Composable
private fun TagListContent(
    tags: List<TagDao.TagWithCount>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSelect: (TagDao.TagWithCount) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(tags, query) {
        if (query.isBlank()) tags else tags.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("标签", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding()),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索标签") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (tags.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Label,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("还没有标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "开启并运行「AI 标签」分析，或在审核时「保留」图片后，标签会显示在这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filtered.forEach { tag ->
                        item(key = tag.id) {
                            TagRow(tag = tag, onClick = { onSelect(tag) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagRow(tag: TagDao.TagWithCount, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tag.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tag.type == "risk") {
                TagBadge("敏感", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.size(8.dp))
            } else if (tag.type == "user") {
                TagBadge("保留", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.size(8.dp))
            }
            Text(
                "${tag.count}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TagBadge(text: String, container: Color, onContainer: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = onContainer)
    }
}

@Composable
private fun TagImagesContent(
    tag: TagDao.TagWithCount,
    contentPadding: PaddingValues,
    viewModel: TagsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val busy by viewModel.busy.collectAsState()
    val columns by viewModel.galleryColumns.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val timeline = remember(tag.id) { viewModel.timelineFor(tag.id) }.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    var selectedIds by remember(tag.id) { mutableStateOf(setOf<Long>()) }
    var preview by remember { mutableStateOf<ImageEntity?>(null) }
    var showConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 4.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            tag.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (selectedIds.isEmpty()) "共 ${tag.count} 张 · 点选可只删部分" else "已选 ${selectedIds.size} 张",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selectedIds.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val ids = selectedIds
                                selectedIds = emptySet()
                                viewModel.removeTagBulk(tag, ids) { count ->
                                    Toast.makeText(context, "已取消标签 $count 张", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !busy,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.LabelOff, contentDescription = "取消标签")
                        }
                    }
                    FilledTonalButton(
                        onClick = { showConfirm = true },
                        enabled = !busy && tag.count > 0,
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (selectedIds.isEmpty()) "回收站·全部" else "回收站 ${selectedIds.size}")
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding())
                .tagPinchColumns(columns) { viewModel.setColumns(it) },
            contentPadding = PaddingValues(
                start = 1.5.dp,
                end = 1.5.dp,
                top = 2.dp,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalArrangement = Arrangement.spacedBy(1.5.dp),
        ) {
            items(
                count = timeline.itemCount,
                key = { index ->
                    when (val item = timeline.peek(index)) {
                        is GalleryTimelineItem.Header -> item.key
                        is GalleryTimelineItem.Photo -> item.image.id
                        null -> "loading-$index"
                    }
                },
                span = { index ->
                    if (timeline.peek(index) is GalleryTimelineItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                },
            ) { index ->
                when (val item = timeline[index]) {
                    is GalleryTimelineItem.Header -> TagSectionHeader(item.label)
                    is GalleryTimelineItem.Photo -> TagImageTile(
                        image = item.image,
                        selected = item.image.id in selectedIds,
                        selectionMode = selectedIds.isNotEmpty(),
                        onToggle = {
                            val id = item.image.id
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        },
                        onPreview = { preview = item.image },
                    )
                    null -> Unit
                }
            }

            if (timeline.loadState.append is LoadState.Loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    preview?.let { startImage ->
        val photos = timeline.itemSnapshotList.items
            .filterIsInstance<GalleryTimelineItem.Photo>()
            .map { it.image }
        val start = photos.indexOfFirst { it.id == startImage.id }
        if (photos.isEmpty() || start < 0) {
            LaunchedEffect(startImage.id) { preview = null }
        } else {
            FullScreenImagePager(
                images = photos,
                startIndex = start,
                onClose = { preview = null },
                onShare = { id -> viewModel.shareImage(id) { t -> context.startActivity(shareChooser(t)) } },
                exportSubdirs = exportSubdirs,
                onCreateSubdir = viewModel::createSubdir,
                onSave = { id, subdir, alsoTrash ->
                    viewModel.saveImage(id, subdir, alsoTrash) { ok ->
                        Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                    }
                    if (alsoTrash) preview = null
                },
                onLoadOcr = viewModel::getOcr,
            ) { current ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val isFav = current.isFavorite
                    PreviewAction(
                        icon = if (isFav) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        label = if (isFav) "已收藏" else "收藏",
                        onClick = { viewModel.setFavorite(current.id, !isFav) },
                    )
                    PreviewAction(
                        icon = Icons.AutoMirrored.Outlined.LabelOff,
                        label = "取消标签",
                        onClick = {
                            viewModel.removeTag(tag, current.id) {
                                Toast.makeText(context, "已取消标签", Toast.LENGTH_SHORT).show()
                            }
                            preview = null
                        },
                    )
                }
            }
        }
    }

    if (showConfirm) {
        val count = if (selectedIds.isEmpty()) tag.count else selectedIds.size
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("移入回收站？") },
            text = {
                Text(
                    if (selectedIds.isEmpty()) {
                        "将把标签「${tag.name}」下全部 $count 张图片移入回收站，之后仍可在回收站恢复。"
                    } else {
                        "将把所选 $count 张图片移入回收站，之后仍可在回收站恢复。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    val ids = selectedIds
                    selectedIds = emptySet()
                    viewModel.moveToTrash(tag, ids) { moved ->
                        Toast.makeText(context, "已移入回收站 $moved 张", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("移入回收站") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TagSectionHeader(label: String) {
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

/** 标签图格：与图库时间线一致（Crop、选中缩放圆角、收藏角标）；点击勾选、长按全屏预览。 */
@Composable
private fun TagImageTile(
    image: ImageEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    val selectedScale by animateFloatAsState(
        targetValue = if (selected) 0.88f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tagSelectScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = selectedScale
                scaleY = selectedScale
            }
            .clip(RoundedCornerShape(if (selected) 8.dp else 0.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onToggle, onLongClick = onPreview),
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

/** 网格双指捏合调列数（与图库一致）：放大减列、缩小加列，落盘由设置层 clamp 到 2–6 列。 */
private fun Modifier.tagPinchColumns(columns: Int, onColumns: (Int) -> Unit): Modifier =
    this.pointerInput(columns) {
        awaitEachGesture {
            var startDistance = 0f
            var changed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size < 2) break
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                if (startDistance == 0f) startDistance = distance
                val ratio = if (startDistance == 0f) 1f else distance / startDistance
                if (!changed) {
                    when {
                        ratio > 1.16f -> { onColumns(columns - 1); changed = true }
                        ratio < 0.86f -> { onColumns(columns + 1); changed = true }
                    }
                }
            }
        }
    }
