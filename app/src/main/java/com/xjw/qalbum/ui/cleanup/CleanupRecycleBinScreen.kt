package com.xjw.qalbum.ui.cleanup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.TrashUnit
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.FloatingActionToolbar
import com.xjw.qalbum.ui.components.FullScreenImagePager
import com.xjw.qalbum.ui.components.PhotoBadges
import com.xjw.qalbum.ui.components.PhotoGrid
import com.xjw.qalbum.ui.components.PreviewAction
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SelectionTopBar
import com.xjw.qalbum.ui.components.ToolbarIconAction
import com.xjw.qalbum.ui.components.ToolbarPrimaryAction
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.sources.SourceSortMenuButton
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private val SelectedSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() })

/** 要确认的操作（永久删除与恢复都要确认） */
private enum class BinConfirm { PURGE, PURGE_ALL, DELETE_ORIGINALS, DELETE_ALL_ORIGINALS, RESTORE, RESTORE_ARCHIVE, RESTORE_ORIGINALS }

/** 看图时点了恢复（[archive] 为恢复并归档）或还原原图，等确认 */
private data class PendingRestore(val image: ImageEntity, val archive: Boolean)

/**
 * 回收站：移入回收站的图与压缩前的原图两类，网格同图库（日期按移入回收站的时间分组、标题带到期天数，可按大小排、
 * 按删除原因筛选；长按拖动多选、点标题选整组、捏合改列数）。选中后恢复、恢复并归档或永久删除（原图：还原或删除），
 * 点开单张也能处理，详情里有删除原因与到期时间。
 */
