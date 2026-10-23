package com.xjw.qalbum.ui.cleanup

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.PhotoSizeSelectSmall
import androidx.compose.material.icons.outlined.SpeakerNotesOff
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.FolderDelete
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.service.cleanup.rules.CleanupRunner
import com.xjw.qalbum.service.cleanup.rules.RuleAction
import com.xjw.qalbum.ui.anim.StaggeredEntrance
import com.xjw.qalbum.ui.anim.sharedAxisXEnter
import com.xjw.qalbum.ui.anim.sharedAxisXExit
import com.xjw.qalbum.ui.anim.sharedAxisXPopEnter
import com.xjw.qalbum.ui.anim.sharedAxisXPopExit
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.common.formatDateTime
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedNavigationItem
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.WavyProgress
import com.xjw.qalbum.ui.video.VideoScreen
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/** 清理页里的子页：主页、某条规则的预览、按时间清理、回收站、视频、QQ 缓存（[parent] 是返回时回到哪一页） */
private data class CleanupPage(val kind: String, val ruleId: String? = null, val parent: String = MAIN) {
    val depth: Int get() = when {
        kind == MAIN -> 0
        kind == RULE && parent == TIME -> 2
        else -> 1
    }

    companion object {
        const val MAIN = "main"
        const val RULE = "rule"
        const val TIME = "time"
        const val BIN = "bin"
        const val VIDEO = "video"
        const val QQ_CACHE = "qqcache"

        val Saver = Saver<CleanupPage, String>(
            save = { listOf(it.kind, it.parent, it.ruleId.orEmpty()).joinToString("\u0001") },
            restore = { raw ->
                val parts = raw.split('\u0001')
                CleanupPage(parts[0], parts.getOrNull(2)?.ifBlank { null }, parts.getOrElse(1) { MAIN })
            },
        )
    }
}

/**
 * 清理页根：在「主页 / 规则预览 / 按时间清理 / 回收站 / 视频 / QQ 缓存」之间内部切换，共享同一个 [CleanupViewModel]。
 * 进入子页时通过 [onSubPageChange] 通知外层隐藏底部导航栏。
 */
