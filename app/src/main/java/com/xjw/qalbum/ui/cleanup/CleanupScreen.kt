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
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.xjw.qalbum.service.cleanup.CleanupRules
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
    onOpenTags: () -> Unit = {},
    viewModel: CleanupViewModel = koinViewModel(),
) {
    val analysisState by viewModel.analysisState.collectAsState()
    val analysisProgress by viewModel.analysisProgress.collectAsState()
    val analysisOverview by viewModel.analysisOverview.collectAsState()
    val includeOcrPending by viewModel.includeOcrPending.collectAsState()
    val cleanupConfig by viewModel.cleanupConfig.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val lastBatch by viewModel.lastBatch.collectAsState()
    val reviewBatch by viewModel.reviewBatch.collectAsState()
    val selectedReviewDeleteIds by viewModel.selectedReviewDeleteIds.collectAsState()
    val recycleBinItems by viewModel.quarantine.collectAsState()
    val thumbnailTrashSummary by viewModel.thumbnailTrashSummary.collectAsState()
    val purgeProgress by viewModel.purgeProgress.collectAsState()
    val context = LocalContext.current
    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    val recycleBinCount = recycleBinItems.size + thumbnailTrashSummary.count
    val recycleBinBytes = recycleBinItems.sumOf { it.size } + thumbnailTrashSummary.bytes

    var activePage by rememberSaveable { mutableStateOf(CleanupPage.Main.name) }
    LaunchedEffect(reviewBatch?.batch?.id) {
        if (reviewBatch != null) activePage = CleanupPage.Review.name
    }

    // 系统返回：在审核 / 回收站子页时拦截，回到清理主页，而不是退出整个清理 Tab 跳回应用首页。
    // 这样审核卡片（lastBatch）也会保留在主页上，可随时再次进入审核。
    BackHandler(enabled = activePage != CleanupPage.Main.name) {
        activePage = CleanupPage.Main.name
    }

    AnimatedContent(
        targetState = activePage,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState == CleanupPage.Main.name) {
                sharedAxisXPopEnter(slideSpec, fadeSpec) togetherWith sharedAxisXPopExit(slideSpec, fadeSpec)
            } else {
                sharedAxisXEnter(slideSpec, fadeSpec) togetherWith sharedAxisXExit(slideSpec, fadeSpec)
            }
        },
        label = "cleanupPage",
    ) { page ->
        when {
            page == CleanupPage.Review.name && reviewBatch != null -> ReviewCleanupSection(
                review = reviewBatch!!,
                selectedDeleteIds = selectedReviewDeleteIds,
                busy = busy,
                contentPadding = contentPadding,
                onBack = { activePage = CleanupPage.Main.name },
                onToggleSelect = viewModel::setReviewItemSelected,
                onSelectIds = viewModel::selectReviewIds,
                onDeselectIds = viewModel::deselectReviewIds,
                onInvertIds = viewModel::invertReviewIds,
                onApply = {
                    viewModel.applyReviewSelection { count ->
                        Toast.makeText(context, "已移入回收站 $count 张", Toast.LENGTH_SHORT).show()
                        activePage = CleanupPage.Main.name
                    }
                },
            )

            page == CleanupPage.RecycleBin.name -> RecycleBinSection(
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
                contentPadding = contentPadding,
            )

            else -> MainCleanupSection(
                contentPadding = contentPadding,
                analysisState = analysisState,
                analysisProgress = analysisProgress,
                analysisOverview = analysisOverview,
                includeOcrPending = includeOcrPending,
                cleanupConfig = cleanupConfig,
                busy = busy,
                lastBatch = lastBatch,
                reviewSelectedCount = selectedReviewDeleteIds.size,
                recycleBinCount = recycleBinCount,
                recycleBinBytes = recycleBinBytes,
                onStartAnalysis = viewModel::startAnalysis,
                onCancelAnalysis = viewModel::cancelAnalysis,
                onSelectPreset = viewModel::applyPreset,
                onGenerate = viewModel::generateBatch,
                onOpenReview = { activePage = CleanupPage.Review.name },
                onOpenRecycleBin = { activePage = CleanupPage.RecycleBin.name },
                onOpenTags = onOpenTags,
            )
        }
    }

    // 进度对话框提到顶层：移入回收站（审核页）/ 一键清理（回收站页）共用，覆盖任意子页
    purgeProgress?.let { PurgeProgressDialog(it) }
}