@Composable
fun CleanupRecycleBinScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: RecycleBinViewModel = koinViewModel(),
) {
    val items = viewModel.photos.collectAsLazyPagingItems()
    val content by viewModel.content.collectAsState()
    val reason by viewModel.reason.collectAsState()
    val reasons by viewModel.reasons.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val directory by viewModel.directory.collectAsState()
    val fastScroll by viewModel.fastScroll.collectAsState()
    val deletedStats by viewModel.deletedStats.collectAsState()
    val originalStats by viewModel.originalStats.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val conflict by viewModel.conflict.collectAsState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var selected by rememberSaveable(stateSaver = SelectedSaver) { mutableStateOf(setOf<Long>()) }
    var confirm by rememberSaveable { mutableStateOf<BinConfirm?>(null) }
    var preview by remember { mutableStateOf<Pair<List<ImageEntity>, Int>?>(null) }
    // 打开看图时网格里那些图的回收站记录（看图读回收站里的文件、详情里的删除信息）
    var previewUnits by remember { mutableStateOf(mapOf<Long, TrashUnit>()) }
    var pendingPurge by remember { mutableStateOf<ImageEntity?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    val originals = content == BinContent.ORIGINALS
    val stats = if (originals) originalStats else deletedStats
    // 筛选了删除原因时，全选、「已全选」按这一类的张数算
    val shownCount = if (!originals && reason != null) reasons.firstOrNull { it.label == reason }?.count ?: stats.count else stats.count
    val selecting = selected.isNotEmpty()
    val clearSelection = { selected = emptySet() }
    val selectAll: () -> Unit = { scope.launch { selected = viewModel.allIds() } }

    BackHandler(enabled = selecting) { clearSelection() }
    // 换类别、排序或筛选后回到顶部（旋转屏幕不算）
    val order = "$content/${sort.name}/$reason"
    var shownOrder by rememberSaveable { mutableStateOf(order) }
    LaunchedEffect(order) {
        if (order != shownOrder) {
            shownOrder = order
            gridState.scrollToItem(0)
        }
    }

    val topEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val topExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    // 顶栏固定为小顶栏：进入多选时高度不变，网格不跳，长按后拖动连选的位置才对得上
    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(topEnter) togetherWith fadeOut(topExit) },
                label = "binTopBar",
            ) { selectingNow ->
                if (selectingNow) {
                    val all = shownCount > 0 && selected.size >= shownCount
                    SelectionTopBar(
                        count = selected.size,
                        onClose = clearSelection,
                        actions = {
                            IconButton(onClick = if (all) clearSelection else selectAll, shapes = IconButtonDefaults.shapes()) {
                                Icon(if (all) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, contentDescription = if (all) "取消全选" else "全选")
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = { Text("回收站") },
                        navigationIcon = { BackButton(onBack) },
                        actions = {
                            SourceSortMenuButton(sort = sort, onSort = viewModel::setSort)
                            IconButton(onClick = selectAll, enabled = shownCount > 0, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.SelectAll, contentDescription = "全选")
                            }
                            BinMenu(
                                label = if (originals) "删除全部原图" else "清空回收站",
                                enabled = stats.count > 0 && !busy,
                                onClick = { confirm = if (originals) BinConfirm.DELETE_ALL_ORIGINALS else BinConfirm.PURGE_ALL },
                            )
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
                badges = PhotoBadges(archived = !originals, size = !sort.byDate, stack = !originals),
                onOpen = { image ->
                    val loaded = items.itemSnapshotList.items.filterIsInstance<GalleryTimelineItem.Photo>()
                    val index = loaded.indexOfFirst { it.image.id == image.id }
                    if (index >= 0) {
                        previewUnits = loaded.mapNotNull { photo -> (photo.tag as? TrashUnit)?.let { photo.image.id to it } }.toMap()
                        preview = loaded.map { it.image } to index
                    }
                },
                onSelectedIdsChange = { selected = it },
                onColumnsChange = viewModel::setColumns,
                minColumns = SettingsRepository.MIN_GALLERY_COLUMNS,
                maxColumns = SettingsRepository.MAX_GALLERY_COLUMNS,
                sectionIds = viewModel::sectionIds,
                directory = directory,
                fastScroll = fastScroll,
                tileModel = viewModel::tileModel,
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 96.dp),
                leadingContent = {
                    item(key = "content", span = { GridItemSpan(maxLineSpan) }, contentType = "content") {
                        BinContentToggle(
                            selected = content,
                            deleted = deletedStats,
                            originals = originalStats,
                            onSelect = { next ->
                                clearSelection()
                                viewModel.setContent(next)
                            },
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                    if (!originals && reasons.size > 1) {
                        item(key = "reasons", span = { GridItemSpan(maxLineSpan) }, contentType = "reasons") {
                            ReasonChips(
                                reasons = reasons,
                                selected = reason,
                                total = deletedStats.count,
                                onSelect = { label ->
                                    clearSelection()
                                    viewModel.setReason(label)
                                },
                            )
                        }
                    }
                    if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
                        item(key = "empty-$content", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                            Box(Modifier.fillMaxWidth().height(360.dp)) {
                                EmptyState(
                                    icon = if (originals) Icons.Outlined.Compress else Icons.Outlined.Inventory2,
                                    title = if (originals) "没有原图备份" else "回收站是空的",
                                )
                            }
                        }
                    }
                },
            )

            // 回收站里主要是清掉：最显眼的是永久删除（删除原图），恢复放在旁边，也要确认
            FloatingActionToolbar(visible = selecting, modifier = Modifier.align(Alignment.BottomCenter)) {
                if (originals) {
                    ToolbarIconAction(Icons.Outlined.Restore, "还原原图", enabled = !busy, onClick = { confirm = BinConfirm.RESTORE_ORIGINALS })
                    ToolbarPrimaryAction(
                        Icons.Outlined.DeleteForever, "删除原图 ${formatCountExact(selected.size)} 张", enabled = !busy,
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                        onClick = { confirm = BinConfirm.DELETE_ORIGINALS },
                    )
                } else {
                    ToolbarIconAction(Icons.Outlined.Restore, "恢复", enabled = !busy, onClick = { confirm = BinConfirm.RESTORE })
                    ToolbarIconAction(Icons.Outlined.Archive, "恢复并归档", enabled = !busy, onClick = { confirm = BinConfirm.RESTORE_ARCHIVE })
                    ToolbarPrimaryAction(
                        Icons.Outlined.DeleteForever, "永久删除 ${formatCountExact(selected.size)} 张", enabled = !busy,
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                        onClick = { confirm = BinConfirm.PURGE },
                    )
                }
            }
        }
    }

    confirm?.let { kind ->
        val ids = selected
        val dismiss = { confirm = null }
        // 选中的图没了（如进程重建后）就不再弹出要确认选中项的对话框
        if (ids.isEmpty() && kind != BinConfirm.PURGE_ALL && kind != BinConfirm.DELETE_ALL_ORIGINALS) {
            LaunchedEffect(kind) { dismiss() }
            return@let
        }
        when (kind) {
            BinConfirm.PURGE -> ConfirmDialog(
                icon = Icons.Outlined.DeleteForever,
                title = "永久删除 ${formatCountExact(ids.size)} 张？",
                text = "删除后无法恢复。",
                confirmLabel = "永久删除",
                destructive = true,
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    viewModel.purge(ids, onDone = clearSelection)
                },
            )
            BinConfirm.PURGE_ALL -> ConfirmDialog(
                icon = Icons.Outlined.DeleteSweep,
                title = "清空回收站？",
                text = "永久删除 ${formatCountExact(deletedStats.count)} 张（${formatBytes(deletedStats.bytes)}），无法恢复。",
                confirmLabel = "清空",
                destructive = true,
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    clearSelection()
                    viewModel.purgeAll()
                },
            )
            BinConfirm.DELETE_ORIGINALS -> ConfirmDialog(
                icon = Icons.Outlined.DeleteForever,
                title = "删除 ${formatCountExact(ids.size)} 张原图？",
                text = "压缩后的图片保留，原图无法再还原。",
                confirmLabel = "删除原图",
                destructive = true,
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    viewModel.deleteOriginals(ids, onDone = clearSelection)
                },
            )
            BinConfirm.RESTORE, BinConfirm.RESTORE_ARCHIVE -> {
                val archive = kind == BinConfirm.RESTORE_ARCHIVE
                ConfirmDialog(
                    icon = if (archive) Icons.Outlined.Archive else Icons.Outlined.Restore,
                    title = (if (archive) "恢复并归档 " else "恢复 ") + "${formatCountExact(ids.size)} 张？",
                    text = if (archive) "放回原来的位置并归档，之后不会被清理。" else "放回原来的位置。",
                    confirmLabel = if (archive) "恢复并归档" else "恢复",
                    onDismiss = dismiss,
                    onConfirm = {
                        dismiss()
                        viewModel.restore(ids, thenArchive = archive, onDone = clearSelection)
                    },
                )
            }
            BinConfirm.RESTORE_ORIGINALS -> ConfirmDialog(
                icon = Icons.Outlined.Restore,
                title = "还原 ${formatCountExact(ids.size)} 张原图？",
                text = "换回压缩前的原图。",
                confirmLabel = "还原",
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    viewModel.restoreOriginals(ids, onDone = clearSelection)
                },
            )
            BinConfirm.DELETE_ALL_ORIGINALS -> ConfirmDialog(
                icon = Icons.Outlined.DeleteSweep,
                title = "删除全部原图？",
                text = "删除 ${formatCountExact(originalStats.count)} 张原图（${formatBytes(originalStats.bytes)}），压缩后的图片保留。",
                confirmLabel = "全部删除",
                destructive = true,
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    clearSelection()
                    viewModel.deleteAllOriginals()
                },
            )
        }
    }

    // 原位置已经有 QQ 新下载的文件：用现有的，或者先留在回收站
    conflict?.let { pending ->
        ConfirmDialog(
            icon = Icons.Outlined.Restore,
            title = "原位置已有文件",
            text = "${formatCountExact(pending.quarantineIds.size)} 张的原位置已经有 QQ 新下载的文件。用现有的文件，回收站里的副本就删掉。",
            confirmLabel = "用现有文件",
            dismissLabel = "先不恢复",
            onDismiss = viewModel::dismissConflict,
            onConfirm = { viewModel.keepExisting(pending) },
        )
    }

    preview?.let { (list, start) ->
        fun unitOf(image: ImageEntity): TrashUnit? = previewUnits[image.id] ?: viewModel.member(image.id)
        /** 看图时可能切到了同一组的其他版本：操作和移出快照都按这一页（组的代表图）算 */
        fun pageOf(shown: ImageEntity): ImageEntity {
            val group = unitOf(shown)?.groupId
            return list.firstOrNull { it.id == shown.id } ?: list.firstOrNull { group != null && previewUnits[it.id]?.groupId == group } ?: shown
        }
        FullScreenImagePager(
            images = list,
            startIndex = start,
            onClose = { preview = null },
            onShare = null,
            onSave = null,
            variantsOf = if (originals) null else { image -> viewModel.versionsOf(previewUnits[image.id]) },
            modelOf = { image -> viewModel.viewerModel(image, unitOf(image)) },
            detailsHeader = if (originals) null else { shown -> unitOf(shown)?.let { TrashDetails(it) } },
            bottomBar = { shown ->
                val page = pageOf(shown)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (originals) {
                        PreviewAction(icon = Icons.Outlined.Restore, label = "还原原图", enabled = !busy, onClick = { pendingRestore = PendingRestore(page, archive = false) })
                        PreviewAction(icon = Icons.Outlined.DeleteForever, label = "删除原图", enabled = !busy, onClick = { pendingPurge = page })
                    } else {
                        PreviewAction(icon = Icons.Outlined.Restore, label = "恢复", enabled = !busy, onClick = { pendingRestore = PendingRestore(page, archive = false) })
                        PreviewAction(icon = Icons.Outlined.Archive, label = "恢复并归档", enabled = !busy, onClick = { pendingRestore = PendingRestore(page, archive = true) })
                        PreviewAction(icon = Icons.Outlined.DeleteForever, label = "永久删除", enabled = !busy, onClick = { pendingPurge = page })
                    }
                }
                pendingRestore?.let { (target, archive) ->
                    ConfirmDialog(
                        icon = if (archive) Icons.Outlined.Archive else Icons.Outlined.Restore,
                        title = when {
                            originals -> "还原这张原图？"
                            archive -> "恢复并归档这张图？"
                            else -> "恢复这张图？"
                        },
                        text = when {
                            originals -> "换回压缩前的原图。"
                            archive -> "放回原来的位置并归档，之后不会被清理。"
                            else -> "放回原来的位置。"
                        },
                        confirmLabel = when {
                            originals -> "还原"
                            archive -> "恢复并归档"
                            else -> "恢复"
                        },
                        onDismiss = { pendingRestore = null },
                        onConfirm = {
                            pendingRestore = null
                            val started = if (originals) viewModel.restoreOriginals(listOf(target.id)) else viewModel.restore(listOf(target.id), thenArchive = archive)
                            if (started) preview = preview?.without(target.id)
                        },
                    )
                }
                pendingPurge?.let { target ->
                    ConfirmDialog(
                        icon = Icons.Outlined.DeleteForever,
                        title = if (originals) "删除这张原图？" else "永久删除这张图？",
                        text = if (originals) "压缩后的图片保留，原图无法再还原。" else "删除后无法恢复。",
                        confirmLabel = if (originals) "删除原图" else "永久删除",
                        destructive = true,
                        onDismiss = { pendingPurge = null },
                        onConfirm = {
                            pendingPurge = null
                            val started = if (originals) viewModel.deleteOriginals(listOf(target.id)) else viewModel.purge(listOf(target.id))
                            if (started) preview = preview?.without(target.id)
                        },
                    )
                }
            },
        )
    }
    // 进度对话框与结果提示由 CleanupScreen 根部统一显示
}

