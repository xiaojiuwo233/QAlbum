package com.xjw.qalbum.ui.tags

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.ui.cleanup.PurgeProgressDialog
import com.xjw.qalbum.ui.cleanup.shareChooser
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.DeleteImagesDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.FloatingActionToolbar
import com.xjw.qalbum.ui.components.FullScreenImagePager
import com.xjw.qalbum.ui.components.PhotoBadges
import com.xjw.qalbum.ui.components.PhotoGrid
import com.xjw.qalbum.ui.components.PreviewAction
import com.xjw.qalbum.ui.components.SearchField
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedDefaults
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.SelectionTopBar
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.ToolbarIconAction
import com.xjw.qalbum.ui.components.ToolbarPrimaryAction
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import org.koin.androidx.compose.koinViewModel

/**
 * 标签页
 *
 * - 一级：全部标签（AI 标签、敏感信息、「用户保留」），显示活跃图片数，可搜索。
 * - 二级：某标签下的图片按日期分组（与图库同一网格：捏合调列、长按多选并可拖动连续勾选）；
 *   单击全屏预览，可收藏 / 取消标签 / 删除；多选后批量取消标签或移入回收站，
 *   未选择时可把该标签下全部图片移入回收站（按块处理，不一次性装载）。
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

    // 列表刷新后同步选中标签的最新计数（删掉一部分后数字会变）
    val activeTag = remember(selected, tags) {
        selected?.let { sel -> tags.firstOrNull { it.id == sel.id } }
    }

    BackHandler(enabled = selected != null) { selected = null }

    val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = activeTag,
        contentKey = { it?.id },
        transitionSpec = { fadeIn(enterSpec) togetherWith fadeOut(exitSpec) },
        label = "tagsPage",
    ) { tag ->
        if (tag == null) {
            TagListContent(
                tags = tags,
                contentPadding = contentPadding,
                onBack = onBack,
                onSelect = { selected = it },
            )
        } else {
            TagImagesContent(
                tag = tag,
                contentPadding = contentPadding,
                viewModel = viewModel,
                onBack = { selected = null },
            )
        }
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("标签") },
                subtitle = { Text("${tags.size} 个标签") },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        if (tags.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.Label,
                title = "还没有标签",
                message = "开启并运行「AI 标签」分析，或在审核时「保留」图片后，标签会出现在这里。",
                modifier = Modifier.padding(top = topPadding.calculateTopPadding()),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = topPadding.calculateTopPadding() + 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(SegmentedDefaults.Gap),
        ) {
            item(key = "search") {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索标签",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (filtered.isEmpty()) {
                item(key = "none") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.SearchOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("没有匹配的标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            itemsIndexed(filtered, key = { _, tag -> tag.id }) { index, tag ->
                TagRow(
                    tag = tag,
                    position = SegmentPosition(index, filtered.size),
                    onClick = { onSelect(tag) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun TagRow(
    tag: TagDao.TagWithCount,
    position: SegmentPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, typeLabel) = when (tag.type) {
        "risk" -> Icons.Outlined.GppMaybe to "敏感信息"
        "user" -> Icons.Outlined.Bookmark to "用户保留"
        else -> Icons.AutoMirrored.Outlined.Label to "AI 标签"
    }
    val container = when (tag.type) {
        "risk" -> MaterialTheme.colorScheme.errorContainer
        "user" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when (tag.type) {
        "risk" -> MaterialTheme.colorScheme.onErrorContainer
        "user" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    SegmentedListItem(
        position = position,
        modifier = modifier,
        onClick = onClick,
        leading = { ShapeIcon(icon, containerColor = container, contentColor = content) },
        headline = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supporting = { Text(typeLabel) },
        trailing = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(
                    "${tag.count}",
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        },
    )
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
    var showTrashAll by remember { mutableStateOf(false) }
    var showDeleteSelected by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()
    val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn(enterSpec) togetherWith fadeOut(exitSpec) },
                label = "tagTopBar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(count = selectedIds.size, onClose = { selectedIds = emptySet() })
                } else {
                    TopAppBar(
                        navigationIcon = { BackButton(onBack) },
                        title = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        subtitle = { Text("${tag.count} 张 · 长按可多选") },
                        actions = {
                            TextButton(onClick = { showTrashAll = true }, enabled = !busy && tag.count > 0) {
                                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("全部移入回收站")
                            }
                        },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        Box(Modifier.fillMaxSize().padding(top = topPadding.calculateTopPadding())) {
            PhotoGrid(
                items = timeline,
                columns = columns,
                selectedIds = selectedIds,
                gridState = gridState,
                onOpen = { preview = it },
                onSelectionChange = { id, select -> selectedIds = if (select) selectedIds + id else selectedIds - id },
                onColumnsChange = viewModel::setColumns,
                badges = PhotoBadges(),
                contentPadding = contentPadding,
            )
            FloatingActionToolbar(visible = selectionMode, modifier = Modifier.align(Alignment.BottomCenter)) {
                ToolbarIconAction(Icons.AutoMirrored.Outlined.LabelOff, "取消标签", enabled = !busy, onClick = {
                    val ids = selectedIds
                    selectedIds = emptySet()
                    viewModel.removeTagBulk(tag, ids) { count ->
                        Toast.makeText(context, if (count > 0) "已取消标签 $count 张" else "取消标签失败", Toast.LENGTH_SHORT).show()
                    }
                })
                ToolbarPrimaryAction(Icons.Outlined.Delete, "移入回收站", enabled = !busy, onClick = { showDeleteSelected = true })
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
            var confirmDelete by remember { mutableStateOf<ImageEntity?>(null) }
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
                    PreviewAction(
                        icon = Icons.Outlined.Delete,
                        label = "删除",
                        enabled = !busy,
                        onClick = { confirmDelete = current },
                    )
                }
                confirmDelete?.let { pending ->
                    ConfirmDialog(
                        icon = Icons.Outlined.Delete,
                        title = "移入回收站？",
                        text = "这张图片会移入回收站，保留期内可恢复。收藏或标记保留的图片会被跳过。",
                        confirmLabel = "移入回收站",
                        destructive = true,
                        onDismiss = { confirmDelete = null },
                        onConfirm = {
                            confirmDelete = null
                            viewModel.trashOne(tag, pending.id) { ok ->
                                Toast.makeText(context, if (ok) "已移入回收站" else "未移动：图片受保护或文件访问失败", Toast.LENGTH_SHORT).show()
                                if (ok) {
                                    preview = null
                                    timeline.refresh()
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showDeleteSelected && selectedIds.isNotEmpty()) {
        val ids = selectedIds
        DeleteImagesDialog(
            count = ids.size,
            note = "收藏或标记保留的图片会被跳过。",
            onDismiss = { showDeleteSelected = false },
            onTrash = {
                showDeleteSelected = false
                selectedIds = emptySet()
                viewModel.moveToTrash(tag, ids) { moved ->
                    Toast.makeText(context, "已移入回收站 $moved 张", Toast.LENGTH_SHORT).show()
                }
            },
            onPermanent = null,
        )
    }

    if (showTrashAll) {
        ConfirmDialog(
            icon = Icons.Outlined.DeleteSweep,
            title = "全部移入回收站？",
            text = "将把标签「${tag.name}」下的全部 ${tag.count} 张图片移入回收站，保留期内可恢复。收藏或标记保留的图片会被跳过。",
            confirmLabel = "全部移入",
            destructive = true,
            onDismiss = { showTrashAll = false },
            onConfirm = {
                showTrashAll = false
                viewModel.moveToTrash(tag, emptySet()) { moved ->
                    Toast.makeText(context, "已移入回收站 $moved 张", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}
