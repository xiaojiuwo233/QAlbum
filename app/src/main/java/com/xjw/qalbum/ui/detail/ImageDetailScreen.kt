package com.xjw.qalbum.ui.detail

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.ui.anim.sharedImage
import com.xjw.qalbum.ui.components.SaveToAlbumSheet
import com.xjw.qalbum.ui.components.ZoomableImage
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ImageDetailScreen(
    imageId: Long,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    favoritesOnly: Boolean = false,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    viewModel: ImageDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(imageId, favoritesOnly) { viewModel.load(imageId, favoritesOnly) }
    val state by viewModel.state.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val context = LocalContext.current
    var showSaveSheet by remember { mutableStateOf(false) }
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(contentPadding),
    ) {
        when (val s = state) {
            ImageDetailState.Loading -> CenteredLoading()
            ImageDetailState.NotFound -> CenteredText("图片不存在或已被清理")
            is ImageDetailState.Ready -> ImmersivePhotoViewer(
                state = s,
                entityFor = viewModel::entityFor,
                onBack = onBack,
                onPageSelected = viewModel::selectIndex,
                onToggleFavorite = viewModel::toggleFavorite,
                onExport = { showSaveSheet = true },
                onOpenExternal = {
                    viewModel.prepareOpenExternal { target ->
                        target ?: return@prepareOpenExternal
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(target.uri, target.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "打开图片"))
                        }.onFailure {
                            Toast.makeText(context, "没有可用的第三方应用", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onMoveToTrash = { viewModel.moveCurrentToTrash(onExhausted = onBack) },
                onDeletePermanently = { viewModel.deleteCurrentPermanently(onExhausted = onBack) },
                sharedScope = sharedScope,
                animatedScope = animatedScope,
            )
        }
    }

    if (showSaveSheet) {
        SaveToAlbumSheet(
            imageCount = 1,
            subdirs = exportSubdirs,
            onCreateSubdir = viewModel::createSubdir,
            onDismiss = { showSaveSheet = false },
            onConfirm = { subdir, alsoTrash ->
                showSaveSheet = false
                viewModel.saveCurrent(subdir, alsoTrash, onExhausted = onBack)
            },
        )
    }
}

@Composable
private fun ImmersivePhotoViewer(
    state: ImageDetailState.Ready,
    entityFor: suspend (Long) -> ImageEntity?,
    onBack: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onExport: () -> Unit,
    onOpenExternal: () -> Unit,
    onMoveToTrash: () -> Unit,
    onDeletePermanently: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var detailsVisible by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var zoomReset by remember { mutableIntStateOf(0) }
    var dismissOffsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }
    var draggingClose by remember { mutableStateOf(false) }
    var dragDirectionDecided by remember { mutableStateOf(false) }
    val springBack = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissDistance = with(density) { 120.dp.toPx() }
    val dismissVelocity = with(density) { 600.dp.toPx() }
    val dismissRange = with(density) { 400.dp.toPx() }
    val latestOnBack by rememberUpdatedState(onBack)
    val latestOnPageSelected by rememberUpdatedState(onPageSelected)
    val pagerState = rememberPagerState(
        initialPage = state.currentIndex,
        pageCount = { state.ids.size },
    )

    LaunchedEffect(pagerState, state.ids) {
        if (pagerState.currentPage != state.currentIndex) {
            pagerState.scrollToPage(state.currentIndex)
        }
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                latestOnPageSelected(page)
            }
    }
    BackHandler(enabled = detailsVisible || currentZoom > 1f) {
        if (detailsVisible) detailsVisible = false else zoomReset++
    }

    // 组合期只订阅「是否处于下滑位移中」的布尔翻转（derivedStateOf 只在翻转时失效）；
    // 逐帧变化的偏移量本体延迟到绘制阶段读取，下滑关闭/回弹期间不会逐帧重组整个 viewer
    val notDismissing by remember {
        derivedStateOf { (if (draggingClose) dismissOffsetY else springBack.value) < 1f }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val offsetY = if (draggingClose) dismissOffsetY else springBack.value
                val progress = (offsetY / dismissRange).coerceIn(0f, 1f)
                drawRect(Color.Black.copy(alpha = 1f - progress * 0.7f))
            }
            // 手势 lambda 内对 detailsVisible/currentZoom 的读取都是经 state 委托的实时读，
            // 无需作为 key —— 以 currentZoom 作 key 会让捏合的每一帧都重启拖拽手势协程
            .pointerInput(density.density) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragDirectionDecided = false
                        draggingClose = false
                        dismissOffsetY = 0f
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        if (!draggingClose) {
                            dragDirectionDecided = false
                            return@detectVerticalDragGestures
                        }
                        val finalOffset = dismissOffsetY
                        val velocity = try {
                            velocityTracker.calculateVelocity().y
                        } catch (_: Exception) {
                            0f
                        }
                        dragDirectionDecided = false
                        if (finalOffset > dismissDistance || (finalOffset > 0f && velocity > dismissVelocity)) {
                            // 关闭：图片停在当前下滑位置（draggingClose 不复位，避免跳回原位），
                            // 直接返回 —— 配合「图库 popEnter 立即显示 + 详情 popExit 缩小淡出」，
                            // 图片淡出即露出图库，没有飞出后那段纯黑
                            latestOnBack()
                        } else {
                            draggingClose = false
                            scope.launch {
                                springBack.snapTo(finalOffset)
                                springBack.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                            chromeVisible = true
                        }
                    },
                    onDragCancel = {
                        if (!draggingClose) return@detectVerticalDragGestures
                        val finalOffset = dismissOffsetY
                        draggingClose = false
                        dragDirectionDecided = false
                        scope.launch {
                            springBack.snapTo(finalOffset)
                            springBack.animateTo(
                                0f,
                                spring(stiffness = Spring.StiffnessMedium),
                            )
                        }
                        chromeVisible = true
                    },
                ) { change, dragAmount ->
                    if (!dragDirectionDecided) {
                        dragDirectionDecided = true
                        val canDismiss = !detailsVisible && currentZoom <= 1.05f
                        draggingClose = canDismiss && dragAmount > 0f
                    }
                    if (draggingClose) {
                        // 双向都累加并采样速度：往回拖能实时抵消偏移，拖回原位松手不会误关闭
                        change.consume()
                        chromeVisible = false
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        dismissOffsetY = (dismissOffsetY + dragAmount).coerceAtLeast(0f)
                    } else if (currentZoom <= 1.01f && dragAmount < -8f && !detailsVisible) {
                        change.consume()
                        chromeVisible = true
                        detailsVisible = true
                    } else if (dragAmount > 8f && detailsVisible) {
                        change.consume()
                        detailsVisible = false
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 偏移/进度在图层 lambda 内读取：状态逐帧变化只更新图层，不触发重组
                    val offsetY = if (draggingClose) dismissOffsetY else springBack.value
                    val progress = (offsetY / dismissRange).coerceIn(0f, 1f)
                    translationY = offsetY
                    val dismissScale = 1f - progress * 0.15f
                    scaleX = dismissScale
                    scaleY = dismissScale
                    alpha = 1f - progress * 0.2f
                    val radius = progress * 24.dp.toPx()
                    shape = RoundedCornerShape(radius)
                    clip = radius > 0f
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { index -> state.ids[index] },
            ) { page ->
                FullscreenPhoto(
                    id = state.ids[page],
                    fallback = if (page == state.currentIndex) state.current else null,
                    entityFor = entityFor,
                    isActive = page == pagerState.currentPage,
                    resetSignal = zoomReset,
                    onTap = {
                        chromeVisible = !chromeVisible
                        if (!chromeVisible) detailsVisible = false
                    },
                    onZoomChanged = { zoom ->
                        if (page == pagerState.currentPage) currentZoom = zoom
                    },
                    sharedScope = sharedScope,
                    animatedScope = animatedScope,
                )
            }

            AnimatedVisibility(
                visible = chromeVisible && notDismissing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopChrome(
                    image = state.current,
                    // 窗口化后 ids 只是时间线片段：序号 = 窗口首元素全序 rank + 窗口内下标
                    index = (state.rankOffset + state.currentIndex)
                        .coerceIn(0, (state.totalCount - 1).coerceAtLeast(0)),
                    total = state.totalCount,
                    onBack = onBack,
                    onOpenExternal = onOpenExternal,
                )
            }

            AnimatedVisibility(
                visible = chromeVisible && !detailsVisible && notDismissing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BottomActions(
                    image = state.current,
                    onToggleFavorite = onToggleFavorite,
                    onInfo = {
                        chromeVisible = true
                        detailsVisible = true
                    },
                    onExport = onExport,
                    onDelete = { confirmDelete = true },
                )
            }
        }

        AnimatedVisibility(
            visible = detailsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PhotoDetailsSheet(
                image = state.current,
                ocr = state.ocr,
                onClose = { detailsVisible = false },
            )
        }

        if (confirmDelete) {
            DeleteConfirmDialog(
                onDismiss = { confirmDelete = false },
                onTrash = {
                    confirmDelete = false
                    onMoveToTrash()
                },
                onPermanent = {
                    confirmDelete = false
                    onDeletePermanently()
                },
            )
        }
    }
}

