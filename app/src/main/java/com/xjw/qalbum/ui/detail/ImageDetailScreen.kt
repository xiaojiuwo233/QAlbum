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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.ui.anim.sharedImage
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatTitleDate
import com.xjw.qalbum.ui.common.sourceLabel
import com.xjw.qalbum.ui.components.DeleteImagesDialog
import com.xjw.qalbum.service.cleanup.CompressionCoordinator
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import com.xjw.qalbum.ui.components.PreviewAction
import com.xjw.qalbum.ui.components.SaveToAlbumSheet
import com.xjw.qalbum.ui.components.VariantStrip
import com.xjw.qalbum.ui.components.ViewerActionBar
import com.xjw.qalbum.ui.components.ViewerIconButton
import com.xjw.qalbum.ui.components.ZoomableImage
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun ImageDetailScreen(
    imageId: Long,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    archiveOnly: Boolean = false,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    viewModel: ImageDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(imageId, archiveOnly) { viewModel.load(imageId, archiveOnly) }
    val state by viewModel.state.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val context = LocalContext.current
    var showSaveSheet by remember { mutableStateOf(false) }
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    val compression = koinInject<CompressionCoordinator>()

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
                onToggleArchive = { viewModel.toggleArchive(onExhausted = onBack) },
                onExport = { showSaveSheet = true },
                onCompress = { compression.start(listOf(s.current.id)) },
                onSelectVariant = viewModel::selectVariant,
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
                onMoveToTrash = { options -> viewModel.moveCurrentToTrash(options,onExhausted = onBack) },
                onDeletePermanently = { options -> viewModel.deleteCurrentPermanently(options,onExhausted = onBack) },
                archiveOnly = archiveOnly,
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
            allowTrash = archiveOnly || (state as? ImageDetailState.Ready)?.current?.isArchived != true,
        )
    }
}

@Composable
private fun ImmersivePhotoViewer(
    state: ImageDetailState.Ready,
    entityFor: suspend (Long) -> ImageEntity?,
    onBack: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onSelectVariant: (Long) -> Unit,
    onToggleArchive: () -> Unit,
    onExport: () -> Unit,
    onCompress: () -> Unit,
    onOpenExternal: () -> Unit,
    onMoveToTrash: (PictureDeleteOptions) -> Unit,
    onDeletePermanently: (PictureDeleteOptions) -> Unit,
    /** 从归档页打开：归档的图可以在这里删除 */
    archiveOnly: Boolean = false,
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
    val chromeFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val chromeSlideIn = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val chromeSlideOut = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()
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
                    // 当前页显示正在选中的版本（合并显示时可在底部版本条切换）
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
                enter = fadeIn(chromeFade),
                exit = fadeOut(chromeFade),
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
                enter = slideInVertically(chromeSlideIn) { it / 2 } + fadeIn(chromeFade),
                exit = slideOutVertically(chromeSlideOut) { it / 2 } + fadeOut(chromeFade),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.variants.size >= 2) {
                        VariantStrip(
                            variants = state.variants,
                            currentId = state.current.id,
                            onSelect = onSelectVariant,
                        )
                    }
                    BottomActions(
                        image = state.current,
                        onToggleArchive = onToggleArchive,
                        onCompress = onCompress,
                        onInfo = {
                            chromeVisible = true
                            detailsVisible = true
                        },
                        onExport = onExport,
                        onDelete = { confirmDelete = true },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = detailsVisible,
            enter = slideInVertically(chromeSlideIn) { it } + fadeIn(chromeFade),
            exit = slideOutVertically(chromeSlideOut) { it } + fadeOut(chromeFade),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PhotoDetailsSheet(
                image = state.current,
                onClose = { detailsVisible = false },
            )
        }

        if (confirmDelete) {
            DeleteImagesDialog(
                imageIds = listOf(state.current.id),
                includeArchived = archiveOnly,
                onDismiss = { confirmDelete = false },
                onTrash = { options ->
                    confirmDelete = false
                    onMoveToTrash(options)
                },
                onPermanent = { options ->
                    confirmDelete = false
                    onDeletePermanently(options)
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
    // 当前页用 fallback（已加载的 current，可能是切换到的其他版本）立即显示；相邻页按 id 异步取实体
    val loaded by produceState<ImageEntity?>(initialValue = fallback, key1 = id) {
        if (value == null) value = entityFor(id)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val entity = fallback ?: loaded
        if (entity == null) {
            LoadingIndicator(color = Color.White)
            return@Box
        }
        ZoomableImage(
            model = ImageEntityModel(entity),
            contentDescription = entity.filename,
            // 切换版本时复位缩放
            stateKey = entity.id,
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
            .background(Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .statusBarsPadding()
            .padding(start = 4.dp, top = 6.dp, end = 8.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack)
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                formatTitleDate(image.mtime),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${index + 1} / $total · ${sourceLabel(image.sourceType)} · ${formatBytes(image.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
        ViewerIconButton(Icons.AutoMirrored.Outlined.OpenInNew, "用其他应用打开", onOpenExternal)
    }
}

@Composable
private fun BottomActions(
    image: ImageEntity,
    onToggleArchive: () -> Unit,
    onCompress: () -> Unit,
    onInfo: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    ViewerActionBar {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewAction(
                icon = if (image.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                label = if (image.isArchived) "取消归档" else "归档",
                onClick = onToggleArchive,
            )
            PreviewAction(Icons.Outlined.Compress, "压缩", onClick = onCompress)
            PreviewAction(Icons.Outlined.Info, "详情", onClick = onInfo)
            PreviewAction(Icons.Outlined.Download, "保存", onClick = onExport)
            PreviewAction(Icons.Outlined.Delete, "删除", onClick = onDelete)
        }
    }
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator(color = Color.White)
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}
