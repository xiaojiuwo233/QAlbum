package com.xjw.qalbum.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.R
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.analysis.AnalysisProgress
import com.xjw.qalbum.service.analysis.AnalysisState
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardScreen(
    contentPadding: PaddingValues,
    onOpenAccessSettings: () -> Unit = {},
    viewModel: DashboardViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val running by viewModel.scanRunning.collectAsState()
    val progress by viewModel.scanProgress.collectAsState()
    val analysisState by viewModel.analysisState.collectAsState()
    val analysisRunning by viewModel.analysisRunningProgress.collectAsState()
    val cleanedStats by viewModel.cleanedStats.collectAsState()
    val todayCleanedStats by viewModel.todayCleanedStats.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                actions = { CleanedStatsLabel(cleanedStats, todayCleanedStats) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topBarPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarPadding.calculateTopPadding())
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loaded && state.imageCount == 0 && !running) {
                FirstRunGuideCard(onOpenAccessSettings = onOpenAccessSettings)
            }
            MetricsGrid(state)
            StorageBreakdownCard(state.breakdown, state.totalSizeBytes)
            ScanCard(
                running = running,
                progress = progress,
                onIncremental = viewModel::startScan,
                onFull = viewModel::startFullScan,
                onCancel = viewModel::cancelScan,
            )
            AnalysisPipelineCard(
                progress = state.analysisProgress,
                analysisState = analysisState,
                running = analysisRunning,
            )
            ImageHealthCard(state)
        }
    }
}

// ── First Run Guide（库为空时的首启引导卡）────────────────────────────

/**
 * 首启引导：库为空时页面全是 0，而「先授权 Shizuku / 确认路径」的提示原本只在
 * 用户主动点扫描失败后才以小字出现——不点扫描永远看不到。这里主动给出配置路径，
 * 点击直达「权限与访问」设置页。扫描进行中隐藏（索引正在建立，不再需要引导）。
 */
@Composable
private fun FirstRunGuideCard(onOpenAccessSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "开始使用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "还没有建立图片索引，首次使用请先完成：\n" +
                    "1. 在「权限与访问」安装并授权 Shizuku（或选择 Root）\n" +
                    "2. 在「设置 → 扫描」确认图片路径（默认为 QQ 聊天图片目录）\n" +
                    "3. 回到本页点击「全量扫描」建立索引",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            FilledTonalButton(onClick = onOpenAccessSettings) { Text("去授权与设置") }
        }
    }
}

// ── Cleaned Stats（顶栏右侧「已清理」小文本）──────────────────────────

