package com.xjw.qalbum.ui.cleanup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.service.analysis.AnalysisState
import com.xjw.qalbum.service.cleanup.CleanupBatch
import com.xjw.qalbum.service.cleanup.CleanupStrategy
import org.koin.androidx.compose.koinViewModel

/**
 * 清理首页
 *
 * - 顶部"分析"卡片：触发 Hash/Quality/Duplicate/Score pipeline
 * - 中部"生成清理批次"：选择策略 → 生成 → 显示风险报告
 * - 底部"隔离区"入口
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
    val quarantine by viewModel.quarantine.collectAsState()

    var showQuarantine by remember { mutableStateOf(false) }

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
        if (showQuarantine) {
            QuarantineSection(
                items = quarantine,
                onRestore = viewModel::restore,
                onPurge = viewModel::purge,
                onBack = { showQuarantine = false },
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
                    BatchReportCard(
                        batch = batch,
                        busy = busy,
                        onApply = {
                            viewModel.applyBatch(batch.id) { /* TODO toast moved=$it */ }
                        },
                    )
                }

                QuarantineSummaryCard(
                    count = quarantine.size,
                    onOpen = { showQuarantine = true },
                )
            }
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
            FilledTonalButton(onClick = onGenerate, enabled = !busy) { Text("生成清理批次") }
        }
    }
}

@Composable
private fun BatchReportCard(batch: CleanupBatch, busy: Boolean, onApply: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("批次 #${batch.id}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "共 ${batch.report.totalCount} 项 · ${formatBytes(batch.report.totalSize)}" +
                    " · 安全 ${batch.report.safeCount} 项 · 谨慎 ${batch.report.riskyCount} 项",
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, enabled = !busy) { Text("送入隔离区") }
                AssistChip(onClick = {}, label = { Text("已默认按建议执行") })
            }
        }
    }
}

@Composable
private fun QuarantineSummaryCard(count: Int, onOpen: () -> Unit) {
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
                Text("隔离区", style = MaterialTheme.typography.titleSmall)
                Text(
                    "当前 $count 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpen) { Text("查看") }
        }
    }
}

@Composable
private fun QuarantineSection(
    items: List<com.xjw.qalbum.data.db.entity.QuarantineEntity>,
    onRestore: (Long) -> Unit,
    onPurge: (Long) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
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
                "隔离区（${items.size} 项）",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("隔离区为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
}

// ---- 工具 ----

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
