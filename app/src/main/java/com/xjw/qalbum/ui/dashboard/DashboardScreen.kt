package com.xjw.qalbum.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.PhotoSizeSelectSmall
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.ui.anim.StaggeredEntrance
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCount
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedNavigationItem
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.WavyProgress
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardScreen(
    contentPadding: PaddingValues,
    onOpenAccessSettings: () -> Unit = {},
    onOpenGuide: () -> Unit = {},
    onOpenSources: () -> Unit = {},
    viewModel: DashboardViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val chatAvailable by viewModel.chatAvailable.collectAsState()
    val running by viewModel.scanRunning.collectAsState()
    val progress by viewModel.scanProgress.collectAsState()
    val automaticScan by viewModel.scanAutomatic.collectAsState()
    val cleanedStats by viewModel.cleanedStats.collectAsState()
    val todayCleanedStats by viewModel.todayCleanedStats.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("QAlbum") },
                actions = { CleanedStatsLabel(cleanedStats, todayCleanedStats) },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topBarPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var index = 0
            AnimatedVisibility(
                visible = state.loaded && state.imageCount == 0 && !running,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                FirstRunGuideCard(onOpenGuide = onOpenGuide, onOpenAccess = onOpenAccessSettings)
            }
            StaggeredEntrance(index++) { MetricsGrid(state) }
            if (chatAvailable) {
                StaggeredEntrance(index++) {
                    SegmentedNavigationItem(
                        position = SegmentPosition.Single,
                        icon = Icons.Outlined.Forum,
                        title = "按聊天来源浏览",
                        subtitle = "按群聊、私聊和发送人查看图片",
                        onClick = onOpenSources,
                        iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            StaggeredEntrance(index++) {
                ScanCard(
                    running = running,
                    progress = progress,
                    automatic = automaticScan,
                    onIncremental = viewModel::startScan,
                    onFull = viewModel::startFullScan,
                    onCancel = viewModel::cancelScan,
                )
            }
            StaggeredEntrance(index) { StorageBreakdownCard(state.breakdown, state.totalSizeBytes) }
        }
    }
}

// ── 新手引导（库为空时） ─────────────────────────────────────────────

/** 库里还没有图时显示（扫描中隐藏）：重新走一遍引导，或直接去文件访问 */
@Composable
private fun FirstRunGuideCard(onOpenGuide: () -> Unit, onOpenAccess: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(
                    Icons.Outlined.RocketLaunch,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    shape = MaterialShapes.Cookie7Sided.toShape(),
                    size = 44.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("还没有图片", style = MaterialTheme.typography.titleLargeEmphasized)
                    Text("授权文件访问，再扫描一次 QQ 图片目录", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenGuide, shapes = ButtonDefaults.shapes()) { Text("开始引导") }
                TextButton(onClick = onOpenAccess, shapes = ButtonDefaults.shapes()) { Text("文件访问") }
            }
        }
    }
}

// ── 顶栏「已清理」 ─────────────────────────────────────────────────

