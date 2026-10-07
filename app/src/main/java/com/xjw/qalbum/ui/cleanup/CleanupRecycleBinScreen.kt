package com.xjw.qalbum.ui.cleanup

import android.content.Intent
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
import com.xjw.qalbum.ThumbnailTrashActivity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.CleanupDao
import org.koin.compose.koinInject
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.LoadingState
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.ViewerIconButton
import java.io.File

/** 回收站：已移入回收站的图片，点按查看后可恢复或永久删除；全屏、隐藏底部导航栏。 */
@Composable
fun CleanupRecycleBinScreen(
    contentPadding: PaddingValues,
    viewModel: CleanupViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val items = viewModel.quarantine.collectAsLazyPagingItems()
    val quarantineStats by viewModel.quarantineStats.collectAsState()
    val thumbnailSummary by viewModel.thumbnailTrashSummary.collectAsState()
    val busy by viewModel.busy.collectAsState()

    // 计数 / 总大小走 DB 聚合：列表已分页，内存里没有全量项
    val totalCount = quarantineStats.count
    val totalBytes = quarantineStats.bytes
    var showClearConfirm by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<QuarantineEntity?>(null) }

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
            item(span = { GridItemSpan(maxLineSpan) }, key = "notice") {
                InfoBanner("回收站位于应用专属目录：到期会自动永久删除；卸载或清除应用数据时，其中的图片也会一并丢失。")
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "thumbs") {
                Box(Modifier.padding(bottom = 6.dp)) {
                    ThumbnailEntryItem(
                        position = SegmentPosition.Single,
                        count = thumbnailSummary.count,
                        bytes = thumbnailSummary.bytes,
                        onOpen = { context.startActivity(Intent(context, ThumbnailTrashActivity::class.java)) },
                    )
                }
            }

            if (items.itemCount == 0) {
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
                                message = "从图库、审核或聊天来源删除的图片会先来到这里。",
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

    if (showClearConfirm) {
        ConfirmDialog(
            icon = Icons.Outlined.DeleteForever,
            title = "清空回收站？",
            text = "将永久删除回收站中的 $totalCount 组图片（${formatBytes(totalBytes)}），包括随图片移入的关联缓存，无法恢复。其他 QQ 预览缓存不受影响。",
            confirmLabel = "永久删除",
            destructive = true,
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                showClearConfirm = false
                viewModel.purgeAllRecycleBin { count ->
                    Toast.makeText(context, "已永久删除 $count 项", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    // 清理进度模态由 CleanupScreen 根部统一展示
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
