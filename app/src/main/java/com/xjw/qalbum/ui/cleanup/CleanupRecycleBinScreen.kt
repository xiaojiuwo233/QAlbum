package com.xjw.qalbum.ui.cleanup

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OriginalBackupEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.CleanupDao
import org.koin.compose.koinInject
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.LoadingState
import com.xjw.qalbum.ui.components.ViewerIconButton
import java.io.File
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import com.xjw.qalbum.ui.imageloading.ImageEntityModel

/**
 * 回收站：两个分页——移入回收站的图片（点按查看后可恢复或永久删除），以及原图压缩前的原图（可还原或删除释放空间）。
 */
@Composable
fun CleanupRecycleBinScreen(
    contentPadding: PaddingValues,
    viewModel: CleanupViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val items = viewModel.quarantine.collectAsLazyPagingItems()
    val backups = viewModel.backedUp.collectAsLazyPagingItems()
    val quarantineStats by viewModel.quarantineStats.collectAsState()
    val backupStats by viewModel.backupStats.collectAsState()
    val busy by viewModel.busy.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val originals = tab == 1
    val totalCount = if (originals) backupStats.count else quarantineStats.count
    val totalBytes = if (originals) backupStats.bytes else quarantineStats.bytes
    var showClearConfirm by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<QuarantineEntity?>(null) }
    var backupPreview by remember { mutableStateOf<ImageEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("回收站") },
                subtitle = { Text("$totalCount 项 · ${formatBytes(totalBytes)}") },
                actions = {
                    TextButton(onClick = { showClearConfirm = true }, enabled = totalCount > 0 && !busy) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("全部清空")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(top = topPadding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "tabs") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    listOf("移入回收站的", "压缩前的原图").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = tab == index,
                            onClick = { tab = index },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "notice-$tab") {
                InfoBanner(
                    if (originals) "压缩前的原图暂存在这里，到期删除。还原即换回原图。"
                    else "到期自动永久删除。卸载或清除 QAlbum 数据时会一并删除。",
                )
            }
            if (originals) {
                if (backups.itemCount == 0) {
                    val loading = backups.loadState.refresh is LoadState.Loading
                    item(span = { GridItemSpan(maxLineSpan) }, key = if (loading) "backups-loading" else "backups-empty") {
                        Box(Modifier.fillMaxWidth().height(320.dp)) {
                            if (loading) LoadingState()
                            else EmptyState(icon = Icons.Outlined.Inventory2, title = "没有压缩前的原图", message = "压缩时保留的原图会放在这里")
                        }
                    }
                } else {
                    items(count = backups.itemCount, key = { index -> backups.peek(index)?.id?.let { "b$it" } ?: "backup-loading-$index" }) { index ->
                        val image = backups[index]
                        if (image != null) BackupTile(image, onClick = { backupPreview = image }, modifier = Modifier.animateItem())
                    }
                }
            } else if (items.itemCount == 0) {
                // 首次读取完成前显示加载中，不能当成「回收站是空的」
                val loading = items.loadState.refresh is LoadState.Loading
                item(span = { GridItemSpan(maxLineSpan) }, key = if (loading) "loading" else "empty") {
                    Box(Modifier.fillMaxWidth().height(320.dp)) {
                        if (loading) {
                            LoadingState()
                        } else {
                            EmptyState(
                                icon = Icons.Outlined.Inventory2,
                                title = "回收站是空的",
                                message = "删除的图片会先放在这里",
                            )
                        }
                    }
                }
            } else {
                items(
                    count = items.itemCount,
                    key = { index -> items.peek(index)?.id ?: "loading-$index" },
                ) { index ->
                    val record = items[index]
                    if (record != null) {
                        QuarantineTile(record = record, onClick = { preview = record }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }

    preview?.let { record ->
        QuarantinePreviewDialog(
            record = record,
            busy = busy,
            onClose = { preview = null },
            onRestore = { viewModel.restore(record.id); preview = null },
            onPurge = { viewModel.purge(record.id); preview = null },
        )
    }

    backupPreview?.let { image ->
        BackupPreviewDialog(
            image = image,
            busy = busy,
            onClose = { backupPreview = null },
            onRestore = { viewModel.restoreOriginal(image.id); backupPreview = null },
            onDelete = { viewModel.deleteOriginal(image.id); backupPreview = null },
        )
    }

    if (showClearConfirm) {
        ConfirmDialog(
            icon = Icons.Outlined.DeleteForever,
            title = if (originals) "删除全部原图？" else "清空回收站？",
            text = if (originals) {
                "删除 $totalCount 张原图（${formatBytes(totalBytes)}），之后无法还原。压缩后的图片保留。"
            } else {
                "永久删除 $totalCount 组图片（${formatBytes(totalBytes)}），连同关联缓存，无法恢复。"
            },
            confirmLabel = "永久删除",
            destructive = true,
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                showClearConfirm = false
                if (originals) {
                    viewModel.purgeAllOriginals { count -> Toast.makeText(context, "已删除 $count 张原图", Toast.LENGTH_SHORT).show() }
                } else {
                    viewModel.purgeAllRecycleBin { count -> Toast.makeText(context, "已永久删除 $count 项", Toast.LENGTH_SHORT).show() }
                }
            },
        )
    }
    // 清理进度模态由 CleanupScreen 根部统一展示
}

/** 压缩前的原图：格子里显示压缩后的图片（就是同一张图） */
@Composable
private fun BackupTile(image: ImageEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(ImageEntityModel(image, tile = true)).crossfade(true).build(),
            contentDescription = image.filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 压缩前的原图：查看压缩后的效果，还原原图或删除原图 */
@Composable
private fun BackupPreviewDialog(
    image: ImageEntity,
    busy: Boolean,
    onClose: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val database = koinInject<AppDatabase>()
    val record by produceState<OriginalBackupEntity?>(null, image.id) { value = database.originalBackupDao().getByImageId(image.id) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(ImageEntityModel(image, tile = false)).crossfade(true).build(),
                contentDescription = image.filename,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(start = 4.dp, top = 6.dp, end = 12.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerIconButton(Icons.Outlined.Close, "关闭", onClose)
                Text(
                    "压缩后的图片",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
            Surface(
                shape = MaterialTheme.shapes.extraLarge.copy(
                    bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
                    bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
                ),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    record?.let { r ->
                        QuarantineInfoRow("原图", "${formatBytes(r.originalSize)} · ${r.originalWidth.coerceAtLeast(0)}×${r.originalHeight.coerceAtLeast(0)}")
                        QuarantineInfoRow("压缩后", "${formatBytes(r.compressedSize)} · ${r.compressedWidth}×${r.compressedHeight}")
                        QuarantineInfoRow("自动删除原图", autoDeleteText(r.expiresAt))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = onRestore, enabled = !busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("还原原图")
                        }
                        Button(
                            onClick = { confirmDelete = true },
                            enabled = !busy,
                            shapes = ButtonDefaults.shapes(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("删除原图")
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            icon = Icons.Outlined.DeleteForever,
            title = "删除这张原图？",
            text = "压缩后的图片保留，但无法再还原。",
            confirmLabel = "删除原图",
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; onDelete() },
        )
    }
}

@Composable
private fun QuarantineTile(record: QuarantineEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        // 新记录直接用移动后的原图（quarantinePath）；存量记录用其缩略图。文件缺失时露出背景色占位
        val thumb = record.thumbnailPath ?: record.quarantinePath
        AsyncImage(
            model = ImageRequest.Builder(context).data(File(thumb)).crossfade(true).build(),
            contentDescription = record.reason,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(record.reason, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuarantinePreviewDialog(
    record: QuarantineEntity,
    busy: Boolean,
    onClose: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val context = LocalContext.current
    var confirmPurge by remember { mutableStateOf(false) }
    val database=koinInject<AppDatabase>()
    val groupStats by remember(record.id,record.groupId) { database.cleanupDao().observeGroupStats(record.id,record.groupId) }
        .collectAsState(initial=CleanupDao.QuarantineStatsRow(record.fileCount,record.size))
    val sheetVisible = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val thumb = record.thumbnailPath ?: record.quarantinePath
            AsyncImage(
                model = ImageRequest.Builder(context).data(File(thumb)).crossfade(true).build(),
                contentDescription = record.reason,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(start = 4.dp, top = 6.dp, end = 12.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerIconButton(Icons.Outlined.Close, "关闭", onClose)
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        record.originalPath.substringAfterLast('/').ifBlank { "图片" },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "移入于 ${formatQuarantineDate(record.quarantinedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }

            AnimatedVisibility(
                visibleState = sheetVisible,
                enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge.copy(
                        bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
                        bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
                    ),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("删除原因", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(
                                    record.reason,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        QuarantineInfoRow("大小", formatBytes(groupStats.bytes))
                        if(record.groupId!=null)QuarantineInfoRow("关联文件", "${groupStats.count} 个，将一并恢复或删除")
                        QuarantineInfoRow("自动删除", autoDeleteText(record.expiresAt))
                        QuarantineInfoRow("原位置", record.originalPath.substringBeforeLast('/').ifBlank { record.originalPath })
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(
                                onClick = onRestore,
                                enabled = !busy,
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(if(record.groupId!=null)"恢复整组" else "恢复")
                            }
                            Button(
                                onClick = { confirmPurge = true },
                                enabled = !busy,
                                shapes = ButtonDefaults.shapes(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text("永久删除")
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmPurge) {
        ConfirmDialog(
            icon = Icons.Outlined.DeleteForever,
            title = "永久删除这张图片？",
            text = "删除后无法恢复。",
            confirmLabel = "永久删除",
            destructive = true,
            onDismiss = { confirmPurge = false },
            onConfirm = { confirmPurge = false; onPurge() },
        )
    }
}

@Composable
private fun QuarantineInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.7f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
