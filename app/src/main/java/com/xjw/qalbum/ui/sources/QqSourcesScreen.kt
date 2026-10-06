package com.xjw.qalbum.ui.sources

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.ui.cleanup.shareChooser
import com.xjw.qalbum.ui.components.FullScreenImagePager
import com.xjw.qalbum.ui.components.PreviewAction
import com.xjw.qalbum.ui.gallery.GalleryViewModel
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QqSourcesScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: QqSourcesViewModel = koinViewModel(),
    gallery: GalleryViewModel = koinViewModel(),
) {
    val selected by viewModel.selection.collectAsState()
    val tab by viewModel.tab.collectAsState()
    val search by viewModel.search.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val senders by viewModel.senders.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val linkedCount by viewModel.linkedCount.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val progress by viewModel.repository.progress.collectAsState()
    val message by viewModel.repository.message.collectAsState()
    val report by viewModel.repository.report.collectAsState()
    val sourcesComplete by viewModel.repository.sourcesComplete.collectAsState(initial = false)
    val confirmation by viewModel.confirmation.collectAsState()
    val subdirs by gallery.exportSubdirs.collectAsState()
    val photos = viewModel.photos.collectAsLazyPagingItems()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<List<ImageEntity>, Int>?>(null) }
    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.repository.acknowledgeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text("QQ 图片来源", style = MaterialTheme.typography.titleLarge)
                    Text("已关联 $linkedCount 张", style = MaterialTheme.typography.bodySmall)
                } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = viewModel.repository::refresh, enabled = progress == null) { Icon(Icons.Outlined.Refresh, "读取 QQ 来源（Root）") }
                    if (selected.group != null || selected.sender != null) {
                        IconButton(onClick = { viewModel.requestTrash(selected) }, enabled = progress == null) { Icon(Icons.Outlined.Delete, "清理筛选后的图片") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("清除来源索引") }, enabled = progress == null,
                                onClick = { menu = false; clearConfirm = true })
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            progress?.let { running ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(running.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (running.cancellable) TextButton(onClick = viewModel.repository::cancel) { Text("停止") }
                    }
                    if (running.total > 0) LinearProgressIndicator(progress = { (running.done.toFloat() / running.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (accounts.isEmpty() && progress == null) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("尚未读取 QQ 图片来源", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = viewModel.repository::refresh) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("读取来源（Root）")
                    }
                }
            } else if (accounts.isNotEmpty()) {
                val updated = accounts.maxOf { it.importedAt }
                Text("${accounts.size} 个账号 · ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(updated))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (progress == null) report?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (progress == null && accounts.isNotEmpty() && !sourcesComplete && report == null) {
                Text("部分来源未能读取，筛选和清理仅覆盖已关联图片；共用统计可能有遗漏。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (selected.group != null || selected.sender != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    selected.group?.let { facet -> SourceFilter(facet.name.ifBlank { "群名称未缓存" }, { viewModel.openTab(0) }, { viewModel.selection.value = selected.copy(group = null) }) }
                    selected.sender?.let { facet -> SourceFilter(facet.name.ifBlank { "发送人名称未缓存" }, { viewModel.openTab(1) }, { viewModel.selection.value = selected.copy(sender = null) }) }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf("群聊", "发送人", "图片").forEachIndexed { index, label ->
                    SegmentedButton(selected = tab == index, onClick = { viewModel.openTab(index) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                }
            }
            if (tab != 2) {
                OutlinedTextField(value = search, onValueChange = { viewModel.search.value = it },
                    label = { Text(if (tab == 0) "搜索群聊" else "搜索昵称或群名片") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                val facets = if (tab == 0) groups else senders
                if (facets.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(if (accounts.isEmpty()) "尚无来源索引" else "没有已关联的图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
                        items(facets, key = { it.key }) { facet ->
                            SourceRow(facet, tab == 0, accounts.size > 1,
                                onClick = { if (tab == 0) viewModel.selectGroup(facet) else viewModel.selectSender(facet) },
                                onLongClick = { viewModel.requestTrash(if (tab == 0) selected.copy(group = facet) else selected.copy(sender = facet)) })
                        }
                    }
                }
            } else {
                if (photos.loadState.refresh is LoadState.Error) {
                    TextButton(onClick = { photos.retry() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("加载失败，重试") }
                } else if (photos.itemCount == 0) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (photos.loadState.refresh is LoadState.Loading) CircularProgressIndicator()
                        else Text("没有已关联的图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Fixed(columns.coerceIn(2, 8)), modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(photos.itemCount, key = { index -> photos.peek(index)?.id ?: -index.toLong() - 1 }) { index ->
                            photos[index]?.let { image ->
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable {
                                    val loaded = photos.itemSnapshotList.items
                                    val position = loaded.indexOfFirst { it.id == image.id }
                                    if (position >= 0) preview = loaded to position
                                }) {
                                    AsyncImage(model = ImageEntityModel(image), contentDescription = image.filename, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmation?.let { request ->
        AlertDialog(onDismissRequest = { viewModel.confirmation.value = null },
            title = { Text("移入回收站") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.selection.group?.let { Text(it.name.ifBlank { "群名称未缓存" }) }
                request.selection.sender?.let { Text(it.name.ifBlank { "发送人名称未缓存" }) }
                request.counts?.let { c -> Text("共 ${c.total} 张 · 共用 ${c.shared} 张 · 受保护 ${c.protectedCount} 张") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = request.includeShared, onCheckedChange = viewModel::updateShared)
                    Text("包含其他群聊或发送人共用的图片", style = MaterialTheme.typography.bodyMedium)
                }
                Text(if (sourcesComplete) "清理会影响 QQ 聊天图片。来源仅覆盖本机已缓存记录；收藏、保留和受保护图片会跳过。"
                    else "仅清理已明确关联的图片。共用统计可能遗漏未读取的聊天来源，清理也可能影响那些聊天。图片先进入回收站，收藏、保留和受保护图片会跳过。", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = viewModel::confirmTrash,
                enabled = request.counts != null && request.counts!!.removable > 0 && progress == null) { Text("移入 ${request.counts?.removable ?: 0} 张") } },
            dismissButton = { TextButton(onClick = { viewModel.confirmation.value = null }) { Text("取消") } })
    }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清除来源索引？") },
        text = { Text("移除 QAlbum 保存的群聊与发送人信息，图片文件保留。") },
        confirmButton = { TextButton(onClick = { clearConfirm = false; viewModel.selection.value = QqSourceSelection(); viewModel.repository.clear() }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })

    preview?.let { (images, index) ->
        FullScreenImagePager(images = images, startIndex = index, onClose = { preview = null },
            onShare = { id -> viewModel.share(id) { context.startActivity(shareChooser(it)) } },
            exportSubdirs = subdirs, onCreateSubdir = gallery::createSubdir,
            onSave = { id, subdir, alsoTrash -> gallery.saveImages(setOf(id), subdir, alsoTrash) { saved, _ ->
                Toast.makeText(context, if (saved > 0) "已保存" else "保存失败", Toast.LENGTH_SHORT).show()
            } }, onLoadOcr = viewModel::ocr,
            bottomBar = { image ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    PreviewAction(icon = if (image.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        label = if (image.isFavorite) "取消收藏" else "收藏", onClick = { viewModel.favorite(image)
                            preview = preview?.let { (list, start) -> list.map { if (it.id == image.id) it.copy(isFavorite = !it.isFavorite) else it } to start }
                        })
                }
            })
    }
}

@Composable
private fun SourceFilter(name: String, onSelect: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f).clickable(onClick = onSelect).padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, "移除此筛选") }
    }
}

@Composable
private fun SourceRow(facet: QqSourceFacet, group: Boolean, showAccount: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (group) Icons.Outlined.Groups else Icons.Outlined.Person, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(facet.name.ifBlank { if (group) "群名称未缓存" else "发送人名称未缓存" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (showAccount) Text(facet.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${facet.count} 张", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
