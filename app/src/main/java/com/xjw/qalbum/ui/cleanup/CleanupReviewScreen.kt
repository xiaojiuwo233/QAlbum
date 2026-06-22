package com.xjw.qalbum.ui.cleanup

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.ui.components.SaveToAlbumSheet
import com.xjw.qalbum.ui.detail.PhotoDetailsSheet
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector
import com.xjw.qalbum.service.cleanup.ExternalViewTarget
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.service.cleanup.CleanupRules
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
    var menuOpen by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showKeepConfirm by remember { mutableStateOf(false) }
    var previewId by remember { mutableStateOf<Long?>(null) }

    val countByReason = remember(items) {
        items.groupingBy { itemReason(it) }.eachCount()
    }
    val filteredItems = remember(items, category) {
        if (category == null) items else items.filter { itemReason(it) == category }
    }
    val visibleItems = remember(filteredItems, sort) { sortReviewItems(filteredItems, sort) }
    val selectedBytes = review?.selectedBytes(selectedIds) ?: 0L

    Scaffold(
        topBar = {
            ReviewTopBar(
                selectedCount = selectedIds.size,
                selectedBytes = selectedBytes,
                totalItems = items.size,
                category = category,
                countByReason = countByReason,
                sort = sort,
                menuOpen = menuOpen,
                onBack = onBack,
                onSelectCategory = onCategoryChange,
                onSelectSort = onSortChange,
                onMenuOpenChange = { menuOpen = it },
                onSelectAllVisible = { viewModel.selectReviewIds(deletableIds(visibleItems)) },
                onClear = viewModel::clearReviewSelection,
                onRestoreSuggested = viewModel::restoreSuggestedSelection,
            )
        },
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                ReviewBottomBar(
                    selectedCount = selectedIds.size,
                    selectedBytes = selectedBytes,
                    busy = busy,
                    onApply = { showConfirm = true },
                    onKeep = { showKeepConfirm = true },
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topPadding.calculateTopPadding()),
                contentAlignment = Alignment.Center,
            ) {
                Text("没有需要审核的清理项", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding())
                .reviewPinchColumns(columns) { onColumnsChange(it) },
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = 10.dp,
                // 底部留出操作条高度（操作条在本页 Scaffold 的 bottomBar 里，含在 topPadding.bottom）
                bottom = topPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(visibleItems, key = { it.itemKey }) { item ->
                when (item) {
                    is ReviewItem.Single -> SingleTile(
                        item = item,
                        selected = item.image.id in selectedIds,
                        onToggle = { viewModel.setReviewItemSelected(item.image.id, item.image.id !in selectedIds) },
                        onLongPress = { previewId = item.image.id },
                    )

                    is ReviewItem.Group -> GroupTile(
                        item = item,
                        selectedCount = item.members.count { it.id in selectedIds },
                        onOpen = { onOpenGroup(item.groupId) },
                        onLongPress = { viewModel.selectReviewIds(item.members.map { m -> m.id }) },
                    )
                }
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
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("移入回收站？") },
            text = {
                Text(
                    "本次将清理 ${selectedIds.size} 张，共 ${formatBytes(selectedBytes)}。移入回收站后仍可恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    viewModel.applyReviewSelection { count ->
                        Toast.makeText(context, "已移入回收站 $count 张，可继续筛选", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("取消") } },
        )
    }

    if (showKeepConfirm) {
        AlertDialog(
            onDismissRequest = { showKeepConfirm = false },
            title = { Text("保留这些图片？") },
            text = { Text("将把选中的 ${selectedIds.size} 张标记为「保留」，之后不再出现在审核中。") },
            confirmButton = {
                TextButton(onClick = {
                    showKeepConfirm = false
                    viewModel.keepSelected { count ->
                        Toast.makeText(context, "已保留 $count 张", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保留") }
            },
            dismissButton = { TextButton(onClick = { showKeepConfirm = false }) { Text("取消") } },
        )
    }

    purgeProgress?.let { PurgeProgressDialog(it) }
}

@Composable
private fun ReviewTopBar(
    selectedCount: Int,
    selectedBytes: Long,
    totalItems: Int,
    category: String?,
    countByReason: Map<String, Int>,
    sort: ReviewSort,
    menuOpen: Boolean,
    onBack: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectSort: (ReviewSort) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    onSelectAllVisible: () -> Unit,
    onClear: () -> Unit,
    onRestoreSuggested: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("审核清理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (selectedCount > 0) "已选 $selectedCount 张 · ${formatBytes(selectedBytes)}" else "点图勾选，重复组点开挑选",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ReviewSortButton(sort = sort, onSelectSort = onSelectSort)
                Box {
                    IconButton(onClick = { onMenuOpenChange(true) }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                        DropdownMenuItem(
                            text = { Text(if (category == null) "全选" else "全选「$category」") },
                            onClick = { onSelectAllVisible(); onMenuOpenChange(false) },
                        )
                        DropdownMenuItem(
                            text = { Text("恢复建议预选") },
                            onClick = { onRestoreSuggested(); onMenuOpenChange(false) },
                        )
                        DropdownMenuItem(
                            text = { Text("全不选") },
                            onClick = { onClear(); onMenuOpenChange(false) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
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
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.SwapVert, contentDescription = "排序")
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

@Composable
private fun ReviewBottomBar(
    selectedCount: Int,
    selectedBytes: Long,
    busy: Boolean,
    onApply: () -> Unit,
    onKeep: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "已选 $selectedCount · ${formatBytes(selectedBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedButton(onClick = onKeep, enabled = selectedCount > 0 && !busy) {
                Icon(Icons.Outlined.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("保留")
            }
            FilledTonalButton(onClick = onApply, enabled = selectedCount > 0 && !busy) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("移入回收站")
            }
        }
    }
}

/** 单图审核格：点击切换勾选、长按全屏预览。 */
@Composable
private fun SingleTile(
    item: ReviewItem.Single,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(ImageEntityModel(item.image)).crossfade(true).build(),
            contentDescription = item.image.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.error.copy(alpha = 0.20f)))
        }
        TileBadge(text = item.reason, modifier = Modifier.align(Alignment.BottomStart))
        SelectionCircle(selected = selected, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** 重复组折叠格：代表图（保留图）+ 「重复×N」+「已选 M」角标，点击进入组详情。 */
@Composable
private fun GroupTile(
    item: ReviewItem.Group,
    selectedCount: Int,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    val keeper = item.members.firstOrNull { it.id == item.keeperId } ?: item.members.first()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(ImageEntityModel(keeper)).crossfade(true).build(),
            contentDescription = "重复组 ${item.groupId}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        TileBadge(text = "重复 ×${item.members.size}", modifier = Modifier.align(Alignment.BottomStart))
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.45f),
                )
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
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SelectionCircle(selected: Boolean, modifier: Modifier = Modifier) {
    Icon(
        if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription = null,
        modifier = modifier
            .padding(5.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.36f)),
        tint = Color.White,
    )
}

/**
 * 重复组详情页：组内逐张勾选/预览，支持「全部删除 / 仅保留 1 张 / 全部保留」。
 *
 * 进入时固定一份成员快照：之后在预览里逐张「保留 / 清理」导致组缩减甚至消失，本页仍按快照展示——
 * 已处理的灰显标记、未处理的可继续操作，直到整组处理完。
 * 避免「处理一张后整组消失、另一张却脱离审核流消失在图库里」。
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
    // 组缩到只剩 1 张时会被转成单图、liveGroup 随之变 null；用进入时的成员快照兜住页面展示。
    val liveGroup = review?.items?.filterIsInstance<ReviewItem.Group>()?.firstOrNull { it.groupId == groupId }
    var snapshot by remember(groupId) { mutableStateOf<List<ImageEntity>?>(null) }
    LaunchedEffect(groupId, liveGroup) {
        if (snapshot == null && liveGroup != null) snapshot = liveGroup.members
    }
    val members = snapshot ?: liveGroup?.members ?: emptyList()

    // reviewBatch 里仍在审核的 id（无论现在属于 Group 还是已被转成 Single）
    val liveIds: Set<Long> = remember(review) {
        buildSet {
            review?.items?.forEach { item ->
                when (item) {
                    is ReviewItem.Single -> add(item.image.id)
                    is ReviewItem.Group -> item.members.forEach { add(it.id) }
                }
            }
        }
    }

    var previewId by remember { mutableStateOf<Long?>(null) }
    var showSplitDialog by remember { mutableStateOf(false) }

    if (members.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("重复组已处理", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val keeperId = liveGroup?.keeperId
    val pendingMembers = members.filter { it.id in liveIds }   // 仍待处理（可勾选/预览）
    val pendingIds = pendingMembers.map { it.id }
    val pendingNonKeeperIds = pendingIds.filter { it != keeperId }
    val selectedInGroup = pendingMembers.count { it.id in selectedIds }
    val processedCount = members.size - pendingMembers.size
    val allProcessed = pendingMembers.isEmpty()

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text("重复组", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            buildString {
                                append("共 ${members.size} 张")
                                if (processedCount > 0) append(" · 已处理 $processedCount")
                                append(if (allProcessed) " · 已全部处理" else " · 已选 $selectedInGroup")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 差异过大不是真重复时，用户可拆分本组并永久排斥（重新查重不再聚到一起）
                    TextButton(onClick = { showSplitDialog = true }, enabled = !busy && !allProcessed) {
                        Text("拆分")
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { viewModel.deselectReviewIds(pendingIds) },
                        enabled = !allProcessed,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("全部保留")
                    }
                    OutlinedButton(
                        onClick = { viewModel.selectReviewIds(pendingNonKeeperIds) },
                        enabled = !allProcessed && pendingNonKeeperIds.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("仅留 1 张")
                    }
                    FilledTonalButton(
                        onClick = { viewModel.selectReviewIds(pendingIds) },
                        enabled = !allProcessed,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("全部删除")
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(top = topPadding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = 10.dp,
                bottom = topPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(members, key = { it.id }) { member ->
                val processed = member.id !in liveIds
                GroupMemberTile(
                    image = member,
                    isKeeper = member.id == keeperId,
                    selected = member.id in selectedIds,
                    processed = processed,
                    onToggle = { viewModel.setReviewItemSelected(member.id, member.id !in selectedIds) },
                    onLongPress = { if (!processed) previewId = member.id },
                )
            }
        }
    }

    previewId?.let { id ->
        // 预览只在「仍待处理」的成员间滑动；已处理的不进预览
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
        AlertDialog(
            onDismissRequest = { showSplitDialog = false },
            title = { Text("拆分重复组") },
            text = {
                Text(
                    "如果这一组图片差异过大、并非真正的重复，可以拆分它。\n\n" +
                        "拆分后这些图片会从重复中移除，且重新查重时不会再被聚到一起。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSplitDialog = false
                        viewModel.splitDuplicateGroup(groupId) { onBack() }
                    },
                ) { Text("确认拆分") }
            },
            dismissButton = {
                TextButton(onClick = { showSplitDialog = false }) { Text("取消") }
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
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = { if (!processed) onToggle() }, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(ImageEntityModel(image)).crossfade(true).build(),
            contentDescription = image.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (processed) {
            // 已处理（已清理 / 已保留）：压暗 + 角标，不再参与勾选
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)))
            TileBadge(text = "已处理", modifier = Modifier.align(Alignment.TopEnd))
        } else {
            if (selected) {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.error.copy(alpha = 0.22f)))
            }
            // 建议标签放右上（醒目），勾选圈移到左上，尺寸信息留左下，三者互不重叠
            TileBadge(
                text = if (isKeeper) "建议保留" else "建议删除",
                modifier = Modifier.align(Alignment.TopEnd),
            )
            SelectionCircle(selected = selected, modifier = Modifier.align(Alignment.TopStart))
        }
        Text(
            imageMeta(image),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 全屏预览：左右滑动切换上一张/下一张；顶部「时间 + 详情 + 保存 + 分享」；底部「收藏 / 勾选 / 清理 / 保留」。
 * [images] 是预览时的快照列表；清理 / 保留会把该图移出审核并关闭预览。
 * 「保存」导出到系统相册（Pictures/QAlbum），「详情」复用图库的元数据 + OCR 详情卡。
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
    if (images.isEmpty()) {
        onClose()
        return
    }
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { images.size }
    var detailsVisible by remember { mutableStateOf(false) }
    var showSaveSheet by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { images[it].id }) { page ->
                AsyncImage(
                    model = ImageRequest.Builder(context).data(ImageEntityModel(images[page])).crossfade(true).build(),
                    contentDescription = images[page].filename,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val current = images.getOrNull(pagerState.currentPage) ?: images.first()

            // 顶部：关闭 + 时间 + 详情 + 保存 + 分享
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(start = 4.dp, top = 6.dp, end = 4.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        formatImageTime(current.mtime),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(imageMeta(current), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.72f))
                }
                IconButton(onClick = { detailsVisible = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "详情", tint = Color.White)
                }
                IconButton(onClick = { showSaveSheet = true }) {
                    Icon(Icons.Outlined.Download, contentDescription = "保存", tint = Color.White)
                }
                IconButton(onClick = { onShare(current.id) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "分享", tint = Color.White)
                }
            }

            // 底部操作栏
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                    .navigationBarsPadding()
                    .padding(top = 28.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isFav = current.isFavorite
                val isSelected = current.id in selectedIds
                PreviewAction(
                    icon = if (isFav) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    label = if (isFav) "已收藏" else "收藏",
                    onClick = { onFavorite(current.id, !isFav) },
                )
                PreviewAction(
                    icon = if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    label = if (isSelected) "已勾选" else "勾选",
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

            // 「查看详情」：复用图库详情卡（元数据 + OCR），从底部滑入覆盖底部操作栏
            val ocr by produceState<OcrTextEntity?>(null, current.id, detailsVisible) {
                value = if (detailsVisible) onLoadOcr(current.id) else null
            }
            AnimatedVisibility(
                visible = detailsVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                PhotoDetailsSheet(
                    image = current,
                    ocr = ocr,
                    onClose = { detailsVisible = false },
                )
            }

            if (showSaveSheet) {
                SaveToAlbumSheet(
                    imageCount = 1,
                    subdirs = exportSubdirs,
                    onCreateSubdir = onCreateSubdir,
                    onDismiss = { showSaveSheet = false },
                    onConfirm = { subdir, alsoTrash ->
                        showSaveSheet = false
                        onSave(current.id, subdir, alsoTrash)
                    },
                )
            }
        }
    }
}

@Composable
private fun PreviewAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** 分享选择器：把缓存好的临时文件（带后缀）以 content Uri 发送给其它 App。 */
internal fun shareChooser(target: ExternalViewTarget): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = target.mimeType
            putExtra(Intent.EXTRA_STREAM, target.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        "分享图片",
    )

/** 审核网格排序方式（类似图库页）。 */
internal enum class ReviewSort(val label: String) {
    DATE_DESC("时间（新→旧）"),
    DATE_ASC("时间（旧→新）"),
    SIZE_DESC("大小（大→小）"),
    COUNT_DESC("数量（多→少）"),
}

private fun sortReviewItems(items: List<ReviewItem>, sort: ReviewSort): List<ReviewItem> = when (sort) {
    ReviewSort.DATE_DESC -> items.sortedByDescending { itemMtime(it) }
    ReviewSort.DATE_ASC -> items.sortedBy { itemMtime(it) }
    ReviewSort.SIZE_DESC -> items.sortedByDescending { itemSize(it) }
    ReviewSort.COUNT_DESC -> items.sortedByDescending { itemCount(it) }
}

/** 审核项「数量」：单图按 1、重复组按成员数 —— 用于「数量多→少」排序，优先清理大重复组。 */
private fun itemCount(item: ReviewItem): Int = when (item) {
    is ReviewItem.Single -> 1
    is ReviewItem.Group -> item.members.size
}

private fun itemMtime(item: ReviewItem): Long = when (item) {
    is ReviewItem.Single -> item.image.mtime
    is ReviewItem.Group -> (item.members.firstOrNull { it.id == item.keeperId } ?: item.members.first()).mtime
}

/** 单图按自身大小、重复组按组内总大小（更能反映清理收益）排序。 */
private fun itemSize(item: ReviewItem): Long = when (item) {
    is ReviewItem.Single -> item.image.size
    is ReviewItem.Group -> item.members.sumOf { it.size }
}

/** 审核项归属的筛选分类名：单图用维度，重复组统一为「重复」。 */
private fun itemReason(item: ReviewItem): String = when (item) {
    is ReviewItem.Single -> item.reason
    is ReviewItem.Group -> CleanupRules.REASON_DUPLICATE
}

/** 当前可见项里「可删除」的图片 id：单图本身 + 重复组内非保留图。 */
private fun deletableIds(items: List<ReviewItem>): List<Long> = items.flatMap { item ->
    when (item) {
        is ReviewItem.Single -> listOf(item.image.id)
        is ReviewItem.Group -> item.members.map { it.id }.filter { it != item.keeperId }
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