@Composable
private fun FullscreenPhoto(
    id: Long,
    fallback: ImageEntity?,
    entityFor: suspend (Long) -> ImageEntity?,
    isActive: Boolean,
    resetSignal: Int,
    onTap: () -> Unit,
    onZoomChanged: (Float) -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    // 当前页用 fallback（已加载的 current）立即显示；相邻页按 id 异步取实体
    val image by produceState<ImageEntity?>(initialValue = fallback, key1 = id) {
        if (value == null) value = entityFor(id)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val entity = image
        if (entity == null) {
            CircularProgressIndicator(color = Color.White)
            return@Box
        }
        ZoomableImage(
            model = ImageEntityModel(entity),
            contentDescription = entity.filename,
            stateKey = id,
            isActive = isActive,
            resetSignal = resetSignal,
            onTap = onTap,
            onZoomChanged = onZoomChanged,
            modifier = Modifier
                .fillMaxSize()
                .sharedImage("image-$id", sharedScope, animatedScope),
        )
    }
}

@Composable
private fun TopChrome(
    image: ImageEntity,
    index: Int,
    total: Int,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                ),
            )
            .statusBarsPadding()
            .padding(start = 4.dp, top = 6.dp, end = 16.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                formatTitleDate(image.mtime),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                "${index + 1}/$total   ${sourceLabel(image.sourceType)}   ${formatSizeCompact(image.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
        CircleIconButton(onClick = onOpenExternal) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "用第三方应用打开", tint = Color.White)
        }
    }
}