/** 清理主页：分析卡片 + 清理策略 + 审核清理卡片（条件） + 回收站入口 */
@Composable
private fun MainCleanupSection(
    contentPadding: PaddingValues,
    analysisState: AnalysisState,
    analysisProgress: com.xjw.qalbum.service.analysis.AnalysisProgress?,
    analysisOverview: ImageDao.AnalysisProgressRow,
    includeOcrPending: Boolean,
    cleanupConfig: CleanupConfig,
    busy: Boolean,
    lastBatch: CleanupBatch?,
    reviewSelectedCount: Int,
    recycleBinCount: Int,
    recycleBinBytes: Long,
    onStartAnalysis: () -> Unit,
    onCancelAnalysis: () -> Unit,
    onSelectPreset: (CleanupPreset) -> Unit,
    onGenerate: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    onOpenTags: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("清理", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenTags) {
                        Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = "标签")
                    }
                },
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
                includeOcrPending = includeOcrPending,
                onStart = onStartAnalysis,
                onCancel = onCancelAnalysis,
            )

            StrategyCard(
                config = cleanupConfig,
                busy = busy,
                onSelectPreset = onSelectPreset,
                onGenerate = onGenerate,
            )

            lastBatch?.let { batch ->
                ReviewSummaryCard(
                    batch = batch,
                    selectedCount = reviewSelectedCount,
                    onOpen = onOpenReview,
                )
            }

            RecycleBinSummaryCard(
                count = recycleBinCount,
                bytes = recycleBinBytes,
                onOpen = onOpenRecycleBin,
            )
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
internal fun analyzedCount(
    overview: ImageDao.AnalysisProgressRow,
    includeOcrPending: Boolean = true,
): Int {
    val pending = if (includeOcrPending) {
        overview.pendingBase
    } else {
        (overview.totalImages - overview.hashDone).coerceAtLeast(0)
    }
    return (overview.totalImages - pending).coerceAtLeast(0)
}

/**
 * 主按钮三态判定：
 * 运行中→取消；无图→禁用；基础全完成→已全部分析；分析过→继续；否则→开始。
 * 「是否分析过」以 hashDone>0 判断（hash 是所有图的必经第一阶段）。
 */
internal fun analysisAction(
    state: AnalysisState,
    overview: ImageDao.AnalysisProgressRow,
    includeOcrPending: Boolean = true,
): AnalysisAction = when {
    state == AnalysisState.Running -> AnalysisAction.CANCEL
    overview.totalImages == 0 -> AnalysisAction.NO_IMAGES
    analyzedCount(overview, includeOcrPending) >= overview.totalImages -> AnalysisAction.ALL_DONE
    overview.hashDone > 0 -> AnalysisAction.CONTINUE
    else -> AnalysisAction.START
}