@Composable
fun CleanupScreen(
    contentPadding: PaddingValues,
    onOpenSources: () -> Unit,
    onSubPageChange: (Boolean) -> Unit,
    viewModel: CleanupViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    var page by rememberSaveable(stateSaver = CleanupPage.Saver) { mutableStateOf(CleanupPage(CleanupPage.MAIN)) }
    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // 主页与按时间页的滚动位置放在切页之外：从规则预览返回时停在原处
    val mainScroll = rememberScrollState()
    val mainBar = rememberTopAppBarState()
    val timeList = rememberLazyListState()
    val timeBar = rememberTopAppBarState()

    // 回收站操作的结果经共享状态回到任意存活页面（规则执行与压缩的结果在界面根部提示，见 CompressionHost）
    LaunchedEffect(Unit) {
        viewModel.message.collect { msg ->
            if (msg != null) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                viewModel.consumeMessage()
            }
        }
    }
    LaunchedEffect(page.kind) { onSubPageChange(page.kind != CleanupPage.MAIN) }
    DisposableEffect(Unit) { onDispose { onSubPageChange(false) } }
    val back = {
        page = when {
            page.kind == CleanupPage.RULE && page.parent == CleanupPage.TIME -> CleanupPage(CleanupPage.TIME)
            else -> CleanupPage(CleanupPage.MAIN)
        }
    }
    BackHandler(enabled = page.kind != CleanupPage.MAIN) { back() }

    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState.depth < initialState.depth) {
                sharedAxisXPopEnter(slideSpec, fadeSpec) togetherWith sharedAxisXPopExit(slideSpec, fadeSpec)
            } else {
                sharedAxisXEnter(slideSpec, fadeSpec) togetherWith sharedAxisXExit(slideSpec, fadeSpec)
            }
        },
        contentKey = { it.kind + it.ruleId },
        label = "cleanupPage",
    ) { p ->
        when (p.kind) {
            CleanupPage.RULE -> RulePreviewScreen(ruleId = p.ruleId ?: CleanupRule.Thumbnails.id, onBack = back)
            CleanupPage.TIME -> TimeCleanupScreen(
                viewModel = viewModel,
                listState = timeList,
                barState = timeBar,
                onBack = back,
                onOpenRange = { range -> page = CleanupPage(CleanupPage.RULE, range.id, parent = CleanupPage.TIME) },
            )
            CleanupPage.BIN -> CleanupRecycleBinScreen(
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                onBack = back,
            )
            CleanupPage.VIDEO -> VideoScreen(
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                onBack = back,
            )
            CleanupPage.QQ_CACHE -> QqCacheScreen(
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                onBack = back,
            )
            else -> CleanupMainScreen(
                contentPadding = contentPadding,
                viewModel = viewModel,
                scrollState = mainScroll,
                barState = mainBar,
                onOpenRule = { rule -> page = CleanupPage(CleanupPage.RULE, rule.id) },
                onOpenTime = { page = CleanupPage(CleanupPage.TIME) },
                onOpenRecycleBin = { page = CleanupPage(CleanupPage.BIN) },
                onOpenVideos = { page = CleanupPage(CleanupPage.VIDEO) },
                onOpenQqCache = { page = CleanupPage(CleanupPage.QQ_CACHE) },
                onOpenSources = onOpenSources,
            )
        }
    }

    // 清空回收站等操作的进度模态提升到根部：无论处于哪个子页都保持输入屏蔽
    val purgeProgress by viewModel.purgeProgress.collectAsState()
    purgeProgress?.let { PurgeProgressDialog(it) }
}

