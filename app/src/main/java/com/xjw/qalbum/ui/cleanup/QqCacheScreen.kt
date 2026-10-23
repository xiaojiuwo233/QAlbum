package com.xjw.qalbum.ui.cleanup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.qq.QqCacheTargets
import com.xjw.qalbum.service.cleanup.QqCacheCleaner
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.FloatingActionToolbar
import com.xjw.qalbum.ui.components.LoadingState
import com.xjw.qalbum.ui.components.SectionHeader
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedDefaults
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.ToolbarPrimaryAction
import com.xjw.qalbum.ui.components.animatedSegmentShape
import com.xjw.qalbum.ui.video.copyPath
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

/**
 * QQ 缓存：QQ「清理缓存」会清空的目录，各自的文件数与大小；点开看占用最大的几项、复制路径，勾选后清空
 * （删掉目录里的内容、保留目录本身，与 QQ 一样）。内部目录只在 Root 模式下列出。
 */
@Composable
fun QqCacheScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: QqCacheViewModel = koinViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    val chosen = ui.selectedEntries
    val chosenBytes = chosen.sumOf { it.bytes }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QQ 缓存") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !ui.loading && !busy, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重新统计")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                ui.loading && ui.entries.isEmpty() -> LoadingState(label = "正在统计…")
                ui.unavailable -> EmptyState(icon = Icons.Outlined.FolderOff, title = "需要文件访问", message = "在「设置 → 文件访问」里授权")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 112.dp),
                    verticalArrangement = Arrangement.spacedBy(SegmentedDefaults.Gap),
                ) {
                    item(key = "summary") {
                        SummaryCard(
                            total = ui.entries.filter { it.clearable }.sumOf { it.bytes },
                            chosenBytes = chosenBytes,
                            chosenFiles = chosen.sumOf { it.files },
                            loading = ui.loading,
                        )
                    }
                    item(key = "system-header") { SectionHeader("清理缓存时清空") }
                    itemsIndexed(ui.system, key = { _, entry -> entry.path }) { index, entry ->
                        CacheEntryRow(
                            entry = entry,
                            position = SegmentPosition(index, ui.system.size),
                            checked = entry.path in ui.selected,
                            expanded = entry.path in ui.expanded,
                            children = ui.children[entry.path],
                            onToggle = { viewModel.toggle(entry.path) },
                            onExpand = { viewModel.toggleExpanded(entry.path) },
                            onCopy = { copyPath(context, entry.path) },
                        )
                    }
                    if (ui.legacy.isNotEmpty()) {
                        item(key = "legacy-header") {
                            val paths = ui.legacy.map { it.path }
                            val all = ui.selected.containsAll(paths)
                            SectionHeader("旧版清理的目录") {
                                TextButton(onClick = { viewModel.setGroup(paths, !all) }) { Text(if (all) "全不选" else "全选") }
                            }
                        }
                        itemsIndexed(ui.legacy, key = { _, entry -> entry.path }) { index, entry ->
                            CacheEntryRow(
                                entry = entry,
                                position = SegmentPosition(index, ui.legacy.size),
                                checked = entry.path in ui.selected,
                                expanded = entry.path in ui.expanded,
                                children = ui.children[entry.path],
                                onToggle = { viewModel.toggle(entry.path) },
                                onExpand = { viewModel.toggleExpanded(entry.path) },
                                onCopy = { copyPath(context, entry.path) },
                            )
                        }
                    }
                    item(key = "note") {
                        Text(
                            "按 QQ 9.3.60「清理缓存」的固定目录整理；新版 QQ 还会按服务器下发的名单删别的文件，这部分复刻不了。长按一行复制路径。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        )
                    }
                }
            }

            FloatingActionToolbar(visible = chosen.isNotEmpty() && !ui.unavailable, modifier = Modifier.align(Alignment.BottomCenter)) {
                ToolbarPrimaryAction(
                    Icons.Outlined.CleaningServices,
                    "清理 ${formatBytes(chosenBytes)}",
                    enabled = !busy && !ui.loading,
                    onClick = { confirm = true },
                )
            }
        }
    }

    if (confirm) {
        if (chosen.isEmpty()) {
            LaunchedEffect(Unit) { confirm = false }
        } else {
            ConfirmDialog(
                icon = Icons.Outlined.CleaningServices,
                title = "清理 ${chosen.size} 个目录？",
                text = "共 ${String.format(Locale.ROOT, "%,d", chosen.sumOf { it.files })} 个文件（${formatBytes(chosenBytes)}），删除后无法恢复。QQ 用到时会重新下载或生成。",
                confirmLabel = "清理",
                destructive = true,
                onDismiss = { confirm = false },
                onConfirm = {
                    confirm = false
                    viewModel.clear(chosen.map { it.path })
                },
            )
        }
    }
}

