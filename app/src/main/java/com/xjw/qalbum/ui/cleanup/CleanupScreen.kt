package com.xjw.qalbum.ui.cleanup

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.ThumbnailTrashActivity
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.domain.model.CleanupPreset
import com.xjw.qalbum.service.analysis.AnalysisProgress
import com.xjw.qalbum.service.analysis.AnalysisState
import com.xjw.qalbum.service.cleanup.CleanupBatch
import com.xjw.qalbum.ui.anim.StaggeredEntrance
import com.xjw.qalbum.ui.anim.sharedAxisXEnter
import com.xjw.qalbum.ui.anim.sharedAxisXExit
import com.xjw.qalbum.ui.anim.sharedAxisXPopEnter
import com.xjw.qalbum.ui.anim.sharedAxisXPopExit
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCount
import com.xjw.qalbum.ui.common.formatDateTime
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedNavigationItem
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.WavyProgress
import com.xjw.qalbum.ui.components.ConnectedChoiceRow
import org.koin.androidx.compose.koinViewModel

private enum class CleanupPage { Main, Review, Group, RecycleBin }

/**
 * 清理页根：在「主页 / 审核网格 / 重复组详情 / 回收站」之间用 [page] 内部切换（非导航路由），
 * 共享同一个 [CleanupViewModel]。[page] 用 rememberSaveable：旋转 / 深色切换等配置变更重建
 * Activity 时留在当前子页；底部导航 saveState/restoreState，切 Tab 再回来同样恢复。
 * 进入非主页时通过 [onReviewModeChange] 通知外层隐藏底部导航栏做全屏专注。
 */
@Composable
fun CleanupScreen(
    contentPadding: PaddingValues,
    onOpenTags: () -> Unit,
    onOpenSources: () -> Unit,
    onReviewModeChange: (Boolean) -> Unit,
    viewModel: CleanupViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(CleanupPage.Main) }
    var groupId by rememberSaveable { mutableLongStateOf(0L) }
    // 进程重建后恢复上次未关闭的审核会话（批次已落库，勾选可用「按推荐勾选」重建）
    LaunchedEffect(Unit) { viewModel.ensureReviewSessionRestored() }
    // 批量任务跑在 appScope（比页面长寿），结果经共享 message 流回到任意存活页面
    LaunchedEffect(Unit) {
        viewModel.message.collect { msg ->
            if (msg != null) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                viewModel.consumeMessage()
            }
        }
    }
    // 审核网格的滚动 / 筛选 / 排序 / 列数提升到这里：进重复组详情再返回时不被 AnimatedContent 销毁。
    // 筛选 / 排序以批次 id 为 key：同批次内保持，换新批次才回到默认。
    val reviewGridState = rememberLazyGridState()
    val reviewBatchId = viewModel.reviewBatch.collectAsState().value?.batch?.id
    var reviewCategory by rememberSaveable(reviewBatchId) { mutableStateOf<String?>(null) }
    var reviewSort by rememberSaveable(reviewBatchId) { mutableStateOf(ReviewSort.RECOMMENDED) }
    var reviewColumns by rememberSaveable { mutableIntStateOf(3) }
    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    LaunchedEffect(page) { onReviewModeChange(page != CleanupPage.Main) }
    DisposableEffect(Unit) { onDispose { onReviewModeChange(false) } }
    BackHandler(enabled = page != CleanupPage.Main) {
        page = if (page == CleanupPage.Group) CleanupPage.Review else CleanupPage.Main
    }

    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState == CleanupPage.Main || (initialState == CleanupPage.Group && targetState == CleanupPage.Review)) {
                sharedAxisXPopEnter(slideSpec, fadeSpec) togetherWith sharedAxisXPopExit(slideSpec, fadeSpec)
            } else {
                sharedAxisXEnter(slideSpec, fadeSpec) togetherWith sharedAxisXExit(slideSpec, fadeSpec)
            }
        },
        label = "cleanupPage",
    ) { p ->
        when (p) {
            CleanupPage.Main -> CleanupMainScreen(
                contentPadding = contentPadding,
                viewModel = viewModel,
                onOpenReview = { page = CleanupPage.Review },
                onOpenRecycleBin = { page = CleanupPage.RecycleBin },
                onOpenTags = onOpenTags,
                onOpenSources = onOpenSources,
            )
            CleanupPage.Review -> CleanupReviewScreen(
                contentPadding = PaddingValues(0.dp),
                viewModel = viewModel,
                gridState = reviewGridState,
                category = reviewCategory,
                sort = reviewSort,
                columns = reviewColumns,
                onCategoryChange = { reviewCategory = it },
                onSortChange = { reviewSort = it },
                onColumnsChange = { reviewColumns = it },
                onBack = { page = CleanupPage.Main },
                onOpenGroup = { gid -> groupId = gid; page = CleanupPage.Group },
            )
            CleanupPage.Group -> CleanupReviewGroupScreen(
                groupId = groupId,
                viewModel = viewModel,
                onBack = { page = CleanupPage.Review },
            )
            CleanupPage.RecycleBin -> CleanupRecycleBinScreen(
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                viewModel = viewModel,
                onBack = { page = CleanupPage.Main },
            )
        }
    }

    // 清理进度模态提升到根部：清理进行中无论处于哪个子页（含重建后落回主页）都保持输入屏蔽
    val purgeProgress by viewModel.purgeProgress.collectAsState()
    purgeProgress?.let { PurgeProgressDialog(it) }
}