/** 清理主页：存储概览 + 清理建议（规则）+ 按时间 / 按聊天 / 回收站 */
@Composable
private fun CleanupMainScreen(
    contentPadding: PaddingValues,
    viewModel: CleanupViewModel,
    scrollState: ScrollState,
    barState: TopAppBarState,
    onOpenRule: (CleanupRule) -> Unit,
    onOpenTime: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    onOpenVideos: () -> Unit,
    onOpenQqCache: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val storage by viewModel.storage.collectAsState()
    val archived by viewModel.archivedCount.collectAsState()
    val rules by viewModel.rules.collectAsState()
    val chatRules by viewModel.chatRules.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val running by viewModel.running.collectAsState()
    val quarantineStats by viewModel.quarantineStats.collectAsState()
    val backupStats by viewModel.backupStats.collectAsState()
    val videoStats by viewModel.videoStats.collectAsState()
    val chatAvailable by viewModel.chatAvailable.collectAsState()
    val quality by viewModel.compressQuality.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("清理") },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding())
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StaggeredEntrance(0) { StorageCard(storage, archived) }
            AnimatedVisibility(
                visible = running != null,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(),
            ) {
                running?.let { RunningCard(it, onCancel = viewModel::cancelRunning) }
            }
            StaggeredEntrance(1) {
                SegmentedGroup(header = "清理建议") {
                    rules.forEach { card ->
                        item(card.rule.id) { position ->
                            SegmentedNavigationItem(
                                position = position,
                                icon = ruleIcon(card.rule),
                                title = card.rule.title,
                                subtitle = ruleSubtitle(card, quality),
                                onClick = { onOpenRule(card.rule) },
                            )
                        }
                    }
                }
            }
            if (chatAvailable) {
                StaggeredEntrance(2) {
                    SegmentedGroup(header = "按聊天") {
                        item("sources") { position ->
                            SegmentedNavigationItem(
                                position = position,
                                icon = Icons.Outlined.Forum,
                                title = "按聊天清理",
                                subtitle = "按群聊、私聊和发送人清理",
                                onClick = onOpenSources,
                            )
                        }
                        chatRules.forEach { card ->
                            item(card.rule.id) { position ->
                                // 还没读取来源时先去来源页
                                val blocked = chatRuleBlocker(card.rule, sources)
                                SegmentedNavigationItem(
                                    position = position,
                                    icon = ruleIcon(card.rule),
                                    title = card.rule.title,
                                    subtitle = blocked ?: ruleSubtitle(card, quality),
                                    onClick = { if (blocked != null) onOpenSources() else onOpenRule(card.rule) },
                                )
                            }
                        }
                    }
                }
            }
            StaggeredEntrance(3) {
                SegmentedGroup(header = "更多") {
                    item("time") { position ->
                        SegmentedNavigationItem(
                            position = position,
                            icon = Icons.Outlined.CalendarMonth,
                            title = "按时间清理",
                            subtitle = "按月查看占用，整段清理",
                            onClick = onOpenTime,
                        )
                    }
                    item("videos") { position ->
                        SegmentedNavigationItem(
                            position = position,
                            icon = Icons.Outlined.VideoLibrary,
                            title = "视频",
                            subtitle = if (videoStats.count > 0) "${formatCountExact(videoStats.count)} 个 · ${formatBytes(videoStats.bytes)}" else "聊天视频与收到的视频文件",
                            onClick = onOpenVideos,
                        )
                    }
                    item("bin") { position ->
                        val backups = if (backupStats.count > 0) " · 原图备份 ${formatCountExact(backupStats.count)} 张" else ""
                        SegmentedNavigationItem(
                            position = position,
                            icon = Icons.Outlined.Inventory2,
                            title = "回收站",
                            subtitle = "${formatCountExact(quarantineStats.count)} 张 · ${formatBytes(quarantineStats.bytes)}$backups",
                            onClick = onOpenRecycleBin,
                        )
                    }
                    item("qqcache") { position ->
                        SegmentedNavigationItem(
                            position = position,
                            icon = Icons.Outlined.FolderDelete,
                            title = "QQ 缓存",
                            subtitle = "QQ「清理缓存」会清空的目录",
                            onClick = onOpenQqCache,
                        )
                    }
                }
            }
        }
    }
}

/** 规则列表的副标题：参数 · 张数 · 大小（压缩按当前质量显示预计能省多少） */
internal fun ruleSubtitle(card: RuleCard, quality: Int): String {
    val option = card.rule.subtitle(card.option)
    val stats = card.stats ?: return listOf(option, "统计中…").filter { it.isNotBlank() }.joinToString(" · ")
    val detail = when {
        stats.count == 0 -> "没有可处理的图片"
        card.rule.action == RuleAction.COMPRESS ->
            "${formatCountExact(stats.count)} 张 · ${formatBytes(stats.bytes)}，约省 ${formatBytes((stats.bytes * compressSaving(quality)).toLong())}"
        else -> "${formatCountExact(stats.count)} 张 · ${formatBytes(stats.bytes)}"
    }
    return listOf(option, detail).filter { it.isNotBlank() }.joinToString(" · ")
}

/** 手机照片原图（长边缩到 2560）按质量压缩大致能省的比例，只用于提示 */
internal fun compressSaving(quality: Int): Double = when {
    quality >= 90 -> 0.55
    quality >= 80 -> 0.68
    quality >= 70 -> 0.74
    quality >= 60 -> 0.78
    else -> 0.82
}

internal fun ruleIcon(rule: CleanupRule): ImageVector = when (rule) {
    CleanupRule.Thumbnails -> Icons.Outlined.PhotoSizeSelectSmall
    CleanupRule.SlimOriginals -> Icons.Outlined.PhotoSizeSelectLarge
    CleanupRule.CompressOriginals -> Icons.Outlined.Compress
    CleanupRule.Stickers -> Icons.Outlined.GifBox
    CleanupRule.OldPictures -> Icons.Outlined.History
    CleanupRule.GroupOld -> Icons.Outlined.Groups
    CleanupRule.DeletedChats -> Icons.Outlined.SpeakerNotesOff
    CleanupRule.SentPreviews -> Icons.Outlined.Outbox
    is CleanupRule.TimeRange -> Icons.Outlined.CalendarMonth
}

