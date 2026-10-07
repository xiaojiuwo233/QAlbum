package com.xjw.qalbum.ui.cleanup

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.service.cleanup.CleanupRules
import com.xjw.qalbum.service.cleanup.ExternalViewTarget
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.imageMeta
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.FloatingActionToolbar
import com.xjw.qalbum.ui.components.FullScreenImagePager
import com.xjw.qalbum.ui.components.LoadingState
import com.xjw.qalbum.ui.components.PreviewAction
import com.xjw.qalbum.ui.components.ToolbarIconAction
import com.xjw.qalbum.ui.components.ToolbarPrimaryAction
import com.xjw.qalbum.ui.imageloading.ImageEntityModel

/** 审核网格页（折叠重复组）：全屏、隐藏底部导航栏。 */
@Composable
internal fun CleanupReviewScreen(
    contentPadding: PaddingValues,
    viewModel: CleanupViewModel,
    gridState: LazyGridState,
    category: String?,
    sort: ReviewSort,
    columns: Int,
    onCategoryChange: (String?) -> Unit,
    onSortChange: (ReviewSort) -> Unit,
    onColumnsChange: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenGroup: (Long) -> Unit,
) {
    val context = LocalContext.current
    val review by viewModel.reviewBatch.collectAsState()
    val selectedIds by viewModel.selectedReviewDeleteIds.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val purgeProgress by viewModel.purgeProgress.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()

    val items = review?.items.orEmpty()
    var showConfirm by remember { mutableStateOf(false) }
    var showKeepConfirm by remember { mutableStateOf(false) }
    var previewId by remember { mutableStateOf<Long?>(null) }

    val countByReason = remember(items) { items.groupingBy { itemReason(it) }.eachCount() }
    val filteredItems = remember(items, category) {
        if (category == null) items else items.filter { itemReason(it) == category }
    }
    val visibleItems = remember(filteredItems, sort) { sortReviewItems(filteredItems, sort) }
    // selectedBytes 是 O(已选数) 的 map 求和（sizeById 在批次内懒建一次）
    val selectedBytes = remember(review, selectedIds) { review?.selectedBytes(selectedIds) ?: 0L }

    Scaffold(
        topBar = {
            ReviewTopBar(
                selectedCount = selectedIds.size,
                selectedBytes = selectedBytes,
                totalItems = items.size,
                category = category,
                countByReason = countByReason,
                sort = sort,
                onBack = onBack,
                onSelectCategory = onCategoryChange,
                onSelectSort = onSortChange,
                onSelectAllVisible = { viewModel.selectReviewIds(deletableIds(visibleItems)) },
                onClear = viewModel::clearReviewSelection,
                onRestoreSuggested = viewModel::restoreSuggestedSelection,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        Box(Modifier.fillMaxSize().padding(top = topPadding.calculateTopPadding())) {
            if (items.isEmpty()) {
                // 装载中（生成批次后台读图）显示加载指示器，读完仍为空才提示
                if (busy) {
                    LoadingState(label = "正在整理审核清单…")
                } else {
                    EmptyState(
                        icon = Icons.Outlined.TaskAlt,
                        title = "没有需要审核的图片",
                        message = "可以回到清理页换一个策略重新生成清单。",
                    )
                }
            } else {
                val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .reviewPinchColumns(columns) { onColumnsChange(it) },
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomInset + 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "notice") {
                        InfoBanner("QQ 缓存尚未核对聊天引用，移入回收站也可能影响 QQ 里回看这些图片。默认不勾选，请逐项确认。")
                    }
                    items(visibleItems, key = { it.itemKey }) { item ->
                        when (item) {
                            is ReviewItem.Single -> SingleTile(
                                item = item,
                                selected = item.image.id in selectedIds,
                                onToggle = { viewModel.setReviewItemSelected(item.image.id, item.image.id !in selectedIds) },
                                onLongPress = { previewId = item.image.id },
                                modifier = Modifier.animateItem(),
                            )
                            is ReviewItem.Group -> GroupTile(
                                item = item,
                                selectedCount = item.members.count { it.id in selectedIds },
                                onOpen = { onOpenGroup(item.groupId) },
                                onLongPress = { viewModel.selectReviewIds(item.members.map { m -> m.id }) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
                // 「按推荐勾选」等后台计算时的加载指示（清理有自己的进度对话框）
                if (busy && purgeProgress == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.ContainedLoadingIndicator()
                    }
                }
            }

            FloatingActionToolbar(
                visible = selectedIds.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ToolbarIconAction(Icons.Outlined.Bookmark, "保留（不再出现在审核中）", enabled = !busy, onClick = { showKeepConfirm = true })
                ToolbarPrimaryAction(
                    icon = Icons.Outlined.Delete,
                    label = "移入回收站 · ${formatBytes(selectedBytes)}",
                    enabled = !busy,
                    onClick = { showConfirm = true },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            }
        }
    }

    previewId?.let { id ->
        val singles = visibleItems.filterIsInstance<ReviewItem.Single>().map { it.image }
        val start = singles.indexOfFirst { it.id == id }.coerceAtLeast(0)
        ReviewPreviewPager(
            images = singles,
            startIndex = start,
            selectedIds = selectedIds,
            busy = busy,
            onClose = { previewId = null },
            onToggleSelect = { viewModel.setReviewItemSelected(it, it !in selectedIds) },
            onFavorite = { iid, fav -> viewModel.setFavorite(iid, fav) },
            onKeep = { viewModel.keepImage(it); previewId = null },
            onTrash = { viewModel.trashOne(it); previewId = null },
            onShare = { iid -> viewModel.shareImage(iid) { t -> context.startActivity(shareChooser(t)) } },
            exportSubdirs = exportSubdirs,
            onCreateSubdir = viewModel::createSubdir,
            onSave = { iid, subdir, alsoTrash ->
                viewModel.saveImage(iid, subdir, alsoTrash) { ok ->
                    Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                }
                if (alsoTrash) previewId = null
            },
            onLoadOcr = viewModel::getOcr,
        )
    }

    if (showConfirm) {
        ConfirmDialog(
            icon = Icons.Outlined.Delete,
            title = "移入回收站？",
            text = "将清理 ${selectedIds.size} 张，共 ${formatBytes(selectedBytes)}，在回收站保留期内可恢复。\n\n" +
                "回收站位于应用专属目录，卸载或清除应用数据会让其中的图片永久丢失。",
            confirmLabel = "移入回收站",
            destructive = true,
            onDismiss = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                viewModel.applyReviewSelection { count ->
                    Toast.makeText(context, "已移入回收站 $count 张，可继续审核", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (showKeepConfirm) {
        ConfirmDialog(
            icon = Icons.Outlined.Bookmark,
            title = "保留这些图片？",
            text = "所选 ${selectedIds.size} 张会标记为「保留」，之后不再出现在审核清单里，也不会被任何清理流程删除。",
            confirmLabel = "保留",
            onDismiss = { showKeepConfirm = false },
            onConfirm = {
                showKeepConfirm = false
                viewModel.keepSelected { count ->
                    Toast.makeText(context, "已保留 $count 张", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    // 清理进度模态由 CleanupScreen 根部统一展示（重建后落回主页也不丢屏蔽）
}

@Composable
private fun ReviewTopBar(
    selectedCount: Int,
    selectedBytes: Long,
    totalItems: Int,
    category: String?,
    countByReason: Map<String, Int>,
    sort: ReviewSort,
    onBack: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectSort: (ReviewSort) -> Unit,
    onSelectAllVisible: () -> Unit,
    onClear: () -> Unit,
    onRestoreSuggested: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            TopAppBar(
                title = { Text("审核清单") },
                subtitle = {
                    Text(
                        if (selectedCount > 0) "已选 $selectedCount 张 · ${formatBytes(selectedBytes)}" else "点图勾选，长按预览，重复组点开挑选",
                    )
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    ReviewSortButton(sort = sort, onSelectSort = onSelectSort)
                    Box {
                        IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (category == null) "全选" else "全选「$category」") },
                                leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                                onClick = { onSelectAllVisible(); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("按推荐勾选") },
                                leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                                onClick = { onRestoreSuggested(); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("全不选") },
                                leadingIcon = { Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null) },
                                onClick = { onClear(); menuOpen = false },
                            )
                        }
                    }
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = category == null,
                    onClick = { onSelectCategory(null) },
                    label = { Text("全部 $totalItems") },
                )
                countByReason.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
                    FilterChip(
                        selected = category == reason,
                        onClick = { onSelectCategory(reason) },
                        label = { Text("$reason $count") },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewSortButton(sort: ReviewSort, onSelectSort: (ReviewSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Outlined.SwapVert, contentDescription = "排序：${sort.label}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReviewSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    onClick = { onSelectSort(s); open = false },
                    trailingIcon = if (s == sort) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 页面顶部的提示条（色调容器 + 图标） */
@Composable
internal fun InfoBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 审核图块的公共外观：圆角、选中时轻微缩小并叠加错误色 */
@Composable
private fun ReviewTileFrame(
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        if (selected) 0.94f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "reviewScale",
    )
    val overlay by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.error.copy(alpha = 0.22f) else Color.Transparent,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "reviewOverlay",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        content()
        Box(Modifier.matchParentSize().background(overlay))
    }
}

/** 单图审核格：点击切换勾选、长按全屏预览 */
@Composable
private fun SingleTile(
    item: ReviewItem.Single,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ReviewTileFrame(selected = selected, onClick = onToggle, onLongClick = onLongPress, modifier = modifier) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(ImageEntityModel(item.image)).crossfade(true).build(),
            contentDescription = item.image.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        TileBadge(text = item.reason, modifier = Modifier.align(Alignment.BottomStart))
        // 保护图（用户保留 / 已收藏）：明确标识且不可勾选（VM 侧同样拦截）
        val protection = protectionLabel(item.image)
        if (protection != null) {
            TileBadge(text = protection, modifier = Modifier.align(Alignment.TopEnd))
        } else {
            DeleteMark(selected = selected, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/** 重复组折叠格：代表图（建议保留的那张）+「重复 ×N」+「已选 M」，点击进入组详情 */
@Composable
private fun GroupTile(
    item: ReviewItem.Group,
    selectedCount: Int,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keeper = item.members.firstOrNull { it.id == item.keeperId } ?: item.members.first()
    ReviewTileFrame(selected = false, onClick = onOpen, onLongClick = onLongPress, modifier = modifier) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(ImageEntityModel(keeper)).crossfade(true).build(),
            contentDescription = "重复组 ${item.groupId}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        TileBadge(text = "重复 ×${item.members.size}", modifier = Modifier.align(Alignment.BottomStart))
        val badgeColor by animateColorAsState(
            if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.5f),
            label = "groupBadge",
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .clip(CircleShape)
                .background(badgeColor)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                if (selectedCount > 0) "已选 $selectedCount" else "点开挑选",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun TileBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(5.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 删除勾选标记：错误色圆形，勾选时对勾弹性出现 */
@Composable
private fun DeleteMark(selected: Boolean, modifier: Modifier = Modifier) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.28f),
        label = "deleteMark",
    )
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(24.dp)
            .clip(CircleShape)
            .background(fill)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.error else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = selected, enter = scaleIn(spatial) + fadeIn(), exit = scaleOut(spatial) + fadeOut()) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * 重复组详情页：组内逐张勾选 / 预览，支持「全部保留 / 仅留 1 张 / 全部删除」。
 *
 * 进入时固定一份成员快照：之后在预览里逐张「保留 / 清理」导致组缩减甚至消失，本页仍按快照展示——
 * 已处理的压暗标记、未处理的可继续操作，避免「处理一张后整组消失、另一张脱离审核流」。
 */
@Composable
fun CleanupReviewGroupScreen(
    groupId: Long,
    viewModel: CleanupViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val review by viewModel.reviewBatch.collectAsState()
    val selectedIds by viewModel.selectedReviewDeleteIds.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    // 组缩到只剩 1 张时会被转成单图、liveGroup 随之变 null；用进入时的成员快照兜住页面展示
    val liveGroup = review?.items?.filterIsInstance<ReviewItem.Group>()?.firstOrNull { it.groupId == groupId }
    var snapshot by remember(groupId) { mutableStateOf<List<ImageEntity>?>(null) }
    LaunchedEffect(groupId, liveGroup) {
        if (snapshot == null && liveGroup != null) snapshot = liveGroup.members
    }

    // reviewBatch 里仍在审核的实体：快照叠加 live 实体，收藏 / 保留状态变更才能即时反映
    val liveEntities: Map<Long, ImageEntity> = remember(review) {
        buildMap {
            review?.items?.forEach { item ->
                when (item) {
                    is ReviewItem.Single -> put(item.image.id, item.image)
                    is ReviewItem.Group -> item.members.forEach { put(it.id, it) }
                }
            }
        }
    }
    val liveIds: Set<Long> = liveEntities.keys
    val members = (snapshot ?: liveGroup?.members ?: emptyList()).map { liveEntities[it.id] ?: it }

    var previewId by remember { mutableStateOf<Long?>(null) }
    var showSplitDialog by remember { mutableStateOf(false) }

    if (members.isEmpty()) {
        EmptyState(icon = Icons.Outlined.TaskAlt, title = "这组重复图片已处理完")
        return
    }

    val keeperId = liveGroup?.keeperId
    val pendingMembers = members.filter { it.id in liveIds }
    val pendingIds = pendingMembers.map { it.id }
    // 「仅留 1 张」保留目标：优先系统建议保留图，未知时取第一张待处理图；其余为待删除集合
    val keepOneId = keeperId ?: pendingIds.firstOrNull()
    val keepOneDeleteIds = pendingIds.filter { it != keepOneId }
    val selectedInGroup = pendingMembers.count { it.id in selectedIds }
    val processedCount = members.size - pendingMembers.size
    val allProcessed = pendingMembers.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("重复组") },
                subtitle = {
                    Text(
                        buildString {
                            append("共 ${members.size} 张")
                            if (processedCount > 0) append(" · 已处理 $processedCount")
                            append(if (allProcessed) " · 已全部处理" else " · 已选 $selectedInGroup")
                        },
                    )
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    // 差异过大不是真重复时，可拆分本组并永久排斥（重新查重不再聚到一起）
                    TextButton(onClick = { showSplitDialog = true }, enabled = !busy && !allProcessed) {
                        Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("不是重复")
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.deselectReviewIds(pendingIds) },
                        enabled = !allProcessed,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f),
                    ) { Text("全部保留") }
                    OutlinedButton(
                        onClick = {
                            // 全选态下也要真正「只留一张」：先勾上其余、再取消保留图的勾选
                            viewModel.selectReviewIds(keepOneDeleteIds)
                            keepOneId?.let { viewModel.deselectReviewIds(listOf(it)) }
                        },
                        enabled = !allProcessed && keepOneDeleteIds.isNotEmpty(),
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f),
                    ) { Text("仅留 1 张") }
                    FilledTonalButton(
                        onClick = { viewModel.selectReviewIds(pendingIds) },
                        enabled = !allProcessed,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f),
                    ) { Text("全部删除") }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "hint") {
                InfoBanner("点图勾选要删除的副本，长按查看大图。「建议保留」是画质最好的一张。")
            }
            items(members, key = { it.id }) { member ->
                val processed = member.id !in liveIds
                GroupMemberTile(
                    image = member,
                    isKeeper = member.id == keeperId,
                    selected = member.id in selectedIds,
                    processed = processed,
                    protection = protectionLabel(member),
                    onToggle = { viewModel.setReviewItemSelected(member.id, member.id !in selectedIds) },
                    onLongPress = { if (!processed) previewId = member.id },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    previewId?.let { id ->
        // 预览只在「仍待处理」的成员间滑动
        val start = pendingMembers.indexOfFirst { it.id == id }
        if (start < 0) {
            LaunchedEffect(id) { previewId = null }
        } else {
            ReviewPreviewPager(
                images = pendingMembers,
                startIndex = start,
                selectedIds = selectedIds,
                busy = busy,
                onClose = { previewId = null },
                onToggleSelect = { viewModel.setReviewItemSelected(it, it !in selectedIds) },
                onFavorite = { iid, fav -> viewModel.setFavorite(iid, fav) },
                onKeep = { viewModel.keepImage(it); previewId = null },
                onTrash = { viewModel.trashOne(it); previewId = null },
                onShare = { iid -> viewModel.shareImage(iid) { t -> context.startActivity(shareChooser(t)) } },
                exportSubdirs = exportSubdirs,
                onCreateSubdir = viewModel::createSubdir,
                onSave = { iid, subdir, alsoTrash ->
                    viewModel.saveImage(iid, subdir, alsoTrash) { ok ->
                        Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                    }
                    if (alsoTrash) previewId = null
                },
                onLoadOcr = viewModel::getOcr,
            )
        }
    }

    if (showSplitDialog) {
        ConfirmDialog(
            icon = Icons.AutoMirrored.Outlined.CallSplit,
            title = "这组不是重复图片？",
            text = "拆分后这些图片会从重复组中移除，重新查重时也不会再被归到一起，同时帮助查重算法学习你的判断。",
            confirmLabel = "拆分",
            onDismiss = { showSplitDialog = false },
            onConfirm = {
                showSplitDialog = false
                viewModel.splitDuplicateGroup(groupId) { onBack() }
            },
        )
    }
}

@Composable
private fun GroupMemberTile(
    image: ImageEntity,
    isKeeper: Boolean,
    selected: Boolean,
    processed: Boolean,
    protection: String?,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ReviewTileFrame(
        selected = selected && !processed,
        onClick = { if (!processed && protection == null) onToggle() },
        onLongClick = onLongPress,
        modifier = modifier,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(ImageEntityModel(image)).crossfade(true).build(),
            contentDescription = image.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        when {
            processed -> {
                // 已处理（已清理 / 已保留）：压暗 + 角标，不再参与勾选
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)))
                TileBadge(text = "已处理", modifier = Modifier.align(Alignment.TopEnd))
            }
            protection != null -> TileBadge(text = protection, modifier = Modifier.align(Alignment.TopEnd))
            else -> {
                // 建议标签放右上（醒目），勾选标记在左上，尺寸信息在左下，互不重叠
                TileBadge(text = if (isKeeper) "建议保留" else "建议删除", modifier = Modifier.align(Alignment.TopEnd))
                DeleteMark(selected = selected, modifier = Modifier.align(Alignment.TopStart))
            }
        }
        TileBadge(text = imageMeta(image), modifier = Modifier.align(Alignment.BottomStart))
    }
}

/**
 * 审核预览：左右滑动、缩放；顶部「时间 + 详情 + 保存 + 分享」；底部「收藏 / 勾选 / 清理 / 保留」。
 * 清理 / 保留会把该图移出审核并关闭预览。
 */
@Composable
internal fun ReviewPreviewPager(
    images: List<ImageEntity>,
    startIndex: Int,
    selectedIds: Set<Long>,
    busy: Boolean,
    onClose: () -> Unit,
    onToggleSelect: (Long) -> Unit,
    onFavorite: (Long, Boolean) -> Unit,
    onKeep: (Long) -> Unit,
    onTrash: (Long) -> Unit,
    onShare: (Long) -> Unit,
    exportSubdirs: List<String>,
    onCreateSubdir: (String) -> Unit,
    onSave: (id: Long, subdir: String?, alsoTrash: Boolean) -> Unit,
    onLoadOcr: suspend (Long) -> OcrTextEntity?,
) {
    FullScreenImagePager(
        images = images,
        startIndex = startIndex,
        onClose = onClose,
        onShare = onShare,
        exportSubdirs = exportSubdirs,
        onCreateSubdir = onCreateSubdir,
        onSave = onSave,
        onLoadOcr = onLoadOcr,
    ) { current ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isFav = current.isFavorite
            val isSelected = current.id in selectedIds
            val isProtected = isProtectedImage(current)
            PreviewAction(
                icon = if (isFav) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                label = if (isFav) "已收藏" else "收藏",
                onClick = { onFavorite(current.id, !isFav) },
            )
            // 保护图（用户保留 / 已收藏）不可勾入删除集；取消收藏后恢复可勾选
            PreviewAction(
                icon = if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                label = if (isProtected) "已保护" else if (isSelected) "已勾选" else "勾选",
                enabled = !isProtected,
                onClick = { onToggleSelect(current.id) },
            )
            PreviewAction(
                icon = Icons.Outlined.Delete,
                label = "清理",
                enabled = !busy,
                onClick = { onTrash(current.id) },
            )
            PreviewAction(
                icon = Icons.Outlined.Bookmark,
                label = "保留",
                onClick = { onKeep(current.id) },
            )
        }
    }
}

/** 分享选择器：把缓存好的临时文件（带后缀）以 content Uri 发送给其它 App */
internal fun shareChooser(target: ExternalViewTarget): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = target.mimeType
            putExtra(Intent.EXTRA_STREAM, target.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        "分享图片",
    )

/** 审核网格排序方式 */
internal enum class ReviewSort(val label: String) {
    RECOMMENDED("推荐优先"),
    DATE_DESC("时间：新→旧"),
    DATE_ASC("时间：旧→新"),
    SIZE_DESC("大小：大→小"),
    COUNT_DESC("数量：多→少"),
}

private fun sortReviewItems(items: List<ReviewItem>, sort: ReviewSort): List<ReviewItem> = when (sort) {
    // 推荐项优先（稳定排序：推荐内部保持模型概率序，其余维持原序）
    ReviewSort.RECOMMENDED -> items.sortedByDescending { itemReason(it) == CleanupRules.REASON_RECOMMENDED }
    ReviewSort.DATE_DESC -> items.sortedByDescending { itemMtime(it) }
    ReviewSort.DATE_ASC -> items.sortedBy { itemMtime(it) }
    ReviewSort.SIZE_DESC -> items.sortedByDescending { itemSize(it) }
    ReviewSort.COUNT_DESC -> items.sortedByDescending { itemCount(it) }
}

/** 审核项「数量」：单图按 1、重复组按成员数（「数量多→少」用于优先清理大重复组） */
private fun itemCount(item: ReviewItem): Int = when (item) {
    is ReviewItem.Single -> 1
    is ReviewItem.Group -> item.members.size
}

private fun itemMtime(item: ReviewItem): Long = when (item) {
    is ReviewItem.Single -> item.image.mtime
    is ReviewItem.Group -> (item.members.firstOrNull { it.id == item.keeperId } ?: item.members.first()).mtime
}

/** 单图按自身大小、重复组按组内总大小（更能反映清理收益）排序 */
private fun itemSize(item: ReviewItem): Long = when (item) {
    is ReviewItem.Single -> item.image.size
    is ReviewItem.Group -> item.members.sumOf { it.size }
}

/** 审核项归属的筛选分类名：单图用维度，重复组统一为「重复」 */
private fun itemReason(item: ReviewItem): String = when (item) {
    is ReviewItem.Single -> item.reason
    is ReviewItem.Group -> CleanupRules.REASON_DUPLICATE
}

/** 当前可见项里可删除的图片 id：单图本身 + 重复组内非保留图；保护（user_keep / 收藏）图排除 */
private fun deletableIds(items: List<ReviewItem>): List<Long> = items.flatMap { item ->
    when (item) {
        is ReviewItem.Single -> if (isProtectedImage(item.image)) emptyList() else listOf(item.image.id)
        is ReviewItem.Group ->
            item.members.filter { it.id != item.keeperId && !isProtectedImage(it) }.map { it.id }
    }
}

/** 审核网格双指捏合调列数（与图库一致）：放大减列、缩小加列，范围 2–6 列 */
private fun Modifier.reviewPinchColumns(columns: Int, onColumns: (Int) -> Unit): Modifier =
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
                        ratio > 1.16f -> { onColumns((columns - 1).coerceIn(2, 6)); changed = true }
                        ratio < 0.86f -> { onColumns((columns + 1).coerceIn(2, 6)); changed = true }
                    }
                }
            }
        }
    }