@Composable
private fun BottomActions(
    image: ImageEntity,
    onToggleFavorite: () -> Unit,
    onInfo: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                ),
            )
            .padding(start = 16.dp, top = 42.dp, end = 16.dp, bottom = 8.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerAction(
            icon = if (image.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            label = if (image.isFavorite) "取消收藏" else "收藏",
            onClick = onToggleFavorite,
        )
        ViewerAction(Icons.Outlined.Info, "详情", onInfo)
        ViewerAction(Icons.Outlined.IosShare, "导出", onExport)
        ViewerAction(Icons.Outlined.Delete, "删除", onDelete)
    }
}

@Composable
private fun DeleteConfirmDialog(
    onDismiss: () -> Unit,
    onTrash: () -> Unit,
    onPermanent: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这张图片？") },
        text = { Text("可以先移动到回收站，或直接永久删除。永久删除后不可恢复。") },
        confirmButton = {
            TextButton(onClick = onTrash) { Text("移动到回收站") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onPermanent) { Text("永久删除") }
            }
        },
    )
}

@Composable
private fun ViewerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircleIconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.88f),
        )
    }
}

@Composable
private fun CircleIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
    ) {
        content()
    }
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}

private val titleFormatter = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)

private fun formatTitleDate(ts: Long): String = titleFormatter.format(Date(ts))

/** 来源目录映射成短标签：chatimg→img、chatraw→raw、chatthumb→thumb */
private fun sourceLabel(sourceType: String): String = when (sourceType) {
    "chatimg" -> "img"
    "chatraw" -> "raw"
    "chatthumb" -> "thumb"
    else -> sourceType.ifBlank { "其他" }
}

/** 顶部信息条用的紧凑大小：512 B / 12.3 KB / 1.7 MB */
private fun formatSizeCompact(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    if (exp == 0) return "$bytes B"
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.1f %s", value, units[exp])
}
