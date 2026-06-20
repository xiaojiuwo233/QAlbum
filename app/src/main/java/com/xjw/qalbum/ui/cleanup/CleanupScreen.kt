package com.xjw.qalbum.ui.cleanup

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import com.xjw.qalbum.ui.anim.sharedAxisXEnter
import com.xjw.qalbum.ui.anim.sharedAxisXExit
import com.xjw.qalbum.ui.anim.sharedAxisXPopEnter
import com.xjw.qalbum.ui.anim.sharedAxisXPopExit
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.domain.model.CleanupPreset
import com.xjw.qalbum.service.analysis.AnalysisState
import com.xjw.qalbum.service.cleanup.CleanupBatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class CleanupPage { Main, Review, Group, RecycleBin }

/**
 * 清理页根：在「主页 / 审核网格 / 重复组详情 / 回收站」之间用 [page] 内部切换（非导航路由），
 * 共享同一个 [CleanupViewModel]。[page] 用 remember（非 saveable），从其它 Tab 切回时回到主页、
 * 不会自动停在审核页；审核状态由 ViewModel 保留，可从主页入口卡再次进入。
 * 进入非主页时通过 [onReviewModeChange] 通知外层隐藏底部导航栏做全屏专注。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CleanupScreen(
    contentPadding: PaddingValues,
    onOpenTags: () -> Unit,
    onReviewModeChange: (Boolean) -> Unit,
    viewModel: CleanupViewModel = koinViewModel(),
) {
    var page by remember { mutableStateOf(CleanupPage.Main) }
    var groupId by remember { mutableLongStateOf(0L) }
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
            if (targetState == CleanupPage.Main) {
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
            )
            CleanupPage.Review -> CleanupReviewScreen(
                contentPadding = contentPadding,
                viewModel = viewModel,
                onBack = { page = CleanupPage.Main },
                onOpenGroup = { gid -> groupId = gid; page = CleanupPage.Group },
            )
            CleanupPage.Group -> CleanupReviewGroupScreen(
                groupId = groupId,
                viewModel = viewModel,
                onBack = { page = CleanupPage.Review },
            )
            CleanupPage.RecycleBin -> CleanupRecycleBinScreen(
                contentPadding = contentPadding,
                viewModel = viewModel,
                onBack = { page = CleanupPage.Main },
            )
        }
    }
}

/**
 * 清理主页：分析卡片 + 清理策略 + 审核入口卡（条件） + 回收站入口卡。
 */