@Composable
private fun AnalysisCard(
    state: AnalysisState,
    progress: com.xjw.qalbum.service.analysis.AnalysisProgress?,
    overview: ImageDao.AnalysisProgressRow,
    includeOcrPending: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val total = overview.totalImages
    val analyzed = analyzedCount(overview, includeOcrPending)
    val fraction = if (total > 0) analyzed.toFloat() / total else 0f
    val action = analysisAction(state, overview, includeOcrPending)

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
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onToggleSelect: (Long, Boolean) -> Unit,
    onSelectIds: (Collection<Long>) -> Unit,
    onDeselectIds: (Collection<Long>) -> Unit,
    onInvertIds: (Collection<Long>) -> Unit,
    onApply: () -> Unit,
) {
    var category by rememberSaveable(review.batch.id) { mutableStateOf(ReviewCategory.All.name) }
    var columns by rememberSaveable(review.batch.id) { mutableIntStateOf(3) }
    var preview by remember { mutableStateOf<ImageEntity?>(null) }
    var showConfirm by remember { mutableStateOf(false) }

    // 各分类计数（用于筛选 chip 上的数字，以及决定是否展示某个 chip）
    val countByCategory = remember(review.tiles) {
        review.tiles.groupingBy { it.reviewCategory() }.eachCount()
    }
    // 当前筛选下可见的格子；分类顺序固定：重复 → 低分辨率 → 极小文件 → 无用
    val current = ReviewCategory.fromName(category)
    val visibleTiles = remember(review.tiles, category) {
        review.tiles.filter { current == ReviewCategory.All || it.reviewCategory() == current }
    }
    val visibleIds = remember(visibleTiles) { visibleTiles.map { it.image.id } }
    val selectedBytes = review.selectedBytes(selectedDeleteIds)

    Scaffold(
        topBar = {
            ReviewTopBar(
                selectedCount = selectedDeleteIds.size,
                selectedBytes = selectedBytes,
                busy = busy,
                category = category,
                countByCategory = countByCategory,
                totalCount = review.tiles.size,
                onBack = onBack,
                onSelectCategory = { category = it.name },
                onSelectAll = { onSelectIds(visibleIds) },
                onSelectNone = { onDeselectIds(visibleIds) },
                onInvert = { onInvertIds(visibleIds) },
                onApply = { if (selectedDeleteIds.isNotEmpty() && !busy) showConfirm = true },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        if (review.tiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPadding.calculateTopPadding()),
                contentAlignment = Alignment.Center,
            ) {
                Text("没有需要审核的清理项", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        val orderedCategories = listOf(
            ReviewCategory.Duplicate,
            ReviewCategory.LowRes,
            ReviewCategory.TinyFile,
            ReviewCategory.Useless,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding())
                .reviewPinchColumns(columns) { columns = it },
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = 10.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            orderedCategories.forEach { cat ->
                val catTiles = visibleTiles.filter { it.reviewCategory() == cat }
                if (catTiles.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "label-${cat.name}") {
                        ReviewSectionLabel(cat.label, catTiles.size, reviewCategoryHint(cat))
                    }
                    items(
                        items = catTiles,
                        key = { "tile-${it.image.id}" },
                    ) { tile ->
                        ReviewTileItem(
                            tile = tile,
                            selected = tile.image.id in selectedDeleteIds,
                            onToggle = { onToggleSelect(tile.image.id, tile.image.id !in selectedDeleteIds) },
                            onLongPress = { preview = tile.image },
                        )
                    }
                }
            }
        }
    }

    preview?.let { image ->
        ReviewPreviewDialog(image = image, onClose = { preview = null })
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("移入回收站？") },
            text = {
                Text(
                    "本次将清理 ${selectedDeleteIds.size} 张，共 ${formatBytes(selectedBytes)}。" +
                        "移入回收站后仍可在回收站恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onApply()
                }) { Text("移入回收站") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 审核页固定顶栏：返回 + 标题/已选信息 + 移入回收站 + 筛选 chip + 全选/反选/全不选（下滑不消失） */
@Composable
private fun ReviewTopBar(
    selectedCount: Int,
    selectedBytes: Long,
    busy: Boolean,
    category: String,
    countByCategory: Map<ReviewCategory, Int>,
    totalCount: Int,
    onBack: () -> Unit,
    onSelectCategory: (ReviewCategory) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onInvert: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            // 第一行：返回 + 标题/已选 + 移入回收站
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 6.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
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
                FilledTonalButton(
                    onClick = onApply,
                    enabled = selectedCount > 0 && !busy,
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("移入回收站")
                }
            }
            // 第二行：筛选 chip（横向滚动）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = category == ReviewCategory.All.name,
                    onClick = { onSelectCategory(ReviewCategory.All) },
                    label = { Text("全部 $totalCount") },
                )
                listOf(
                    ReviewCategory.Duplicate,
                    ReviewCategory.LowRes,
                    ReviewCategory.TinyFile,
                    ReviewCategory.Useless,
                ).forEach { cat ->
                    val count = countByCategory[cat] ?: 0
                    if (count > 0) {
                        FilterChip(
                            selected = category == cat.name,
                            onClick = { onSelectCategory(cat) },
                            label = { Text("${cat.label} $count") },
                        )
                    }
                }
            }
            // 第三行：全选 / 反选 / 全不选
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSelectAll) { Text("全选") }
                TextButton(onClick = onInvert) { Text("反选") }
                TextButton(onClick = onSelectNone) { Text("全不选") }
            }
        }
    }
}