@Composable
private fun CleanedStatsLabel(
    stats: CleanedStats,
    todayStats: CleanedStats,
) {
    // 从未清理过时不显示；清理过后单击在字节 / 张数间切换，长按切到「今日」维度
    if (stats.isEmpty) return
    var todayMode by remember { mutableStateOf(false) }
    var showCount by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val container = if (todayMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (todayMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(CircleShape)
            .background(container)
            .combinedClickable(
                onClick = { showCount = !showCount },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    todayMode = !todayMode
                    showCount = false
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CleaningServices,
            contentDescription = if (todayMode) "今日已清理" else "已清理",
            modifier = Modifier.size(16.dp),
            tint = content,
        )
        Spacer(Modifier.width(6.dp))
        AnimatedContent(
            targetState = todayMode to showCount,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
            },
            label = "cleaned-stats",
        ) { (today, isCount) ->
            val row = if (today) todayStats else stats
            val prefix = if (today) "今日 " else "已清理 "
            Text(
                text = if (isCount) cleanedCountText(row, today) else "$prefix${formatBytes(row.bytes)}",
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
        }
    }
}

/** 张数：删除的与压缩的分开说（压缩的图还在） */
internal fun cleanedCountText(row: CleanedStats, today: Boolean): String {
    val deleted = "${formatCount(row.deleted.count)} 张"
    val compressed = "压缩 ${formatCount(row.compressed.count)} 张"
    return when {
        row.compressed.count <= 0 -> if (today) "今日 $deleted" else "已清理 $deleted"
        row.deleted.count <= 0 -> if (today) "今日$compressed" else "已$compressed"
        else -> (if (today) "今日 " else "已清理 ") + "$deleted · $compressed"
    }
}

// ── 关键指标 2×2 ────────────────────────────────────────────────────

@Composable
private fun MetricsGrid(state: DashboardUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                icon = Icons.Outlined.Image,
                value = formatCount(state.imageCount),
                label = "图片",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
                expandedValue = formatCountExact(state.imageCount),
            )
            MetricCard(
                icon = Icons.Outlined.Storage,
                value = formatBytes(state.totalSizeBytes),
                label = "占用空间",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
                expandedValue = formatExactMb(state.totalSizeBytes),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                icon = Icons.Outlined.PhotoSizeSelectSmall,
                value = formatCount(state.thumbnailTrashCount),
                label = "QQ 缩略图",
                subtitle = if (state.thumbnailTrashBytes > 0) formatBytes(state.thumbnailTrashBytes) else null,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
                expandedValue = formatCountExact(state.thumbnailTrashCount),
            )
            MetricCard(
                icon = Icons.Outlined.Inventory2,
                value = formatCount(state.quarantineCount),
                label = "回收站",
                subtitle = if (state.quarantineBytes > 0) formatBytes(state.quarantineBytes) else null,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                expandedValue = formatCountExact(state.quarantineCount),
            )
        }
    }
}

@Composable
private fun MetricCard(
    icon: ImageVector,
    value: String,
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** 精确值：非空时卡片可点击，在缩写与精确值间切换 */
    expandedValue: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        onClick = { if (expandedValue != null) expanded = !expanded },
        enabled = expandedValue != null,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = if (expanded && expandedValue != null) expandedValue else value,
                transitionSpec = { (fadeIn() + slideInVertically { it / 3 }) togetherWith fadeOut() },
                label = "metricValue",
            ) { text ->
                Text(text, style = MaterialTheme.typography.headlineMediumEmphasized, maxLines = 1)
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, color = contentColor.copy(alpha = 0.78f))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = contentColor.copy(alpha = 0.66f))
            }
        }
    }
}

// ── 存储分布 ─────────────────────────────────────────────────────────