/**
 * 按聊天的规则还不能用时的说明（null = 可以用）。读过来源就能用（之后切回 Shizuku 也行，清理只需要文件访问）；
 * 「聊天记录已删除」还要完整读取过一次。
 */
internal fun chatRuleBlocker(rule: CleanupRule, sources: SourcesState): String? = when {
    !sources.read -> "先读取一次聊天来源"
    rule == CleanupRule.DeletedChats && sources.completeReadAt <= 0 -> "需要完整读取一次聊天来源"
    else -> null
}

/** 存储概览：缓存总大小 + 原图 / 预览图 / 缩略图占比条 + 已归档数 */
@Composable
private fun StorageCard(storage: StorageOverview?, archived: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(Icons.Outlined.Storage, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("QQ 图片缓存", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        storage?.let { formatBytes(it.totalBytes) } ?: "统计中…",
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                }
            }
            val parts = storage?.let {
                listOf(
                    StoragePart("原图", it.originals?.bytes ?: 0, it.originals?.count ?: 0, MaterialTheme.colorScheme.primary),
                    StoragePart("预览图", it.previews?.bytes ?: 0, it.previews?.count ?: 0, MaterialTheme.colorScheme.tertiary),
                    StoragePart("缩略图", it.thumbs.bytes, it.thumbs.count, MaterialTheme.colorScheme.secondary),
                    StoragePart("其他", it.others?.bytes ?: 0, it.others?.count ?: 0, MaterialTheme.colorScheme.outline),
                ).filter { part -> part.bytes > 0 }
            }.orEmpty()
            if (parts.isNotEmpty()) {
                val total = parts.sumOf { it.bytes }.coerceAtLeast(1)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    parts.forEach { part ->
                        Box(
                            Modifier
                                .weight((part.bytes.toFloat() / total).coerceAtLeast(0.02f))
                                .fillMaxHeight()
                                .background(part.color, RoundedCornerShape(6.dp)),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    parts.forEach { part ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(part.color, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(part.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                "${formatCountExact(part.count)} 张 · ${formatBytes(part.bytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                if (archived > 0) "已归档 ${formatCountExact(archived)} 张，清理不会动它们" else "要留的图先归档，清理不会动它们",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class StoragePart(val label: String, val bytes: Long, val count: Int, val color: Color)

/** 正在执行的规则：进度 + 停止（也可以在通知栏停止） */
@Composable
internal fun RunningCard(running: CleanupRunner.Running, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running.action == RuleAction.COMPRESS) "正在压缩原图" else "正在清理：${running.title}",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Text(
                        "${formatCountExact(running.done)} / ${formatCountExact(running.total)} 张 · 可离开本页",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text("停止") }
            }
            WavyProgress(fraction = running.total.takeIf { it > 0 }?.let { running.done.toFloat() / it })
        }
    }
}

/** 清理 / 移入回收站进度对话框：完成后由调用方置空自动消失，不可手动关闭；不到半秒就做完的操作不弹出，免得一闪而过 */
@Composable
internal fun PurgeProgressDialog(progress: PurgeProgress) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PROGRESS_DIALOG_DELAY_MS)
        visible = true
    }
    if (!visible) return
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

private const val PROGRESS_DIALOG_DELAY_MS = 500L

internal fun formatQuarantineDate(ts: Long): String = formatDateTime(ts)

internal fun autoDeleteText(expiresAt: Long): String {
    val remaining = expiresAt - System.currentTimeMillis()
    if (remaining <= 0) return "即将清理"
    val days = remaining / 86_400_000L
    return if (days >= 1) "$days 天后" else "不到 1 天"
}
