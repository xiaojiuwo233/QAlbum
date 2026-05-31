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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.ThumbnailTrashActivity
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.service.analysis.AnalysisState
import com.xjw.qalbum.service.cleanup.CleanupBatch
import com.xjw.qalbum.service.cleanup.CleanupStrategy
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import org.koin.androidx.compose.koinViewModel

/**
 * 清理首页
 *
 * - 顶部"分析"卡片：触发 Hash/Quality/Duplicate/Score pipeline
 * - 中部"生成清理批次"：选择策略 → 生成 → 显示风险报告
 * - 底部"回收站"入口
 */
@Composable
fun CleanupScreen(
    contentPadding: PaddingValues,
    viewModel: CleanupViewModel = koinViewModel(),
) {
    val analysisState by viewModel.analysisState.collectAsState()
    val analysisProgress by viewModel.analysisProgress.collectAsState()
    val strategy by viewModel.strategy.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val lastBatch by viewModel.lastBatch.collectAsState()
    val reviewBatch by viewModel.reviewBatch.collectAsState()
    val selectedReviewDeleteIds by viewModel.selectedReviewDeleteIds.collectAsState()
    val recycleBinItems by viewModel.quarantine.collectAsState()
    val thumbnailTrashSummary by viewModel.thumbnailTrashSummary.collectAsState()
    val context = LocalContext.current

    val recycleBinCount = recycleBinItems.size + thumbnailTrashSummary.count
    val recycleBinBytes = recycleBinItems.sumOf { it.size } + thumbnailTrashSummary.bytes

    var activePage by rememberSaveable { mutableStateOf(CleanupPage.Main.name) }
    LaunchedEffect(reviewBatch?.batch?.id) {
        if (reviewBatch != null) activePage = CleanupPage.Review.name
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("清理", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        if (activePage == CleanupPage.Review.name && reviewBatch != null) {
            ReviewCleanupSection(
                review = reviewBatch!!,
                selectedDeleteIds = selectedReviewDeleteIds,
                busy = busy,
                onBack = { activePage = CleanupPage.Main.name },
                onSelectDisposable = viewModel::setReviewItemSelected,
                onSelectDuplicateDelete = viewModel::selectDuplicateDelete,
                onApply = {
                    viewModel.applyReviewSelection { count ->
                        Toast.makeText(context, "已移入回收站 $count 张", Toast.LENGTH_SHORT).show()
                        activePage = CleanupPage.Main.name
                    }
                },
                contentPadding = PaddingValues(
                    top = topPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
            )
        } else if (activePage == CleanupPage.RecycleBin.name) {
            RecycleBinSection(
                items = recycleBinItems,
                thumbnailSummary = thumbnailTrashSummary,
                busy = busy,
                onRestore = viewModel::restore,
                onPurge = viewModel::purge,
                onPurgeAll = {
                    viewModel.purgeAllRecycleBin { count ->
                        Toast.makeText(context, "已清理 $count 项", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenThumbnails = {
                    context.startActivity(Intent(context, ThumbnailTrashActivity::class.java))
                },
                onBack = { activePage = CleanupPage.Main.name },
                contentPadding = PaddingValues(
                    top = topPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPadding.calculateTopPadding())
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AnalysisCard(
                    state = analysisState,
                    progress = analysisProgress,
                    onStart = viewModel::startAnalysis,
                    onCancel = viewModel::cancelAnalysis,
                )

                StrategyCard(
                    current = strategy,
                    busy = busy,
                    onSelect = viewModel::setStrategy,
                    onGenerate = viewModel::generateBatch,
                )

                lastBatch?.let { batch ->
                    ReviewSummaryCard(
                        batch = batch,
                        selectedCount = selectedReviewDeleteIds.size,
                        onOpen = { activePage = CleanupPage.Review.name },
                    )
                }

                RecycleBinSummaryCard(
                    count = recycleBinCount,
                    bytes = recycleBinBytes,
                    onOpen = { activePage = CleanupPage.RecycleBin.name },
                )
            }
        }
    }
}

@Composable
private fun ThumbnailEntryCard(
    summary: ImageDao.ThumbnailTrashSummary,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("缩略图", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${summary.count} 项 · ${formatBytes(summary.bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            OutlinedButton(onClick = onOpen) { Text("查看") }
        }
    }
}

@Composable
private fun AnalysisCard(
    state: AnalysisState,
    progress: com.xjw.qalbum.service.analysis.AnalysisProgress?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(12.dp))
                Text("分析图片", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = analysisStatusText(state, progress),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state == AnalysisState.Running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state == AnalysisState.Running) {
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                } else {
                    Button(onClick = onStart) { Text("开始分析") }
                }
            }
        }
    }
}

@Composable
private fun StrategyCard(
    current: CleanupStrategy,
    busy: Boolean,
    onSelect: (CleanupStrategy) -> Unit,
    onGenerate: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CleaningServices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(12.dp))
                Text("清理策略", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                strategies.forEach { s ->
                    FilterChip(
                        selected = current.name == s.name,
                        onClick = { onSelect(s) },
                        label = { Text(s.displayName) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = strategyHint(current),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onGenerate, enabled = !busy) { Text("生成审核清理") }
        }
    }
}

@Composable
private fun ReviewSummaryCard(
    batch: CleanupBatch,
    selectedCount: Int,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("审核清理", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "批次 #${batch.id} · 共 ${batch.report.totalCount} 项 · 已预选 $selectedCount 项",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            // 按原因分组
            batch.report.byReason.entries.toList().forEachIndexed { index, (reason, group) ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(reason, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${group.count} · ${formatBytes(group.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpen) { Text("开始审核") }
        }
    }
}

@Composable
private fun ReviewCleanupSection(
    review: CleanupReviewBatch,
    selectedDeleteIds: Set<Long>,
    busy: Boolean,
    onBack: () -> Unit,
    onSelectDisposable: (Long, Boolean) -> Unit,
    onSelectDuplicateDelete: (Long, Long) -> Unit,
    onApply: () -> Unit,
    contentPadding: PaddingValues,
) {
    var category by rememberSaveable(review.batch.id) { mutableStateOf(ReviewCategory.All.name) }
    val showDuplicates = category != ReviewCategory.Disposable.name
    val showDisposable = category != ReviewCategory.Duplicate.name
    val selectedBytes = review.selectedBytes(selectedDeleteIds)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ReviewHeader(
                selectedCount = selectedDeleteIds.size,
                selectedBytes = selectedBytes,
                busy = busy,
                onBack = onBack,
                onApply = onApply,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ReviewCategoryChips(
                current = category,
                duplicateCount = review.duplicatePairs.size,
                disposableCount = review.disposableItems.size,
                onSelect = { category = it.name },
            )
        }

        if (showDuplicates && review.duplicatePairs.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ReviewSectionLabel("重复", "左右对比，默认选中清晰度较低的一张")
            }
            items(
                items = review.duplicatePairs,
                key = { "dup-${it.groupId}-${it.deleteImage.id}" },
                span = { GridItemSpan(maxLineSpan) },
            ) { pair ->
                DuplicateCompareCard(
                    pair = pair,
                    selectedDeleteIds = selectedDeleteIds,
                    onSelectDelete = onSelectDuplicateDelete,
                )
            }
        }

        if (showDisposable && review.disposableItems.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ReviewSectionLabel("无用", "损坏、空白、模糊或极小文件")
            }
            items(
                items = review.disposableItems,
                key = { "item-${it.image.id}" },
            ) { item ->
                DisposableReviewTile(
                    item = item,
                    selected = item.image.id in selectedDeleteIds,
                    onSelectedChange = { selected -> onSelectDisposable(item.image.id, selected) },
                )
            }
        }

        if (review.duplicatePairs.isEmpty() && review.disposableItems.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("没有需要审核的清理项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ReviewHeader(
    selectedCount: Int,
    selectedBytes: Long,
    busy: Boolean,
    onBack: () -> Unit,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onBack) { Text("返回") }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "审核清理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "已选 $selectedCount 项 · ${formatBytes(selectedBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onApply,
            enabled = selectedCount > 0 && !busy,
        ) {
            Text("移入回收站")
        }
    }
}

@Composable
private fun ReviewCategoryChips(
    current: String,
    duplicateCount: Int,
    disposableCount: Int,
    onSelect: (ReviewCategory) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = current == ReviewCategory.All.name,
            onClick = { onSelect(ReviewCategory.All) },
            label = { Text("全部 ${duplicateCount + disposableCount}") },
        )
        FilterChip(
            selected = current == ReviewCategory.Duplicate.name,
            onClick = { onSelect(ReviewCategory.Duplicate) },
            label = { Text("重复 $duplicateCount") },
        )
        FilterChip(
            selected = current == ReviewCategory.Disposable.name,
            onClick = { onSelect(ReviewCategory.Disposable) },
            label = { Text("无用 $disposableCount") },
        )
    }
}

@Composable
private fun ReviewSectionLabel(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DuplicateCompareCard(
    pair: DuplicateReviewPair,
    selectedDeleteIds: Set<Long>,
    onSelectDelete: (Long, Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "重复组 #${pair.groupId}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "系统按清晰度推荐",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DuplicateChoicePane(
                    image = pair.keepImage,
                    badge = "推荐保留",
                    selectedForDelete = pair.keepImage.id in selectedDeleteIds,
                    onClick = { onSelectDelete(pair.keepImage.id, pair.deleteImage.id) },
                    modifier = Modifier.weight(1f),
                )
                DuplicateChoicePane(
                    image = pair.deleteImage,
                    badge = "建议删除",
                    selectedForDelete = pair.deleteImage.id in selectedDeleteIds,
                    onClick = { onSelectDelete(pair.deleteImage.id, pair.keepImage.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DuplicateChoicePane(
    image: ImageEntity,
    badge: String,
    selectedForDelete: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
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
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.48f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Icon(
                if (selectedForDelete) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape),
                tint = if (selectedForDelete) MaterialTheme.colorScheme.error else Color.White,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (selectedForDelete) "将删除" else "将保留",
            style = MaterialTheme.typography.labelMedium,
            color = if (selectedForDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        SourceTypeText(image.sourceType)
        Text(
            imageMeta(image),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DisposableReviewTile(
    item: CleanupReviewItem,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectedChange(!selected) },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(ImageEntityModel(item.image))
                        .crossfade(true)
                        .build(),
                    contentDescription = item.image.filename,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape),
                    tint = if (selected) MaterialTheme.colorScheme.error else Color.White,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.candidate.reason,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SourceTypeText(item.image.sourceType)
            Text(
                imageMeta(item.image),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun SourceTypeText(sourceType: String) {
    Text(
        sourceTypeLabel(sourceType),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RecycleBinSummaryCard(
    count: Int,
    bytes: Long,
    onOpen: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("回收站", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$count 项 · ${formatBytes(bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpen) { Text("查看") }
        }
    }
}

@Composable
private fun RecycleBinSection(
    items: List<QuarantineEntity>,
    thumbnailSummary: ImageDao.ThumbnailTrashSummary,
    busy: Boolean,
    onRestore: (Long) -> Unit,
    onPurge: (Long) -> Unit,
    onPurgeAll: () -> Unit,
    onOpenThumbnails: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val totalCount = items.size + thumbnailSummary.count
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.size(12.dp))
            Text(
                "回收站（$totalCount 项）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { showClearConfirm = true },
                enabled = totalCount > 0 && !busy,
            ) {
                Text("一键清理")
            }
        }
        ThumbnailEntryCard(
            summary = thumbnailSummary,
            onOpen = onOpenThumbnails,
        )
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("没有已移入回收站的图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items.forEach { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(record.reason, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            record.originalPath.substringBefore('|'),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onRestore(record.id) }) { Text("恢复") }
                            OutlinedButton(
                                onClick = { onPurge(record.id) },
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("永久删除")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清理整个回收站？") },
            text = { Text("将永久删除回收站中的图片和缩略图，删除后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onPurgeAll()
                    },
                ) {
                    Text("确认清理")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

// ---- 工具 ----

private enum class CleanupPage {
    Main,
    RecycleBin,
    Review,
}

private enum class ReviewCategory {
    All,
    Duplicate,
    Disposable,
}

private fun CleanupReviewBatch.selectedBytes(selectedIds: Set<Long>): Long {
    val images = duplicatePairs.flatMap { listOf(it.keepImage, it.deleteImage) } +
        disposableItems.map { it.image }
    return images
        .distinctBy { it.id }
        .filter { it.id in selectedIds }
        .sumOf { it.size }
}

private fun imageMeta(image: ImageEntity): String {
    val size = formatBytes(image.size)
    val dimensions = if (image.width > 0 && image.height > 0) {
        "${image.width}×${image.height}"
    } else {
        "未知尺寸"
    }
    val clarity = image.blurScore?.let { " · 清晰度 ${String.format("%.0f", it)}" } ?: ""
    return "$dimensions · $size$clarity"
}

private fun sourceTypeLabel(sourceType: String): String = when (sourceType) {
    "chatimg" -> "img"
    "chatraw" -> "raw"
    "chatthumb" -> "thumb"
    else -> sourceType.ifBlank { "其他" }
}

private val strategies = listOf(
    CleanupStrategy.Conservative,
    CleanupStrategy.Balanced,
    CleanupStrategy.Aggressive,
)

private fun strategyHint(s: CleanupStrategy): String = when (s) {
    CleanupStrategy.Conservative -> "仅推荐高置信度删除（删除分 ≥ 0.7）。最安全。"
    CleanupStrategy.Balanced -> "中等置信度（≥ 0.5）。覆盖大多数明显多余的文件。"
    CleanupStrategy.Aggressive -> "激进（≥ 0.3）。可能包含一些有价值的图片，请仔细审核。"
    is CleanupStrategy.Custom -> "自定义阈值 ${s.threshold}"
}

private fun analysisStatusText(
    state: AnalysisState,
    progress: com.xjw.qalbum.service.analysis.AnalysisProgress?,
): String = when (state) {
    AnalysisState.Idle -> "尚未运行分析。点击「开始分析」会逐步计算 hash、模糊度、重复组等。"
    AnalysisState.Running -> progress?.let { "${it.phase} · ${it.processed}/${it.total} ${it.msg ?: ""}" }
        ?: "运行中…"
    AnalysisState.Done -> "上次分析已完成"
    AnalysisState.Failed -> "上次分析失败：${progress?.msg ?: "未知"}"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.1f %s", value, units[exp])
}