@Composable
private fun CleanedStatsLabel(
    stats: ImageDao.CleanedStatsRow,
    todayStats: ImageDao.CleanedStatsRow,
) {
    // 累计为 0（从未清理过）时整个图标不显示，避免顶栏出现无意义的「0 B」；
    // 一旦清理过，长按即可切到「今日」维度，今日为 0 也照常显示「今日 0 张 / 0 B」。
    if (stats.count <= 0) return
    var todayMode by remember { mutableStateOf(false) }
    var showCount by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = { showCount = !showCount },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    todayMode = !todayMode
                    showCount = false   // 切换维度后从字节开始（与「显示今日 xxMB」一致）
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CleaningServices,
            contentDescription = if (todayMode) "今日已清理" else "已清理",
            modifier = Modifier.size(16.dp),
            tint = if (todayMode) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        AnimatedContent(targetState = todayMode to showCount, label = "cleaned-stats") { (today, isCount) ->
            val row = if (today) todayStats else stats
            val prefix = if (today) "今日 " else ""
            Text(
                text = if (isCount) "$prefix${formatCount(row.count)} 张" else "$prefix${formatBytes(row.bytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (today) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Key Metrics 2×2 ─────────────────────────────────────────────────

@Composable
private fun MetricsGrid(state: DashboardUiState) {
    val cachedThumbCount = state.thumbnailTrashCount
    val cachedThumbBytes = state.thumbnailTrashBytes

    val cleanableCount = state.quarantineCount
    val cleanableBytes = state.quarantineBytes

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                icon = Icons.Outlined.Image,
                value = formatCount(state.imageCount),
                label = "总图片",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
                expandedValue = state.imageCount.toString(),
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                icon = Icons.Outlined.Folder,
                value = formatCount(cachedThumbCount),
                label = "QQ 预览缓存",
                subtitle = if (cachedThumbBytes > 0) formatBytes(cachedThumbBytes) else null,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                icon = Icons.Outlined.DeleteSweep,
                value = formatCount(cleanableCount),
                label = "可清理",
                subtitle = if (cleanableBytes > 0) formatBytes(cleanableBytes) else null,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
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
    /** 精确值（如 198210 / 1,234,567,890 B）：非空时卡片可点击，在缩写与精确值间切换。 */
    expandedValue: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val clickModifier =
        if (expandedValue != null) Modifier.clickable { expanded = !expanded } else Modifier
    ElevatedCard(
        modifier = modifier.then(clickModifier),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (expanded && expandedValue != null) expandedValue else value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// ── Storage Breakdown Donut ─────────────────────────────────────────

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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "存储分布",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))

            if (rows.isEmpty() || totalBytes <= 0L) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "扫描后可查看分布",
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
                    Box(
                        modifier = Modifier.size(130.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val segments = rows.mapIndexed { i, row ->
                            chartColors[i % chartColors.size] to row.bytes.toFloat()
                        }
                        DonutChart(
                            segments = segments,
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 22.dp,
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                formatBytes(totalBytes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "总计",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rows.forEachIndexed { i, row ->
                            LegendItem(
                                color = chartColors[i % chartColors.size],
                                label = mapSourceLabel(row.source),
                                value = formatBytes(row.bytes),
                                percentage = if (totalBytes > 0) (row.bytes * 100f / totalBytes) else 0f,
                            )
                        }
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
        val gap = 3f
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
private fun LegendItem(
    color: Color,
    label: String,
    value: String,
    percentage: Float,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$value (${String.format("%.0f", percentage)}%)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Analysis Pipeline Rings ─────────────────────────────────────────

@Composable
private fun AnalysisPipelineCard(
    progress: ImageDao.AnalysisProgressRow,
    analysisState: AnalysisState,
    running: AnalysisProgress?,
) {
    val totalImages = progress.totalImages.coerceAtLeast(1)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "分析流水线",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // 分析运行时：实时显示当前阶段与本批进度（与清理页共享同一 pipeline 单例）
            if (analysisState == AnalysisState.Running) {
                Spacer(Modifier.height(12.dp))
                val phaseLabel = running?.phase?.displayName ?: "准备中"
                val detail = when {
                    running == null -> "准备中…"
                    running.total > 0 -> "正在$phaseLabel · ${running.processed}/${running.total}"
                    else -> "正在$phaseLabel…${running.msg?.let { " $it" } ?: ""}"
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                val batchFraction = running
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

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                PipelineRing(
                    label = "Hash",
                    done = progress.hashDone,
                    total = totalImages,
                    color = MaterialTheme.colorScheme.primary,
                    delayIndex = 0,
                )
                PipelineRing(
                    label = "OCR",
                    done = progress.ocrDone,
                    total = totalImages,
                    color = Color(0xFF66BB6A),
                    delayIndex = 1,
                )
                PipelineRing(
                    label = "AI",
                    done = progress.aiDone,
                    total = totalImages,
                    color = Color(0xFFAB47BC),
                    delayIndex = 2,
                )
                PipelineRing(
                    label = "向量",
                    done = progress.embeddingDone,
                    total = totalImages,
                    color = MaterialTheme.colorScheme.tertiary,
                    delayIndex = 3,
                )
            }
        }
    }
}

@Composable
private fun PipelineRing(
    label: String,
    done: Int,
    total: Int,
    color: Color,
    delayIndex: Int,
) {
    val fraction = if (total > 0) done.toFloat() / total else 0f
    val percentage = (fraction * 100).toInt()

    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(
            durationMillis = 800,
            delayMillis = delayIndex * 100,
            easing = FastOutSlowInEasing,
        ),
        label = "ring-$label",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp),
    ) {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProgressRing(
                progress = animatedFraction,
                color = color,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = "${percentage}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${compactCount(done)}/${compactCount(total)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}

@Composable
private fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 5.dp,
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arcSize = Size(diameter, diameter)

        drawArc(
            color = color.copy(alpha = 0.12f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (progress > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

// ── Image Health Bars ───────────────────────────────────────────────

@Composable
private fun ImageHealthCard(state: DashboardUiState) {
    val total = state.imageCount.coerceAtLeast(1)
    val health = state.health
    val normalCount = (state.imageCount - health.corrupt - health.blank - health.animated)
        .coerceAtLeast(0)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "文件概况",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (health.favoriteCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFAB47BC),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${health.favoriteCount} 已收藏",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFAB47BC),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HealthBarRow("正常", normalCount, normalCount.toFloat() / total, Color(0xFF66BB6A))
            Spacer(Modifier.height(8.dp))
            if (health.animated > 0) {
                HealthBarRow("GIF", health.animated, health.animated.toFloat() / total, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            if (health.blank > 0) {
                HealthBarRow("空白", health.blank, health.blank.toFloat() / total, Color(0xFFFFA726))
                Spacer(Modifier.height(8.dp))
            }
            if (health.corrupt > 0) {
                HealthBarRow("损坏", health.corrupt, health.corrupt.toFloat() / total, MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HealthBarRow(
    label: String,
    count: Int,
    fraction: Float,
    color: Color,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "bar-$label",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(40.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(color.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedFraction)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(color),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatCount(count),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End,
        )
    }
}

// ── Scan Controls ───────────────────────────────────────────────────

@Composable
private fun ScanCard(
    running: Boolean,
    progress: ScanProgress?,
    onIncremental: () -> Unit,
    onFull: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "扫描图片",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    OutlinedButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(34.dp),
                    ) { Text("取消", style = MaterialTheme.typography.labelMedium) }
                } else {
                    FilledTonalButton(
                        onClick = onIncremental,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(34.dp),
                    ) { Text("增量", style = MaterialTheme.typography.labelMedium) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onFull,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(34.dp),
                    ) { Text("全量", style = MaterialTheme.typography.labelMedium) }
                }
            }

            if (running || progress != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    statusText(running, progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress.toProgressFraction()?.let { f ->
                    Spacer(Modifier.height(4.dp))
                    if (f >= 0f) {
                        LinearProgressIndicator(
                            progress = { f },
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
            }
        }
    }
}

// ── Utilities ───────────────────────────────────────────────────────

private fun mapSourceLabel(source: String): String = when (source) {
    "chatimg" -> "img"
    "chatraw" -> "raw"
    "chatthumb" -> "thumb"
    else -> source.ifBlank { "其他" }
}

private fun formatCount(n: Int): String = when {
    n >= 100_000 -> "%.1f万".format(n / 10_000f)
    else -> n.toString()
}

/**
 * 流水线进度文本（done/total 并排）专用的紧凑计数：尽量窄，避免在 72.dp 环宽内换行。
 * 100万+ 用整数万（如 200万）、1万~100万 用一位小数万（如 20.7万）、1万以下原样。
 */
private fun compactCount(n: Int): String = when {
    n >= 1_000_000 -> "${n / 10_000}万"
    n >= 10_000 -> "%.1f万".format(n / 10_000f)
    else -> n.toString()
}

private fun statusText(running: Boolean, progress: ScanProgress?): String = when (progress) {
    is ScanProgress.Cancelled -> "已取消"
    is ScanProgress.Failed -> "失败：${progress.reason}"
    is ScanProgress.Done -> "完成，新增/更新 ${progress.total} 张"
    is ScanProgress.Indexing -> "正在扫描目录 ${progress.processed}/${progress.total}"
    is ScanProgress.Enumerating ->
        if (progress.total > 0) "正在对比目录… 已发现 ${progress.total}" else "正在对比目录…"
    null -> if (running) "启动中…" else ""
}

private fun ScanProgress?.toProgressFraction(): Float? = when (this) {
    is ScanProgress.Indexing -> if (total > 0) processed.toFloat() / total else -1f
    is ScanProgress.Enumerating -> -1f
    null -> null
    else -> null
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.1f %s", value, units[exp])
}

/** 「占用空间」点击展开值：精确到 MB（带千分位），如 1,177 MB。 */
private fun formatExactMb(bytes: Long): String =
    if (bytes <= 0) "0 MB" else "%,d MB".format(Math.round(bytes / (1024.0 * 1024.0)))