@Composable
fun CleanupMainScreen(
    contentPadding: PaddingValues,
    viewModel: CleanupViewModel,
    onOpenReview: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    onOpenTags: () -> Unit,
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
    val recycleBinItems by viewModel.quarantine.collectAsState()
    val thumbnailTrashSummary by viewModel.thumbnailTrashSummary.collectAsState()

    val recycleBinCount = recycleBinItems.size + thumbnailTrashSummary.count
    val recycleBinBytes = recycleBinItems.sumOf { it.size } + thumbnailTrashSummary.bytes

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
                total = analysisOverview.totalImages,
                analyzed = fullyAnalyzed,
                hashedAny = analysisOverview.hashDone > 0,
                onStart = viewModel::startAnalysis,
                onCancel = viewModel::cancelAnalysis,
            )

            StrategyCard(
                config = cleanupConfig,
                busy = busy,
                onSelectPreset = viewModel::applyPreset,
                onGenerate = { viewModel.generateBatch(onReady = onOpenReview) },
                onRecluster = {
                    viewModel.reclusterDuplicates {
                        Toast.makeText(context, "重复分组已用新算法重算", Toast.LENGTH_SHORT).show()
                    }
                },
            )

            lastBatch?.let { batch ->
                ReviewSummaryCard(
                    batch = batch,
                    selectedCount = selectedReviewDeleteIds.size,
                    onOpen = onOpenReview,
                    onClose = viewModel::closeReview,
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

/** 分析卡片主按钮的语义状态（抽出便于单测） */
internal enum class AnalysisAction { START, CONTINUE, ALL_DONE, CANCEL, NO_IMAGES }

/**
 * 主按钮三态判定：
 * 运行中→取消；无图→禁用；已分析达到总数→已全部分析；hash 过→继续；否则→开始。
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
private fun AnalysisCard(
    state: AnalysisState,
    progress: com.xjw.qalbum.service.analysis.AnalysisProgress?,
    total: Int,
    analyzed: Int,
    hashedAny: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val fraction = if (total > 0) analyzed.toFloat() / total else 0f
    val action = analysisAction(state, total, analyzed, hashedAny)

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
                    AnalysisAction.CANCEL -> OutlinedButton(onClick = onCancel) { Text("取消") }
                    AnalysisAction.ALL_DONE -> Button(onClick = {}, enabled = false) { Text("已全部分析") }
                    AnalysisAction.NO_IMAGES -> Button(onClick = {}, enabled = false) { Text("开始分析") }
                    AnalysisAction.CONTINUE -> Button(onClick = onStart) { Text("继续分析") }
                    AnalysisAction.START -> Button(onClick = onStart) { Text("开始分析") }
                }
            }

            Spacer(Modifier.height(16.dp))

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
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall),
                )
            } else {
                Text(
                    "扫描图片后即可开始分析",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                val batchFraction = progress?.takeIf { it.total > 0 }?.let { it.processed.toFloat() / it.total }
                if (batchFraction != null) {
                    LinearProgressIndicator(
                        progress = { batchFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall),
                    )
                }
            }

            if (state == AnalysisState.Failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "上次分析失败：${progress?.msg ?: "未知错误"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

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
    onRecluster: () -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onGenerate, enabled = !busy) { Text("生成审核清理") }
                OutlinedButton(onClick = onRecluster, enabled = !busy) { Text("重新查重") }
            }
        }
    }
}

@Composable
private fun ReviewSummaryCard(
    batch: CleanupBatch,
    selectedCount: Int,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("审核清理", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "共 ${batch.report.totalCount} 项 · 已预选 $selectedCount",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            batch.report.byReason.entries.toList().forEachIndexed { index, (reason, group) ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(reason, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${group.count} 项 · ${formatBytes(group.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text("开始审核") }
                OutlinedButton(onClick = onClose) { Text("关闭审核") }
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

// ---- 公共小组件 / 工具（供审核页、回收站页复用，标 internal）----

@Composable
internal fun ThumbnailEntryCard(
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

/** 一键清理 / 移入回收站 进度对话框：清理期间显示，完成后由调用方置空自动消失，不可手动关闭 */
@Composable
internal fun PurgeProgressDialog(progress: PurgeProgress) {
    val fraction = if (progress.total > 0) {
        (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
    } else {
        0f
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { },
        confirmButton = { },
        title = { Text(progress.label) },
        text = {
            Column {
                Text(
                    "已处理 ${progress.done} / ${progress.total} 项",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall),
                )
            }
        },
    )
}

private fun presetHint(preset: CleanupPreset?): String = when (preset) {
    CleanupPreset.CONSERVATIVE -> "保守：只清理重复 / 损坏 / 空白，最安全。"
    CleanupPreset.BALANCED -> "平衡：加入低分辨率 / 模糊 / 表情包，覆盖大多数明显多余的图。"
    CleanupPreset.AGGRESSIVE -> "激进：全部维度开启且门限放宽，可能含有价值的图，请仔细审核。"
    null -> "自定义规则：可在「设置 → 清理规则」里逐项调整开关与门限。"
}

internal fun formatCount(n: Int): String =
    if (n >= 10_000) "%.1f万".format(n / 10_000f) else n.toString()

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.1f %s", value, units[exp])
}

internal fun imageMeta(image: com.xjw.qalbum.data.db.entity.ImageEntity): String {
    val size = formatBytes(image.size)
    val dimensions = if (image.width > 0 && image.height > 0) "${image.width}×${image.height}" else "未知尺寸"
    val clarity = image.blurScore?.let { " · 清晰度 ${String.format("%.0f", it)}" } ?: ""
    return "$dimensions · $size$clarity"
}

private val quarantineDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

internal fun formatQuarantineDate(ts: Long): String = quarantineDateFormatter.format(Date(ts))

/** 图片拍摄/修改时间（mtime 兼容秒与毫秒）：预览标题用 */
internal fun formatImageTime(mtime: Long): String {
    val ms = if (mtime < 1_000_000_000_000L) mtime * 1000 else mtime
    return quarantineDateFormatter.format(Date(ms))
}

internal fun autoDeleteText(expiresAt: Long): String {
    val remaining = expiresAt - System.currentTimeMillis()
    if (remaining <= 0) return "即将清理"
    val days = remaining / 86_400_000L
    return if (days >= 1) "$days 天后" else "不到 1 天"
}
