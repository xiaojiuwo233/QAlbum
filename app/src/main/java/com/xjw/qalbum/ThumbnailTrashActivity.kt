package com.xjw.qalbum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.cleanup.CleanupViewModel
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatTitleDate
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.ViewerIconButton
import com.xjw.qalbum.ui.components.ZoomableImage
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import com.xjw.qalbum.ui.theme.QAlbumTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel

/** QQ 缩略图（chatthumb）浏览页：受保护，只能查看。 */
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

@Composable
private fun ThumbnailCacheScreen(onBack: () -> Unit, viewModel: CleanupViewModel = koinViewModel()) {
    val images = viewModel.thumbnailTrash.collectAsLazyPagingItems()
    val summary by viewModel.thumbnailTrashSummary.collectAsState()
    var preview by remember { mutableStateOf<Pair<List<ImageEntity>, Int>?>(null) }
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("QQ 缩略图") },
                subtitle = { Text("${summary.count} 项 · ${formatBytes(summary.bytes)} · 受保护，只能浏览") },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "explain") { ProtectionCard() }
            if (images.itemCount == 0) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Box(Modifier.fillMaxWidth().height(320.dp)) {
                        EmptyState(icon = Icons.Outlined.Image, title = "还没有索引到 QQ 缩略图")
                    }
                }
            } else {
                items(images.itemCount, key = { images.peek(it)?.id ?: "loading-$it" }) { index ->
                    val image = images[index]
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .animateItem()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        image?.let {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(ImageEntityModel(it)).crossfade(true).build(),
                                contentDescription = it.filename,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clickable {
                                    val loaded = images.itemSnapshotList.items
                                    val position = loaded.indexOfFirst { item -> item.id == it.id }
                                    if (position >= 0) preview = loaded to position
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    preview?.let { (list, start) -> ThumbnailPreview(list, start, onClose = { preview = null }) }
}

/**
 * 说明为什么缩略图受保护（依据 QQ 9.2.50 聊天窗口图片消息的加载逻辑）：可展开查看细节。
 */
@Composable
private fun ProtectionCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "expand")
    Card(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(
                    Icons.Outlined.Shield,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    shape = MaterialShapes.Cookie7Sided.toShape(),
                )
                Spacer(Modifier.width(12.dp))
                Text("为什么这些文件不能删？", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
            Text(
                "这是 QQ 聊天窗口里图片消息优先显示的缩略图。很多群聊图片只是刷到过、从没点开，本机唯一的副本就是这里的缩略图。",
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
                        "删掉缩略图后：图片还在 QQ 服务器保存期内，打开聊天时会重新下载；已经过期、本机又没有预览图或原图的，会变成「图片已过期」，再也找不回。正在打开的聊天还可能先显示加载失败。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "缩略图通常只有几 KB 到几十 KB，删掉省下的空间很少，所以 QAlbum 只提供浏览，不提供删除。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** 只读预览：左右滑动、缩放，单击显隐信息栏 */
@Composable
private fun ThumbnailPreview(images: List<ImageEntity>, start: Int, onClose: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = start.coerceIn(0, images.lastIndex)) { images.size }
    var chromeVisible by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { images[it].id }) { page ->
                ZoomableImage(
                    model = ImageEntityModel(images[page]),
                    contentDescription = images[page].filename,
                    modifier = Modifier.fillMaxSize(),
                    stateKey = images[page].id,
                    isActive = page == pagerState.currentPage,
                    onTap = { chromeVisible = !chromeVisible },
                )
            }
            val current = images[pagerState.currentPage.coerceIn(0, images.lastIndex)]
            AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(start = 4.dp, top = 6.dp, end = 12.dp, bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerIconButton(Icons.Outlined.Close, "关闭", onClose)
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            formatTitleDate(current.mtime),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${pagerState.currentPage + 1}/${images.size} · ${formatBytes(current.size)} · 受保护",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
    }
}