@Composable
private fun DashboardCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun StorageBreakdownCard(
    rows: List<ImageDao.SourceBreakdownRow>,
    totalBytes: Long,
) {
    val chartColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.outline,
    )
    DashboardCard(title = "存储分布", icon = Icons.Outlined.DataUsage) {
        if (rows.isEmpty() || totalBytes <= 0L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "扫描后显示各目录的占用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                    DonutChart(
                        segments = rows.mapIndexed { i, row -> chartColors[i % chartColors.size] to row.bytes.toFloat() },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 20.dp,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatBytes(totalBytes), style = MaterialTheme.typography.titleSmallEmphasized)
                        Text("总计", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEachIndexed { i, row ->
                        LegendItem(
                            color = chartColors[i % chartColors.size],
                            label = mapSourceLabel(row.source),
                            value = formatBytes(row.bytes),
                            percentage = if (totalBytes > 0) row.bytes * 100f / totalBytes else 0f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutChart(
    segments: List<Pair<Color, Float>>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 22.dp,
) {
    val total = segments.sumOf { it.second.toDouble() }.toFloat()
    var targetSweep by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { targetSweep = 1f }
    val sweepFraction by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "donut",
    )
    Canvas(modifier = modifier.aspectRatio(1f)) {
        if (total <= 0f) return@Canvas
        val stroke = strokeWidth.toPx()
        val gap = 4f
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arcSize = Size(diameter, diameter)
        var startAngle = -90f
        segments.forEach { (color, value) ->
            val sweep = ((value / total) * 360f - gap).coerceAtLeast(0f) * sweepFraction
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            startAngle += (value / total) * 360f
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, value: String, percentage: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$value · ${String.format("%.0f", percentage)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 扫描 ─────────────────────────────────────────────────────────────

@Composable
private fun ScanCard(
    running: Boolean,
    progress: ScanProgress?,
    automatic: Boolean,
    onIncremental: () -> Unit,
    onFull: () -> Unit,
    onCancel: () -> Unit,
) {
    DashboardCard(title = "扫描图片", icon = Icons.Outlined.TravelExplore) {
        Text(
            when {
                running || progress != null -> statusText(running, progress, automatic)
                else -> "增量扫描只看有变化的目录，几秒完成；全量扫描核对全部文件，能发现已删除的。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(
            visible = progress.toProgressFraction() != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val f = progress.toProgressFraction()
            WavyProgress(
                fraction = f?.takeIf { it >= 0f },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(targetState = running, label = "scanActions") { isRunning ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRunning) {
                    OutlinedButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text("取消扫描") }
                } else {
                    FilledTonalButton(onClick = onIncremental, shapes = ButtonDefaults.shapes()) { Text("增量扫描") }
                    Button(onClick = onFull, shapes = ButtonDefaults.shapes()) { Text("全量扫描") }
                }
            }
        }
    }
}

// ── 工具 ─────────────────────────────────────────────────────────────

private fun mapSourceLabel(source: String): String = when (source) {
    "chatimg" -> "预览图 chatimg"
    "chatraw" -> "原图 chatraw"
    "chatthumb" -> "缩略图 chatthumb"
    "temp" -> "转码图 Temp"
    else -> source.ifBlank { "其他" }
}

/**
 * 进度环下方的紧凑计数：100 万以上取整万，1 万以上一位小数万，其余原样，避免在环宽内换行。
 */
private fun compactCount(n: Int): String = when {
    n >= 1_000_000 -> "${n / 10_000}万"
    n >= 10_000 -> "%.1f万".format(n / 10_000f)
    else -> n.toString()
}

/** 扫描进度的文字（概览与设置的扫描页共用）；[automatic] 为后台定时的自动扫描时标出来，免得以为是自己点的 */
internal fun statusText(running: Boolean, progress: ScanProgress?, automatic: Boolean = false): String {
    val text = when (progress) {
        is ScanProgress.Cancelled -> "已取消"
        is ScanProgress.Failed -> "失败：${progress.reason}"
        is ScanProgress.Done -> "完成，新增或更新 ${progress.total} 张"
        is ScanProgress.Indexing -> "正在扫描目录 ${progress.processed}/${progress.total}"
        is ScanProgress.Enumerating ->
            if (progress.total > 0) "正在比对目录… 已发现 ${progress.total}" else "正在比对目录…"
        null -> if (running) "启动中…" else ""
    }
    return if (automatic && text.isNotEmpty()) "自动扫描 · $text" else text
}

/** 进度比例：null 不显示，-1 表示不确定进度 */
internal fun ScanProgress?.toProgressFraction(): Float? = when (this) {
    is ScanProgress.Indexing -> if (total > 0) processed.toFloat() / total else -1f
    is ScanProgress.Enumerating -> -1f
    else -> null
}

/** 「占用空间」点击展开：精确到 MB（带千分位），如 1,177 MB */
private fun formatExactMb(bytes: Long): String =
    if (bytes <= 0) "0 MB" else "%,d MB".format(Math.round(bytes / (1024.0 * 1024.0)))
