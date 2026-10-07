package com.xjw.qalbum

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import com.xjw.qalbum.ui.cleanup.ThumbnailCacheViewModel
import com.xjw.qalbum.ui.cleanup.shareChooser
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.DeleteImagesDialog
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.FloatingActionToolbar
import com.xjw.qalbum.ui.components.FullScreenImagePager
import com.xjw.qalbum.ui.components.LoadingState
import com.xjw.qalbum.ui.components.PhotoGrid
import com.xjw.qalbum.ui.components.PreviewAction
import com.xjw.qalbum.ui.components.SelectionTopBar
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.ToolbarPrimaryAction
import com.xjw.qalbum.ui.components.WavyProgress
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.theme.QAlbumTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel

/** QQ 缩略图页：按日期浏览全部 chatthumb 缩略图，可多选、按时间批量清理。 */
class ThumbnailTrashActivity : ComponentActivity() {
    private val settings: SettingsRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val dynamicColor by settings.dynamicColorEnabled.collectAsState(initial = true)
            QAlbumTheme(dynamicColor = dynamicColor) {
                ThumbnailCacheScreen(onBack = ::finish)
            }
        }
    }
}

private val SelectedIdsSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() })

/** 缩略图默认只清理所选缩略图本身；它们本就是可以重新下载的预览缓存 */
private val ThumbnailDeleteDefaults = PictureDeleteOptions(linkedVersions = false, includeShared = true)

