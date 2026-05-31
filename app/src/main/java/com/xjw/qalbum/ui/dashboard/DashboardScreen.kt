package com.xjw.qalbum.ui.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.DeleteSweep
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.R
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.domain.model.ScanProgress
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardScreen(
    contentPadding: PaddingValues,
    onNavigateToSettings: () -> Unit = {},
    viewModel: DashboardViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val running by viewModel.scanRunning.collectAsState()
    val progress by viewModel.scanProgress.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "设置",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
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
            MetricsGrid(state)
            StorageBreakdownCard(state.breakdown, state.totalSizeBytes)
            ScanCard(
                running = running,
                progress = progress,
                onIncremental = viewModel::startScan,
                onFull = viewModel::startFullScan,
                onCancel = viewModel::cancelScan,
            )
            AnalysisPipelineCard(state.analysisProgress)
            ImageHealthCard(state)
        }
    }
}

// ── Key Metrics 2×2 ─────────────────────────────────────────────────

@Composable
private fun MetricsGrid(state: DashboardUiState) {
    val cachedThumbCount = state.thumbnailTrashCount
    val cachedThumbBytes = state.thumbnailTrashBytes

    val cleanableCount = state.thumbnailTrashCount + state.quarantineCount
    val cleanableBytes = state.thumbnailTrashBytes + state.quarantineBytes

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
            )
            MetricCard(
                icon = Icons.Outlined.Storage,
                value = formatBytes(state.totalSizeBytes),
                label = "占用空间",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                icon = Icons.Outlined.Folder,
                value = formatCount(cachedThumbCount),
                label = "缓存图片",
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
) {
    ElevatedCard(
        modifier = modifier,
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
                text = value,
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
private fun AnalysisPipelineCard(progress: ImageDao.AnalysisProgressRow) {
    val totalImages = progress.totalImages.coerceAtLeast(1)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "分析流水线",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
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
            text = "$done/$total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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
                if (health.protectedCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFAB47BC),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${health.protectedCount} 受保护",
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

private fun statusText(running: Boolean, progress: ScanProgress?): String = when {
    progress is ScanProgress.Failed -> "失败：${progress.reason}"
    progress is ScanProgress.Done -> "完成 ${progress.total} 张"
    progress is ScanProgress.Indexing -> "索引 ${progress.processed}/${progress.total}"
    progress is ScanProgress.Enumerating -> "枚举中… ${progress.total} 个候选"
    running -> "启动中…"
    else -> ""
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
