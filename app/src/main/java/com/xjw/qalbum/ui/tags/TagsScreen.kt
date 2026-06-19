package com.xjw.qalbum.ui.tags

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.ui.cleanup.PurgeProgressDialog
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import androidx.compose.foundation.layout.statusBarsPadding
import org.koin.androidx.compose.koinViewModel

/**
 * 标签管理页
 *
 * - 一级：所有「AI 打的标签」列表（带活跃图片数），可按名搜索筛选。
 * - 二级：点某标签 → 该标签下图片分页网格；点选子集后「移入回收站 已选 N」，
 *   未选时「移入回收站·全部 N」（按 tag 分块全删）。删除全程有进度条。
 */
@Composable
fun TagsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: TagsViewModel = koinViewModel(),
) {
    val tags by viewModel.tags.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val context = LocalContext.current
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
            busy = busy,
            contentPadding = contentPadding,
            pager = remember(activeTag.id) { viewModel.pagerFor(activeTag.id) },
            onBack = { selected = null },
            onMoveToTrash = { ids ->
                viewModel.moveToTrash(activeTag, ids) { count ->
                    Toast.makeText(context, "已移入回收站 $count 张", Toast.LENGTH_SHORT).show()
                }
            },
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
                            "开启并运行「AI 标签」分析后，标签会显示在这里。",
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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "敏感",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
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
private fun TagImagesContent(
    tag: TagDao.TagWithCount,
    busy: Boolean,
    contentPadding: PaddingValues,
    pager: kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<ImageEntity>>,
    onBack: () -> Unit,
    onMoveToTrash: (Set<Long>) -> Unit,
) {
    val images = pager.collectAsLazyPagingItems()
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
                        .padding(start = 4.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
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
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 6.dp,
                end = 6.dp,
                top = 6.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(
                count = images.itemCount,
                key = { index -> images.peek(index)?.id ?: "loading-$index" },
            ) { index ->
                val img = images[index]
                if (img != null) {
                    TagImageTile(
                        image = img,
                        selected = img.id in selectedIds,
                        onToggle = {
                            selectedIds = if (img.id in selectedIds) selectedIds - img.id else selectedIds + img.id
                        },
                        onPreview = { preview = img },
                    )
                }
            }
        }
    }

    preview?.let { image ->
        TagPreviewDialog(image = image, onClose = { preview = null })
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
                    onMoveToTrash(ids)
                }) { Text("移入回收站") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TagImageTile(
    image: ImageEntity,
    selected: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
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
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.20f)),
            )
        }
        Icon(
            if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.36f)),
            tint = Color.White,
        )
    }
}

@Composable
private fun TagPreviewDialog(image: ImageEntity, onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onClose),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(ImageEntityModel(image))
                    .crossfade(true)
                    .build(),
                contentDescription = image.filename,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}