@Composable
private fun ThumbnailCacheScreen(onBack: () -> Unit, viewModel: ThumbnailCacheViewModel = koinViewModel()) {
    val items = viewModel.photos.collectAsLazyPagingItems()
    val summary by viewModel.summary.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val message by viewModel.message.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var selectedIds by rememberSaveable(stateSaver = SelectedIdsSaver) { mutableStateOf(setOf<Long>()) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<List<ImageEntity>, Int>?>(null) }
    var pendingDelete by remember { mutableStateOf<ImageEntity?>(null) }
    val selectionMode = selectedIds.isNotEmpty()
    val clearSelection = { selectedIds = emptySet() }

    BackHandler(enabled = selectionMode) { clearSelection() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        // 在页面作用域里显示：清空消息会重启本副作用，直接 show 会被立刻取消
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun selectOlderThan(days: Int) {
        scope.launch {
            val ids = viewModel.idsOlderThan(days)
            if (ids.isEmpty()) snackbar.showSnackbar("没有 $days 天前的缩略图") else selectedIds = ids
        }
    }

    val topBarEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val topBarExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn(topBarEnter) togetherWith fadeOut(topBarExit) },
                label = "thumbnailTopBar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(
                        count = selectedIds.size,
                        onClose = clearSelection,
                        actions = {
                            IconButton(
                                onClick = { scope.launch { selectedIds = viewModel.allIds() } },
                                shapes = IconButtonDefaults.shapes(),
                            ) { Icon(Icons.Outlined.SelectAll, contentDescription = "全选") }
                        },
                    )
                } else {
                    LargeFlexibleTopAppBar(
                        title = { Text("QQ 缩略图") },
                        subtitle = {
                            Text(summary?.let { "${formatCountExact(it.count)} 张 · ${formatBytes(it.bytes)}" } ?: "正在统计…")
                        },
                        navigationIcon = { BackButton(onBack) },
                        actions = {
                            Box {
                                IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    listOf(30, 90, 180).forEach { days ->
                                        DropdownMenuItem(
                                            text = { Text("选择 $days 天前的缩略图") },
                                            leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                                            enabled = progress == null,
                                            onClick = { menuOpen = false; selectOlderThan(days) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("全选") },
                                        leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                                        enabled = progress == null,
                                        onClick = { menuOpen = false; scope.launch { selectedIds = viewModel.allIds() } },
                                    )
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = progress != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    val running = progress
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            running?.let { "正在清理缩略图 ${formatCountExact(it.done)} / ${formatCountExact(it.total)}" }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        WavyProgress(fraction = running?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total })
                    }
                }
                val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                when {
                    items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> LoadingState()
                    else -> PhotoGrid(
                        items = items,
                        columns = columns,
                        selectedIds = selectedIds,
                        onOpen = { image ->
                            val loaded = items.itemSnapshotList.items
                                .filterIsInstance<GalleryTimelineItem.Photo>()
                                .map { it.image }
                            val index = loaded.indexOfFirst { it.id == image.id }
                            if (index >= 0) preview = loaded to index
                        },
                        onSelectionChange = { id, selected ->
                            selectedIds = if (selected) selectedIds + id else selectedIds - id
                        },
                        onColumnsChange = viewModel::setColumns,
                        contentPadding = PaddingValues(bottom = bottomInset + if (selectionMode) 88.dp else 16.dp),
                        leadingContent = {
                            item(key = "explain", span = { GridItemSpan(maxLineSpan) }, contentType = "explain") {
                                ExplainCard(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp))
                            }
                            if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
                                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                    Box(Modifier.fillMaxWidth().height(320.dp)) {
                                        EmptyState(icon = Icons.Outlined.Image, title = "没有 QQ 缩略图", message = "扫描到的 chatthumb 缩略图会显示在这里")
                                    }
                                }
                            }
                        },
                    )
                }
            }

            FloatingActionToolbar(visible = selectionMode, modifier = Modifier.align(Alignment.BottomCenter)) {
                ToolbarPrimaryAction(Icons.Outlined.Delete, "删除", enabled = progress == null, onClick = { showDelete = true })
            }
        }
    }

    if (showDelete && selectedIds.isNotEmpty()) {
        val ids = selectedIds
        DeleteImagesDialog(
            count = ids.size,
            imageIds = ids,
            initialOptions = ThumbnailDeleteDefaults,
            sizeOf = { viewModel.sizeOf(ids) },
            note = "这些是 QQ 聊天里的图片预览。删除后 QQ 会在打开聊天时重新下载；服务器上已过期、本机又没有原图或预览图的，会显示「图片已过期」。",
            onDismiss = { showDelete = false },
            onTrash = {},
            onPermanent = {},
            onTrashOptions = { options ->
                showDelete = false
                clearSelection()
                viewModel.remove(ids, options, permanent = false) { items.refresh() }
            },
            onPermanentOptions = { options ->
                showDelete = false
                clearSelection()
                viewModel.remove(ids, options, permanent = true) { items.refresh() }
            },
        )
    }

    preview?.let { (list, start) ->
        FullScreenImagePager(
            images = list,
            startIndex = start,
            onClose = { preview = null },
            onShare = { id -> viewModel.share(id) { context.startActivity(shareChooser(it)) } },
            exportSubdirs = exportSubdirs,
            onCreateSubdir = viewModel::createSubdir,
            onSave = { id, subdir, alsoTrash ->
                viewModel.save(id, subdir, alsoTrash) { trashed -> if (trashed) { preview = preview?.without(id); items.refresh() } }
            },
            onLoadOcr = viewModel::ocr,
            bottomBar = { image ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PreviewAction(icon = Icons.Outlined.Delete, label = "删除", enabled = progress == null, onClick = { pendingDelete = image })
                }
                pendingDelete?.let { target ->
                    DeleteImagesDialog(
                        count = 1,
                        imageIds = listOf(target.id),
                        initialOptions = ThumbnailDeleteDefaults,
                        onDismiss = { pendingDelete = null },
                        onTrash = {},
                        onPermanent = {},
                        onTrashOptions = { options ->
                            pendingDelete = null
                            viewModel.remove(listOf(target.id), options, permanent = false) { removed ->
                                if (removed > 0) {
                                    preview = preview?.without(target.id)
                                    items.refresh()
                                } else {
                                    Toast.makeText(context, "这张缩略图未删除：已收藏、保留或文件已变化", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onPermanentOptions = { options ->
                            pendingDelete = null
                            viewModel.remove(listOf(target.id), options, permanent = true) { removed ->
                                if (removed > 0) {
                                    preview = preview?.without(target.id)
                                    items.refresh()
                                }
                            }
                        },
                    )
                }
            },
        )
    }
}

/** 从看图快照移除一张并停在下一张（删的是末张则后退），删空后关闭 */
private fun Pair<List<ImageEntity>, Int>.without(id: Long): Pair<List<ImageEntity>, Int>? {
    val index = first.indexOfFirst { it.id == id }
    if (index < 0) return this
    val remaining = first.filterNot { it.id == id }
    if (remaining.isEmpty()) return null
    return remaining to index.coerceAtMost(remaining.lastIndex)
}

/** 说明缩略图的作用与删除后果（依据 QQ 9.2.50 聊天窗口图片消息的加载逻辑），可展开查看细节 */
@Composable
private fun ExplainCard(modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "expand")
    Card(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(
                    Icons.Outlined.Info,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    shape = MaterialShapes.Cookie7Sided.toShape(),
                )
                Spacer(Modifier.width(12.dp))
                Text("QQ 聊天预览缓存", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
            Text(
                "QQ 聊天窗口里的图片消息优先显示这里的缩略图，长期使用会积累很多。可以按时间批量清理，也可以在「按聊天来源清理」里按群聊、发送人清理。",
                style = MaterialTheme.typography.bodyMedium,
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "QQ 显示聊天图片时依次查找：高清缩略图（_hd）→ 普通缩略图 → chatimg 预览图 → chatraw 原图，用第一个存在的文件。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "删除后：本机还有预览图或原图的，重新打开聊天会改用它们；只剩缩略图的图片（很多群图只是刷到过、没点开），QQ 会重新下载，服务器上已过期的会显示「图片已过期」。正在打开的聊天可能先显示加载失败。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "默认只清理所选缩略图本身；勾选「同时删除已关联的原图、预览图」会连同一张图的其他缓存一起清理。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