/** 顶部概览：能清的总量、已选多少 */
@Composable
private fun SummaryCard(total: Long, chosenBytes: Long, chosenFiles: Long, loading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(Icons.Outlined.CleaningServices, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("可清理", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(formatBytes(total), style = MaterialTheme.typography.headlineSmallEmphasized)
                }
                if (loading) LoadingIndicator(modifier = Modifier.size(32.dp))
            }
            Text(
                "已选 ${formatBytes(chosenBytes)} · ${String.format(Locale.ROOT, "%,d", chosenFiles)} 个文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一个目录：勾选、路径、所属与文件数、大小；点开看完整路径和占用最大的几项，长按复制路径 */
@Composable
private fun CacheEntryRow(
    entry: QqCacheCleaner.Entry,
    position: SegmentPosition,
    checked: Boolean,
    expanded: Boolean,
    children: List<QqCacheCleaner.Child>?,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    onCopy: () -> Unit,
) {
    val target = entry.target
    val area = if (target.area == QqCacheTargets.Area.INTERNAL) "内部" else "外部"
    // 标题是目录名，内部 / 外部与上级路径放在标题上面一行（整条路径放标题会挤成两行还被截断）
    val title = target.relative.substringAfterLast('/') + if (target.file) "" else "/"
    val parent = target.relative.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { "$it/" }
    val detail = when (entry.state) {
        QqCacheCleaner.State.PRESENT -> if (entry.files > 0) "${String.format(Locale.ROOT, "%,d", entry.files)} 个文件" else "是空的"
        QqCacheCleaner.State.MISSING -> "没有这个目录"
        QqCacheCleaner.State.DENIED -> "没有权限"
        QqCacheCleaner.State.ERROR -> "读取出错"
    }
    val location = listOfNotNull(area, parent).joinToString(" · ")
    val subtitle = listOfNotNull(target.owner.takeIf { !target.system }, detail).joinToString(" · ")
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "expand")
    Surface(shape = animatedSegmentShape(position, emphasized = expanded), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                // 没有权限的目录也能长按复制路径
                modifier = Modifier.combinedClickable(
                    onClick = { if (entry.state == QqCacheCleaner.State.PRESENT) onExpand() },
                    onLongClick = onCopy,
                ),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Checkbox(checked = checked && entry.clearable, onCheckedChange = { onToggle() }, enabled = entry.clearable)
                },
                overlineContent = { Text(location, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                headlineContent = {
                    Text(title, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = { Text(subtitle) },
                trailingContent = if (entry.state == QqCacheCleaner.State.PRESENT) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatBytes(entry.bytes), style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.Outlined.ExpandMore, contentDescription = if (expanded) "收起" else "展开", modifier = Modifier.rotate(rotation))
                        }
                    }
                } else {
                    null
                },
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onCopy, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制路径")
                        }
                    }
                    when {
                        children == null -> LoadingIndicator(modifier = Modifier.size(32.dp).padding(4.dp))
                        children.isEmpty() -> Text("里面没有东西", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> children.forEach { child -> ChildRow(child) }
                    }
                    if (children != null && children.isNotEmpty() && entry.bytes > 0) {
                        val rest = entry.bytes - children.sumOf { it.bytes }
                        if (rest > 0) {
                            Text(
                                "其余 ${formatBytes(rest)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun ChildRow(child: QqCacheCleaner.Child) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            child.name + if (child.directory) "/" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (child.directory) "${String.format(Locale.ROOT, "%,d", child.files)} 个 · ${formatBytes(child.bytes)}" else formatBytes(child.bytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        )
    }
}
