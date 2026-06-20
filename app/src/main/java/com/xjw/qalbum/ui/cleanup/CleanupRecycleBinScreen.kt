package com.xjw.qalbum.ui.cleanup

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.ThumbnailTrashActivity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import java.io.File

/** 回收站页：已移入回收站的图片 + 缩略图入口；全屏、隐藏底部导航栏。 */
@Composable
fun CleanupRecycleBinScreen(
    contentPadding: PaddingValues,
    viewModel: CleanupViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val items by viewModel.quarantine.collectAsState()
    val thumbnailSummary by viewModel.thumbnailTrashSummary.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val purgeProgress by viewModel.purgeProgress.collectAsState()

    val totalCount = items.size + thumbnailSummary.count
    val totalBytes = items.sumOf { it.size } + thumbnailSummary.bytes
    var showClearConfirm by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<QuarantineEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text("回收站", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "$totalCount 项 · ${formatBytes(totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showClearConfirm = true }, enabled = totalCount > 0 && !busy) {
                        Text("一键清理")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(top = topPadding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ThumbnailEntryCard(
                    summary = thumbnailSummary,
                    onOpen = { context.startActivity(Intent(context, ThumbnailTrashActivity::class.java)) },
                )
            }

            if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 56.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Inventory2,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("没有已移入回收站的图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "已删除图片 · 点按查看",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(items, key = { it.id }) { record ->
                    QuarantineTile(record = record, onClick = { preview = record })
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
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清理整个回收站？") },
            text = { Text("将永久删除回收站中的图片和缩略图，删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.purgeAllRecycleBin { count ->
                        Toast.makeText(context, "已清理 $count 项", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确认清理") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } },
        )
    }

    purgeProgress?.let { PurgeProgressDialog(it) }
}

@Composable
private fun QuarantineTile(record: QuarantineEntity, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        val thumb = record.thumbnailPath
        if (thumb != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(File(thumb)).crossfade(true).build(),
                contentDescription = record.reason,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp)
                .clip(RoundedCornerShape(999.dp))
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

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val thumb = record.thumbnailPath
            if (thumb != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(thumb)).crossfade(true).build(),
                    contentDescription = record.reason,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(72.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(start = 4.dp, top = 6.dp, end = 12.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        record.originalPath.substringAfterLast('/').ifBlank { "图片" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "删除于 ${formatQuarantineDate(record.quarantinedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Inventory2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("删除原因", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(record.reason, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                HorizontalDivider()
                QuarantineInfoRow("大小", formatBytes(record.size))
                QuarantineInfoRow("自动删除", autoDeleteText(record.expiresAt))
                QuarantineInfoRow("原位置", record.originalPath.substringBeforeLast('/').ifBlank { record.originalPath })
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("恢复")
                    }
                    Button(
                        onClick = { confirmPurge = true },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("永久删除")
                    }
                }
            }
        }
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("永久删除这张图片？") },
            text = { Text("删除后将无法恢复。") },
            confirmButton = {
                TextButton(onClick = { confirmPurge = false; onPurge() }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("取消") } },
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
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.7f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