/**
 * 清理主页：分析卡 + 清理策略 + 审核入口（有批次时）+ 回收站 / QQ 缩略图 / 按来源清理 / 标签入口。
 */
@Composable
fun CleanupMainScreen(
    contentPadding: PaddingValues,
    viewModel: CleanupViewModel,
    onOpenReview: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val context = LocalContext.current
    val analysisState by viewModel.analysisState.collectAsState()
    val analysisProgress by viewModel.analysisProgress.collectAsState()
    val analysisOverview by viewModel.analysisOverview.collectAsState()
    val fullyAnalyzed by viewModel.fullyAnalyzedCount.collectAsState()
    val cleanupConfig by viewModel.cleanupConfig.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val lastBatch by viewModel.lastBatch.collectAsState()
    val selectedReviewDeleteIds by viewModel.selectedReviewDeleteIds.collectAsState()
    val quarantineStats by viewModel.quarantineStats.collectAsState()
    val thumbnailTrashSummary by viewModel.thumbnailTrashSummary.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("清理") },
                subtitle = { Text("先审核、再移入回收站，误删可恢复") },
                actions = {
                    IconButton(onClick = onOpenTags, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = "标签")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StaggeredEntrance(0) {
                AnalysisCard(
                    state = analysisState,
                    progress = analysisProgress,
                    total = analysisOverview.totalImages,
                    analyzed = fullyAnalyzed,
                    hashedAny = analysisOverview.hashDone > 0,
                    onStart = viewModel::startAnalysis,
                    onCancel = viewModel::cancelAnalysis,
                )
            }
            StaggeredEntrance(1) {
                StrategyCard(
                    config = cleanupConfig,
                    busy = busy,
                    onSelectPreset = viewModel::applyPreset,
                    onGenerate = { viewModel.generateBatch(onReady = onOpenReview) },
                    onRecluster = {
                        // 重聚类在 pipeline 自有 scope 后台跑（页面销毁不中断），进行中状态由分析卡展示
                        viewModel.reclusterDuplicates {
                            Toast.makeText(context, "已开始后台重新查重", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            AnimatedVisibility(
                visible = lastBatch != null,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(),
            ) {
                lastBatch?.let { batch ->
                    ReviewSummaryCard(
                        batch = batch,
                        selectedCount = selectedReviewDeleteIds.size,
                        busy = busy,
                        onOpen = onOpenReview,
                        onClose = viewModel::closeReview,
                    )
                }
            }
            StaggeredEntrance(2) {
                SegmentedGroup(header = "更多") {
                    item("recycle") { position ->
                        SegmentedNavigationItem(
                            position = position,
                            icon = Icons.Outlined.Inventory2,
                            title = "回收站",
                            subtitle = "${quarantineStats.count} 项 · ${formatBytes(quarantineStats.bytes)}，到期自动永久删除",
                            onClick = onOpenRecycleBin,
                        )
                    }
                    item("sources") { position ->
                        SegmentedNavigationItem(
                            position = position,
                            icon = Icons.Outlined.Forum,
                            title = "按聊天来源清理",
                            subtitle = "按群聊、成员查看占用空间并批量清理",
                            onClick = onOpenSources,
                        )
                    }
                    item("thumbs") { position ->
                        ThumbnailEntryItem(
                            position = position,
                            count = thumbnailTrashSummary.count,
                            bytes = thumbnailTrashSummary.bytes,
                            onOpen = { context.startActivity(Intent(context, ThumbnailTrashActivity::class.java)) },
                        )
                    }
                }
            }
        }
    }
}

/** 分析卡片主按钮的语义状态（抽出便于单测） */
internal enum class AnalysisAction { START, CONTINUE, ALL_DONE, CANCEL, NO_IMAGES }

/**
 * 主按钮判定：运行中→取消；无图→禁用；已分析达到总数→已全部分析；分析过→继续；否则→开始。
 */
internal fun analysisAction(
    state: AnalysisState,
    total: Int,
    analyzed: Int,
    hashedAny: Boolean,
): AnalysisAction = when {
    state == AnalysisState.Running -> AnalysisAction.CANCEL
    total == 0 -> AnalysisAction.NO_IMAGES
    analyzed >= total -> AnalysisAction.ALL_DONE
    hashedAny -> AnalysisAction.CONTINUE
    else -> AnalysisAction.START
}

@Composable
private fun CleanupCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainer,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(icon, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun AnalysisCard(
    state: AnalysisState,
    progress: AnalysisProgress?,
    total: Int,
    analyzed: Int,
    hashedAny: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val fraction = if (total > 0) analyzed.toFloat() / total else 0f
    val action = analysisAction(state, total, analyzed, hashedAny)

    CleanupCard(
        title = "分析图片",
        icon = Icons.Outlined.AutoAwesome,
        trailing = {
            AnimatedContent(targetState = action, label = "analysisAction") { a ->
                when (a) {
                    AnalysisAction.CANCEL -> OutlinedButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text("停止") }
                    AnalysisAction.ALL_DONE -> FilledTonalButton(onClick = {}, enabled = false, shapes = ButtonDefaults.shapes()) { Text("已完成") }
                    AnalysisAction.NO_IMAGES -> Button(onClick = {}, enabled = false, shapes = ButtonDefaults.shapes()) { Text("开始") }
                    AnalysisAction.CONTINUE -> Button(onClick = onStart, shapes = ButtonDefaults.shapes()) { Text("继续") }
                    AnalysisAction.START -> Button(onClick = onStart, shapes = ButtonDefaults.shapes()) { Text("开始") }
                }
            }
        },
    ) {
        if (total > 0) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "已分析 ${formatCount(analyzed)} / ${formatCount(total)} 张",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            WavyProgress(fraction = fraction)
        } else {
            Text("扫描图片后即可开始分析", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AnimatedVisibility(
            visible = state == AnalysisState.Running,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                val phaseLabel = progress?.phase?.displayName ?: "准备中"
                Text(
                    when {
                        progress == null -> "准备中…"
                        progress.total > 0 -> "正在$phaseLabel · ${progress.processed}/${progress.total}"
                        else -> "正在$phaseLabel…${progress.msg?.let { " $it" } ?: ""}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                WavyProgress(fraction = progress?.takeIf { it.total > 0 }?.let { it.processed.toFloat() / it.total })
            }
        }

        if (state == AnalysisState.Failed) {
            Text(
                "上次分析失败：${progress?.msg ?: "未知错误"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (action == AnalysisAction.ALL_DONE) {
            Text(
                "基础分析已全部完成。需要重新分析时，到「设置 → 分析」重置完成标记。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun StrategyCard(
    config: CleanupConfig,
    busy: Boolean,
    onSelectPreset: (CleanupPreset) -> Unit,
    onGenerate: () -> Unit,
    onRecluster: () -> Unit,
) {
    val activePreset = CleanupPreset.match(config)
    CleanupCard(title = "清理策略", icon = Icons.Outlined.CleaningServices) {
        ConnectedChoiceRow(
            options = CleanupPreset.entries.toList(),
            selected = activePreset,
            label = { it.displayName },
            onSelect = onSelectPreset,
        )
        Spacer(Modifier.height(12.dp))
        AnimatedContent(targetState = activePreset, label = "presetHint") { preset ->
            Text(
                text = presetHint(preset),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onGenerate, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Outlined.RateReview, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("生成审核清单")
            }
            OutlinedButton(onClick = onRecluster, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("重新查重") }
        }
    }
}

@Composable
private fun ReviewSummaryCard(
    batch: CleanupBatch,
    selectedCount: Int,
    busy: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    CleanupCard(
        title = "待审核清单",
        icon = Icons.Outlined.RateReview,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            "共 ${batch.report.totalCount} 项 · ${formatBytes(batch.report.totalSize)}" +
                if (selectedCount > 0) " · 已勾选 $selectedCount" else "",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        batch.report.byReason.entries.forEach { (reason, group) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(reason, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${group.count} 项 · ${formatBytes(group.size)}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpen, shapes = ButtonDefaults.shapes()) { Text("继续审核") }
            // 批量清理（appScope 上跑）期间禁止关闭审核：避免关闭后过期结果与主页状态打架
            OutlinedButton(onClick = onClose, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("关闭清单") }
        }
    }
}

/** QQ 缩略图（chatthumb）入口：只浏览、受保护 */
@Composable
internal fun ThumbnailEntryItem(
    position: SegmentPosition,
    count: Int,
    bytes: Long,
    onOpen: () -> Unit,
) {
    SegmentedNavigationItem(
        position = position,
        icon = Icons.Outlined.Shield,
        title = "QQ 缩略图（受保护）",
        subtitle = "$count 项 · ${formatBytes(bytes)}，QQ 聊天里的图片预览靠它显示，只可浏览",
        onClick = onOpen,
        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

/** 清理 / 移入回收站进度对话框：完成后由调用方置空自动消失，不可手动关闭 */
@Composable
internal fun PurgeProgressDialog(progress: PurgeProgress) {
    val fraction = if (progress.total > 0) (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) else 0f
    AlertDialog(
        onDismissRequest = { },
        confirmButton = { },
        icon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
        title = { Text(progress.label) },
        text = {
            Column {
                Text("已处理 ${progress.done} / ${progress.total} 项", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                WavyProgress(fraction = fraction)
            }
        },
    )
}

private fun presetHint(preset: CleanupPreset?): String = when (preset) {
    CleanupPreset.CONSERVATIVE -> "保守：只审核重复、损坏和空白图片。"
    CleanupPreset.BALANCED -> "平衡：再加上低分辨率、模糊和表情包，覆盖大多数明显多余的图片。"
    CleanupPreset.AGGRESSIVE -> "激进：开启全部维度并放宽门限，可能混入有价值的图片，请仔细审核。"
    null -> "自定义：可在「设置 → 清理规则」中逐项调整开关和门限。"
}

internal fun formatQuarantineDate(ts: Long): String = formatDateTime(ts)

internal fun autoDeleteText(expiresAt: Long): String {
    val remaining = expiresAt - System.currentTimeMillis()
    if (remaining <= 0) return "即将清理"
    val days = remaining / 86_400_000L
    return if (days >= 1) "$days 天后" else "不到 1 天"
}