@Composable
private fun ReviewSectionLabel(title: String, count: Int, subtitle: String) {
    Column(modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 2.dp)) {
        Text(
            "$title · $count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 审核格子：方形容器内用 [ContentScale.Fit] 完整展示图片（非正方形留黑边，避免裁切丢信息），
 * 点击切换勾选、长按全屏预览。重复组的最优图标「建议保留」，其余标「建议删除」。
 */
@Composable
private fun ReviewTileItem(
    tile: ReviewTile,
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
            model = ImageRequest.Builder(context)
                .data(ImageEntityModel(tile.image))
                .crossfade(true)
                .build(),
            contentDescription = tile.image.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.20f)),
            )
        }
        // 底部原因 / 建议标签
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                reviewTileBadge(tile),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 选择圈
        Icon(
            if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.36f),
                ),
            tint = Color.White,
        )
    }
}

/** 审核页长按全屏预览：完整展示（Fit），点任意处关闭 */
@Composable
private fun ReviewPreviewDialog(
    image: ImageEntity,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
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
                        image.filename.ifBlank { "图片" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        imageMeta(image),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
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
                        Text(
                            "回收站",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "$totalCount 项 · ${formatBytes(totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = totalCount > 0 && !busy,
                    ) { Text("一键清理") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding()),
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

/** 一键清理进度对话框：清理期间显示，完成后由调用方把进度置空自动消失，不可手动关闭 */
@Composable
internal fun PurgeProgressDialog(progress: PurgeProgress) {
    val fraction = if (progress.total > 0) {
        (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
    } else {
        0f
    }
    AlertDialog(
        onDismissRequest = { },
        confirmButton = { },
        title = { Text(progress.label) },
        text = {
            Column {
                Text(
                    "已清理 ${progress.done} / ${progress.total} 项",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.extraSmall),
                )
            }
        },
    )
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

/** 审核页筛选分类：低分辨率 / 极小文件从「无用」中拆出，单独成 tab */
private enum class ReviewCategory(val label: String) {
    All("全部"),
    Duplicate("重复"),
    LowRes("低分辨率"),
    TinyFile("极小文件"),
    Useless("无用");

    companion object {
        fun fromName(name: String): ReviewCategory =
            entries.firstOrNull { it.name == name } ?: All
    }
}

/** 把一个 tile 归到某个筛选分类 */
private fun ReviewTile.reviewCategory(): ReviewCategory = when {
    duplicateGroupId != null -> ReviewCategory.Duplicate
    reason == CleanupRules.REASON_LOW_RESOLUTION -> ReviewCategory.LowRes
    reason == CleanupRules.REASON_TINY_FILE -> ReviewCategory.TinyFile
    else -> ReviewCategory.Useless
}

/** 分组小标题下的说明文案 */
private fun reviewCategoryHint(category: ReviewCategory): String = when (category) {
    ReviewCategory.Duplicate -> "同组可任意勾选，默认选中非最优的几张"
    ReviewCategory.LowRes -> "分辨率过低的图"
    ReviewCategory.TinyFile -> "文件极小的图"
    ReviewCategory.Useless -> "损坏、空白或模糊的图"
    ReviewCategory.All -> ""
}

/** 格子底部标签：重复组标注建议保留 / 建议删除，其余直接显示原因 */
private fun reviewTileBadge(tile: ReviewTile): String = when {
    tile.duplicateGroupId != null -> if (tile.recommendedDelete) "重复·建议删除" else "重复·建议保留"
    else -> tile.reason
}

private fun CleanupReviewBatch.selectedBytes(selectedIds: Set<Long>): Long =
    tiles
        .distinctBy { it.image.id }
        .filter { it.image.id in selectedIds }
        .sumOf { it.image.size }

/** 审核网格的双指捏合调列数（与图库一致）：放大减列、缩小加列，范围 2–6 列 */
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
                        ratio > 1.16f -> {
                            onColumns((columns - 1).coerceIn(2, 6))
                            changed = true
                        }
                        ratio < 0.86f -> {
                            onColumns((columns + 1).coerceIn(2, 6))
                            changed = true
                        }
                    }
                }
            }
        }
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