/** 右上角菜单：清空回收站 / 删除全部原图 */
@Composable
private fun BinMenu(label: String, enabled: Boolean, onClick: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                enabled = enabled,
                onClick = {
                    open = false
                    onClick()
                },
            )
        }
    }
}

/** 移入回收站的图 / 压缩前保留的原图（连接式按钮组），各自带占用 */
@Composable
private fun BinContentToggle(
    selected: BinContent,
    deleted: CleanupDao.QuarantineStatsRow,
    originals: CleanupDao.QuarantineStatsRow,
    onSelect: (BinContent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        BinContent.entries.forEachIndexed { index, option ->
            val checked = option == selected
            val stats = if (option == BinContent.DELETED) deleted else originals
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(option) },
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                colors = ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                if (checked) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                }
                Text(
                    "${if (option == BinContent.DELETED) "已删除" else "原图备份"} · ${formatBytes(stats.bytes)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 按删除原因筛选：「全部」+ 各类原因（张数多的在前） */
@Composable
private fun ReasonChips(reasons: List<ReasonFilter>, selected: String?, total: Int, onSelect: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("全部 · ${formatCountExact(total)}") })
        reasons.forEach { option ->
            val isSelected = option.label == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else option.label) },
                label = { Text("${option.label} · ${formatCountExact(option.count)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = if (isSelected) {
                    { Icon(Icons.Outlined.Close, contentDescription = "取消筛选", modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

/** 详情里的删除信息：原因、移入时间、自动删除 */
@Composable
private fun TrashDetails(unit: TrashUnit) {
    SegmentedCard(SegmentPosition.Single, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        TrashInfoRow("删除原因", unit.reason.ifBlank { "没有记录" })
        TrashInfoRow("移入时间", formatQuarantineDate(unit.quarantinedAt))
        TrashInfoRow("自动删除", autoDeleteText(unit.expiresAt))
    }
}

@Composable
private fun TrashInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.7f))
    }
}

/** 从看图快照移除一张并停在下一张（删的是末张则后退），删空后关闭 */
private fun Pair<List<ImageEntity>, Int>.without(id: Long): Pair<List<ImageEntity>, Int>? {
    val index = first.indexOfFirst { it.id == id }
    if (index < 0) return this
    val remaining = first.filterNot { it.id == id }
    if (remaining.isEmpty()) return null
    return remaining to index.coerceAtMost(remaining.lastIndex)
}
