package com.xjw.qalbum.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.ui.common.formatTitleDate
import com.xjw.qalbum.ui.common.imageMeta
import com.xjw.qalbum.ui.detail.PhotoDetailsSheet
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import kotlinx.coroutines.launch

/** 看图页浮层统一用深色半透明容器，保证在任何图片上都清晰 */
internal val ViewerChromeColor = Color(0xFF1E1F22).copy(alpha = 0.82f)

/**
 * 全屏图片预览：左右滑动切换、双指 / 双击缩放（见 [ZoomableImage]），单击显隐浮层。
 * 与图库看图页手势一致：上滑查看详情、详情打开时下滑收起；未缩放时下滑可拖动关闭。
 * 顶部「关闭 + 时间 + 详情 + 保存 + 分享」；底部操作由调用方经 [bottomBar] 提供，
 * 放在胶囊形浮动操作栏里（清理规则页：归档 / 压缩 / 移入回收站；聊天来源：归档 / 压缩 / 删除等）。
 *
 * 「详情」复用 [PhotoDetailsSheet]，从底部滑入；点详情卡以外任意处关闭。
 * [images] 是预览时的快照（调用方负责取已加载的有限集合；删除后传入新列表即可）。
 * 提供 [variantsOf] 时（合并显示），底部出现版本条，可切换显示同一张图的其他版本；[refreshKey] 变化时重新取版本
 * （例如归档状态变了）。[bottomBar] 拿到的是正在显示的那个版本。
 */
