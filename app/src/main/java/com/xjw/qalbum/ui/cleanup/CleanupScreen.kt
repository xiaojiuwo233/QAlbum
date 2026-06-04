package com.xjw.qalbum.ui.cleanup

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.ThumbnailTrashActivity
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.service.analysis.AnalysisState
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.domain.model.CleanupPreset
import com.xjw.qalbum.service.cleanup.CleanupBatch
import com.xjw.qalbum.ui.anim.sharedAxisXEnter
import com.xjw.qalbum.ui.anim.sharedAxisXExit
import com.xjw.qalbum.ui.anim.sharedAxisXPopEnter
import com.xjw.qalbum.ui.anim.sharedAxisXPopExit
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import org.koin.androidx.compose.koinViewModel
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val analysisOverview by viewModel.analysisOverview.collectAsState()
    val cleanupConfig by viewModel.cleanupConfig.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val lastBatch by viewModel.lastBatch.collectAsState()
    val reviewBatch by viewModel.reviewBatch.collectAsState()
    val selectedReviewDeleteIds by viewModel.selectedReviewDeleteIds.collectAsState()
    val recycleBinItems by viewModel.quarantine.collectAsState()
    val thumbnailTrashSummary by viewModel.thumbnailTrashSummary.collectAsState()
    val context = LocalContext.current
    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

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
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        AnimatedContent(
            targetState = activePage,
            transitionSpec = {
                if (targetState == CleanupPage.Main.name) {
                    sharedAxisXPopEnter(slideSpec, fadeSpec) togetherWith sharedAxisXPopExit(slideSpec, fadeSpec)
                } else {
                    sharedAxisXEnter(slideSpec, fadeSpec) togetherWith sharedAxisXExit(slideSpec, fadeSpec)
                }
            },
            label = "cleanupPage",
        ) { page ->
        if (page == CleanupPage.Review.name && reviewBatch != null) {
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
        } else if (page == CleanupPage.RecycleBin.name) {
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
                    overview = analysisOverview,
                    onStart = viewModel::startAnalysis,
                    onCancel = viewModel::cancelAnalysis,
                )

                StrategyCard(
                    config = cleanupConfig,
                    busy = busy,
                    onSelectPreset = viewModel::applyPreset,
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

/** 分析卡片主按钮的语义状态（抽出便于单测） */
internal enum class AnalysisAction { START, CONTINUE, ALL_DONE, CANCEL, NO_IMAGES }

/** 已完成「基础分析」(哈希/质量 + OCR) 的图片数 = 总数 − 待基础分析数 */
internal fun analyzedCount(overview: ImageDao.AnalysisProgressRow): Int =
    (overview.totalImages - overview.pendingBase).coerceAtLeast(0)

/**
 * 主按钮三态判定：
 * 运行中→取消；无图→禁用；基础全完成→已全部分析；分析过→继续；否则→开始。
 * 「是否分析过」以 hashDone>0 判断（hash 是所有图的必经第一阶段）。
 */
internal fun analysisAction(
    state: AnalysisState,
    overview: ImageDao.AnalysisProgressRow,
): AnalysisAction = when {
    state == AnalysisState.Running -> AnalysisAction.CANCEL
    overview.totalImages == 0 -> AnalysisAction.NO_IMAGES
    overview.pendingBase == 0 -> AnalysisAction.ALL_DONE
    overview.hashDone > 0 -> AnalysisAction.CONTINUE
    else -> AnalysisAction.START
}

@Composable
private fun AnalysisCard(
    state: AnalysisState,
    progress: com.xjw.qalbum.service.analysis.AnalysisProgress?,
    overview: ImageDao.AnalysisProgressRow,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val total = overview.totalImages
    val analyzed = analyzedCount(overview)
    val fraction = if (total > 0) analyzed.toFloat() / total else 0f
    val action = analysisAction(state, overview)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "分析图片",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                when (action) {
                    AnalysisAction.CANCEL ->
                        OutlinedButton(onClick = onCancel) { Text("取消") }
                    AnalysisAction.ALL_DONE ->
                        Button(onClick = {}, enabled = false) { Text("已全部分析") }
                    AnalysisAction.NO_IMAGES ->
                        Button(onClick = {}, enabled = false) { Text("开始分析") }
                    AnalysisAction.CONTINUE ->
                        Button(onClick = onStart) { Text("继续分析") }
                    AnalysisAction.START ->
                        Button(onClick = onStart) { Text("开始分析") }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 总进度（常驻）：让用户随时看到「已分析多少、还剩多少」
            if (total > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "已分析 ${formatCount(analyzed)} / ${formatCount(total)} 张",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.extraSmall),
                )
            } else {
                Text(
                    "扫描图片后即可开始分析",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 运行态：当前阶段 + 本批进度（实时，来自内存 StateFlow）
            if (state == AnalysisState.Running) {
                Spacer(Modifier.height(12.dp))
                val phaseLabel = progress?.phase?.displayName ?: "准备中"
                val detail = when {
                    progress == null -> "准备中…"
                    progress.total > 0 -> "正在$phaseLabel · ${progress.processed}/${progress.total}"
                    else -> "正在$phaseLabel…${progress.msg?.let { " $it" } ?: ""}"
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                val batchFraction = progress
                    ?.takeIf { it.total > 0 }
                    ?.let { it.processed.toFloat() / it.total }
                if (batchFraction != null) {
                    LinearProgressIndicator(
                        progress = { batchFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.extraSmall),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.extraSmall),
                    )
                }
            }

            // 失败态
            if (state == AnalysisState.Failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "上次分析失败：${progress?.msg ?: "未知错误"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 全部完成引导（重置入口仍在设置页，这里只做文字引导）
            if (action == AnalysisAction.ALL_DONE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "基础分析已全部完成。如需重跑，可到 设置 → 分析 → 重置完成标记。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StrategyCard(
    config: CleanupConfig,
    busy: Boolean,
    onSelectPreset: (CleanupPreset) -> Unit,
    onGenerate: () -> Unit,
) {
    val activePreset = CleanupPreset.match(config)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
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
                CleanupPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = activePreset == preset,
                        onClick = { onSelectPreset(preset) },
                        label = { Text(preset.displayName) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = presetHint(activePreset),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
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
                MaterialTheme.colorScheme.surfaceContainerHigh
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
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
    val totalBytes = items.sumOf { it.size } + thumbnailSummary.bytes
    var showClearConfirm by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<QuarantineEntity?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
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
            RecycleBinHeader(
                count = totalCount,
                bytes = totalBytes,
                busy = busy,
                onBack = onBack,
                onClear = { showClearConfirm = true },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ThumbnailEntryCard(
                summary = thumbnailSummary,
                onOpen = onOpenThumbnails,
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

    preview?.let { record ->
        QuarantinePreviewDialog(
            record = record,
            busy = busy,
            onClose = { preview = null },
            onRestore = {
                onRestore(record.id)
                preview = null
            },
            onPurge = {
                onPurge(record.id)
                preview = null
            },
        )
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

@Composable
private fun RecycleBinHeader(
    count: Int,
    bytes: Long,
    busy: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "回收站",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "$count 项 · ${formatBytes(bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onClear,
            enabled = count > 0 && !busy,
        ) {
            Text("一键清理")
        }
    }
}

@Composable
private fun QuarantineTile(
    record: QuarantineEntity,
    onClick: () -> Unit,
) {
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
                model = ImageRequest.Builder(context)
                    .data(File(thumb))
                    .crossfade(true)
                    .build(),
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
            Text(
                record.reason,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val thumb = record.thumbnailPath
            if (thumb != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(thumb))
                        .crossfade(true)
                        .build(),
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
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                        ),
                    )
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
                    Text(
                        "删除原因",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(
                            record.reason,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                HorizontalDivider()
                QuarantineInfoRow("大小", formatBytes(record.size))
                QuarantineInfoRow("自动删除", autoDeleteText(record.expiresAt))
                QuarantineInfoRow(
                    "原位置",
                    record.originalPath.substringBeforeLast('/').ifBlank { record.originalPath },
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onRestore,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
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
                TextButton(
                    onClick = {
                        confirmPurge = false
                        onPurge()
                    },
                ) { Text("永久删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurge = false }) { Text("取消") }
            },
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

private fun presetHint(preset: CleanupPreset?): String = when (preset) {
    CleanupPreset.CONSERVATIVE -> "保守：只清理重复 / 损坏 / 空白，最安全。"
    CleanupPreset.BALANCED -> "平衡：在保守基础上加入低分辨率 / 模糊，覆盖大多数明显多余的图。"
    CleanupPreset.AGGRESSIVE -> "激进：全部维度开启且门限放宽，可能含有价值的图，请仔细审核。"
    null -> "自定义规则：可在「设置 → 清理规则」里逐项调整开关与门限。"
}

private fun formatCount(n: Int): String =
    if (n >= 10_000) "%.1f万".format(n / 10_000f) else n.toString()

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.1f %s", value, units[exp])
}

private val quarantineDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

private fun formatQuarantineDate(ts: Long): String = quarantineDateFormatter.format(Date(ts))

private fun autoDeleteText(expiresAt: Long): String {
    val remaining = expiresAt - System.currentTimeMillis()
    if (remaining <= 0) return "即将清理"
    val days = remaining / 86_400_000L
    return if (days >= 1) "$days 天后" else "不到 1 天"
}