@Composable
internal fun FullScreenImagePager(
    images: List<ImageEntity>,
    startIndex: Int,
    onClose: () -> Unit,
    onShare: (Long) -> Unit,
    exportSubdirs: List<String>,
    onCreateSubdir: (String) -> Unit,
    onSave: (id: Long, subdir: String?, alsoTrash: Boolean) -> Unit,
    variantsOf: (suspend (ImageEntity) -> List<ImageEntity>)? = null,
    refreshKey: Any? = null,
    bottomBar: @Composable (current: ImageEntity) -> Unit,
) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { images.size }
    var detailsVisible by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomReset by remember { mutableIntStateOf(0) }
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissDistance = with(density) { 120.dp.toPx() }
    val dismissVelocity = with(density) { 600.dp.toPx() }
    val dismissRange = with(density) { 400.dp.toPx() }
    val velocityTracker = remember { VelocityTracker() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var draggingClose by remember { mutableStateOf(false) }
    var directionDecided by remember { mutableStateOf(false) }
    val springBack = remember { Animatable(0f) }
    val latestOnClose by rememberUpdatedState(onClose)
    // 合并显示：各页的全部版本，以及每页正在显示的版本（页图片 id → 版本 id）
    var variantCache by remember { mutableStateOf(mapOf<Long, List<ImageEntity>>()) }
    var shownVersions by remember { mutableStateOf(mapOf<Long, Long>()) }
    fun shownOn(pageImage: ImageEntity): ImageEntity {
        val wanted = shownVersions[pageImage.id] ?: pageImage.id
        return variantCache[pageImage.id]?.firstOrNull { it.id == wanted } ?: pageImage
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(enabled = detailsVisible || zoom > 1f) {
            if (detailsVisible) detailsVisible = false else zoomReset++
        }
        fun settleBack(from: Float) {
            draggingClose = false
            scope.launch {
                springBack.snapTo(from)
                springBack.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
            }
            chromeVisible = true
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val offset = if (draggingClose) dragOffset else springBack.value
                    drawRect(Color.Black.copy(alpha = 1f - (offset / dismissRange).coerceIn(0f, 1f) * 0.7f))
                }
                // 竖向手势：上滑打开详情、详情中下滑收起；未缩放时下滑拖动关闭（横向交给 pager）
                .pointerInput(density.density) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            directionDecided = false
                            draggingClose = false
                            dragOffset = 0f
                            velocityTracker.resetTracking()
                        },
                        onDragEnd = {
                            directionDecided = false
                            if (!draggingClose) return@detectVerticalDragGestures
                            val velocity = runCatching { velocityTracker.calculateVelocity().y }.getOrDefault(0f)
                            if (dragOffset > dismissDistance || (dragOffset > 0f && velocity > dismissVelocity)) {
                                latestOnClose()
                            } else {
                                settleBack(dragOffset)
                            }
                        },
                        onDragCancel = {
                            directionDecided = false
                            if (draggingClose) settleBack(dragOffset)
                        },
                    ) { change, amount ->
                        if (!directionDecided) {
                            directionDecided = true
                            draggingClose = !detailsVisible && zoom <= 1.05f && amount > 0f
                        }
                        if (draggingClose) {
                            change.consume()
                            chromeVisible = false
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                        } else if (zoom <= 1.01f && amount < -8f && !detailsVisible) {
                            change.consume()
                            chromeVisible = true
                            detailsVisible = true
                        } else if (amount > 8f && detailsVisible) {
                            change.consume()
                            detailsVisible = false
                        }
                    }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                key = { images[it].id },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 下滑关闭时图片跟手下移、缩小并圆角；在图层里读偏移，拖动不触发重组
                        val offset = if (draggingClose) dragOffset else springBack.value
                        val progress = (offset / dismissRange).coerceIn(0f, 1f)
                        translationY = offset
                        scaleX = 1f - progress * 0.15f
                        scaleY = 1f - progress * 0.15f
                        val radius = progress * 24.dp.toPx()
                        shape = RoundedCornerShape(radius)
                        clip = radius > 0f
                    },
            ) { page ->
                val shown = shownOn(images[page])
                ZoomableImage(
                    model = ImageEntityModel(shown),
                    contentDescription = shown.filename,
                    modifier = Modifier.fillMaxSize(),
                    stateKey = shown.id,
                    isActive = page == pagerState.currentPage,
                    resetSignal = zoomReset,
                    onTap = {
                        chromeVisible = !chromeVisible
                        if (!chromeVisible) detailsVisible = false
                    },
                    onZoomChanged = { if (page == pagerState.currentPage) zoom = it },
                )
            }

            val page = pagerState.currentPage.coerceIn(0, images.lastIndex)
            val pageImage = images[page]
            if (variantsOf != null) {
                LaunchedEffect(pageImage.id, refreshKey) { variantCache = variantCache + (pageImage.id to variantsOf(pageImage)) }
            }
            val variants = variantCache[pageImage.id].orEmpty()
            val current = shownOn(pageImage)

            AnimatedVisibility(
                visible = chromeVisible && !draggingClose,
                enter = fadeIn(fade),
                exit = fadeOut(fade),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(start = 4.dp, top = 6.dp, end = 4.dp, bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerIconButton(Icons.Outlined.Close, "关闭", onClose)
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            formatTitleDate(current.mtime),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${page + 1}/${images.size} · ${imageMeta(current)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ViewerIconButton(Icons.Outlined.Info, "详情") { detailsVisible = true }
                    ViewerIconButton(Icons.Outlined.Download, "保存") { showSaveSheet = true }
                    ViewerIconButton(Icons.Outlined.Share, "分享") { onShare(current.id) }
                }
            }

            AnimatedVisibility(
                visible = chromeVisible && !detailsVisible && !draggingClose,
                enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 2 } + fadeIn(fade),
                exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 2 } + fadeOut(fade),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (variants.size >= 2) {
                        VariantStrip(
                            variants = variants,
                            currentId = current.id,
                            onSelect = { id -> shownVersions = shownVersions + (pageImage.id to id) },
                        )
                    }
                    ViewerActionBar { bottomBar(current) }
                }
            }

            // 「查看详情」：复用图库详情卡（文件信息 + QQ 来源），从底部滑入
            if (detailsVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            detailsVisible = false
                        },
                )
            }
            AnimatedVisibility(
                visible = detailsVisible,
                enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(fade),
                exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(fade),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                PhotoDetailsSheet(image = current, onClose = { detailsVisible = false })
            }

            if (showSaveSheet) {
                SaveToAlbumSheet(
                    imageCount = 1,
                    subdirs = exportSubdirs,
                    onCreateSubdir = onCreateSubdir,
                    onDismiss = { showSaveSheet = false },
                    onConfirm = { subdir, alsoTrash ->
                        showSaveSheet = false
                        onSave(current.id, subdir, alsoTrash)
                    },
                    allowTrash = !current.isArchived,
                )
            }
        }
    }
}

/** 看图页底部的胶囊操作栏（深色半透明，导航栏之上留白） */
@Composable
internal fun ViewerActionBar(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = ViewerChromeColor,
            contentColor = Color.White,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) { content() }
        }
    }
}

@Composable
internal fun ViewerIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

/** 预览操作项（图标 + 文案的竖排按钮）：放在 [ViewerActionBar] 里 */
@Composable
internal fun PreviewAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
