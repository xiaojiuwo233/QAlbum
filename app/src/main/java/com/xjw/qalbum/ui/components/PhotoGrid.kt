package com.xjw.qalbum.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.ui.anim.sharedImage
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.gallery.EnabledPhases
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.gallery.isFullyAnalyzed
import com.xjw.qalbum.ui.imageloading.ImageEntityModel

/** 网格图块上显示哪些角标 */
@Immutable
data class PhotoBadges(
    val favorite: Boolean = true,
    /** 超过该字节数显示「MB+」；null 不显示 */
    val largeThresholdBytes: Long? = null,
    /** 已启用分析项全部完成时显示小对勾；null 不显示 */
    val analyzedPhases: EnabledPhases? = null,
    /** 右下角显示文件大小（按大小排序时更直观） */
    val size: Boolean = false,
    /** 合并显示同一张图的多个版本：左下角显示版本数，大小按全部版本合计 */
    val stack: Boolean = false,
)

/**
 * 图库式照片网格（图库 / 收藏 / 标签 / QQ 来源共用）。
 *
 * - 日期 / 大小分组标题横跨整行；
 * - 单击：非选择态打开，选择态切换勾选；长按进入选择，**长按后拖动连续勾选**（起点未选则一路选中，
 *   起点已选则一路取消）；
 * - 双指捏合调整列数；选中图块缩小并变圆角，勾选标记弹性出现。
 */
@Composable
fun PhotoGrid(
    items: LazyPagingItems<GalleryTimelineItem>,
    columns: Int,
    selectedIds: Set<Long>,
    onOpen: (ImageEntity) -> Unit,
    onSelectionChange: (id: Long, selected: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onColumnsChange: ((Int) -> Unit)? = null,
    minColumns: Int = 2,
    maxColumns: Int = 8,
    badges: PhotoBadges = PhotoBadges(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    leadingContent: (LazyGridScope.() -> Unit)? = null,
) {
    val selectionMode = selectedIds.isNotEmpty()
    // 命中矩形只被手势闭包读取、不参与组合：普通 HashMap 免去滚动期逐帧快照写入；
    // 图块离屏销毁时移除（见 DisposableEffect），规模约等于可见项数
    val photoBounds = remember { HashMap<Long, Rect>() }
    var containerBounds by remember { mutableStateOf(Rect.Zero) }
    // 手势协程只在 key 变化时重启，经 rememberUpdatedState 读取最新的选择集与回调
    val currentSelected by rememberUpdatedState(selectedIds)
    val currentOnSelection by rememberUpdatedState(onSelectionChange)
    val haptic = LocalHapticFeedback.current
    val layoutDirection = LocalLayoutDirection.current

    fun idAt(rootOffset: Offset): Long? {
        val visible = gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }.toSet()
        return photoBounds.entries.firstOrNull { (id, bounds) -> id in visible && bounds.contains(rootOffset) }?.key
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + 2.dp,
            end = contentPadding.calculateEndPadding(layoutDirection) + 2.dp,
            top = contentPadding.calculateTopPadding() + 2.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { containerBounds = it.boundsInRoot() }
            .then(
                if (onColumnsChange == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(columns) {
                        awaitEachGesture {
                            var startDistance = 0f
                            var changed = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size < 2) break
                                val distance = (pressed[0].position - pressed[1].position).getDistance()
                                if (startDistance == 0f) startDistance = distance
                                val ratio = if (startDistance == 0f) 1f else distance / startDistance
                                if (!changed) {
                                    when {
                                        ratio > 1.16f -> {
                                            onColumnsChange((columns - 1).coerceIn(minColumns, maxColumns))
                                            changed = true
                                        }
                                        ratio < 0.86f -> {
                                            onColumnsChange((columns + 1).coerceIn(minColumns, maxColumns))
                                            changed = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            )
            // 长按选择 + 拖动连续勾选：在 Initial 阶段先于图块与网格滚动拿到事件。长按成立前不消费任何事件
            // （单击交给图块、滑动交给网格滚动）；成立后独占后续事件直到抬起，图块不会再触发单击，
            // 手指停在上下边缘时自动滚动并继续勾选。
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val edge = 72.dp.toPx()
                val maxStep = 26.dp.toPx()
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val notLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed || change.isConsumed) break
                                if (event.changes.size > 1) break
                                if ((change.position - down.position).getDistance() > slop) break
                            }
                            true
                        }
                        if (notLongPress != null) return@awaitEachGesture
                        val origin = Offset(containerBounds.left, containerBounds.top)
                        val startId = idAt(down.position + origin) ?: return@awaitEachGesture
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val target = startId !in currentSelected
                        val touched = mutableSetOf(startId)
                        currentOnSelection(startId, target)
                        var pointer = down.position
                        val autoScroll = launch {
                            while (isActive) {
                                val step = when {
                                    pointer.y < edge -> -maxStep * (edge - pointer.y) / edge
                                    pointer.y > size.height - edge -> maxStep * (pointer.y - (size.height - edge)) / edge
                                    else -> 0f
                                }
                                if (step != 0f) {
                                    gridState.scrollBy(step)
                                    idAt(pointer + origin)?.let { id -> if (touched.add(id)) currentOnSelection(id, target) }
                                }
                                delay(16)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                pointer = change.position
                                idAt(pointer + origin)?.let { id ->
                                    if (touched.add(id)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        currentOnSelection(id, target)
                                    }
                                }
                            }
                        } finally {
                            autoScroll.cancel()
                        }
                    }
                }
            },
    ) {
        leadingContent?.invoke(this)

        items(
            count = items.itemCount,
            key = { index ->
                when (val item = items.peek(index)) {
                    is GalleryTimelineItem.Header -> item.key
                    is GalleryTimelineItem.Photo -> item.image.id
                    null -> "loading-$index"
                }
            },
            span = { index ->
                if (items.peek(index) is GalleryTimelineItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            },
            contentType = { index ->
                when (items.peek(index)) {
                    is GalleryTimelineItem.Header -> "header"
                    else -> "photo"
                }
            },
        ) { index ->
            when (val item = items[index]) {
                is GalleryTimelineItem.Header -> PhotoSectionHeader(item.label, Modifier.animateItem())
                is GalleryTimelineItem.Photo -> {
                    val image = item.image
                    val id = image.id
                    DisposableEffect(id) { onDispose { photoBounds.remove(id) } }
                    val selected = id in selectedIds
                    PhotoTile(
                        image = image,
                        selected = selected,
                        selectionMode = selectionMode,
                        badges = badges,
                        modifier = Modifier
                            .animateItem()
                            .onGloballyPositioned { photoBounds[id] = it.boundsInRoot() },
                        onClick = {
                            if (selectionMode) onSelectionChange(id, !selected) else onOpen(image)
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelectionChange(id, true)
                        },
                        sharedScope = sharedScope,
                        animatedScope = animatedScope,
                    )
                }
                null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
        }

        if (items.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "append") {
                Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

@Composable
fun PhotoSectionHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 20.dp, end = 14.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 单个图块：选中时缩小 + 圆角，勾选标记弹性缩放进出 */
@Composable
fun PhotoTile(
    image: ImageEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    badges: PhotoBadges = PhotoBadges(),
    contentScale: ContentScale = ContentScale.Crop,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, spatial, label = "tileScale")
    val corner: Dp by animateDpAsState(
        if (selected) 16.dp else 0.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "tileCorner",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { this.selected = selected }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // 弹簧回弹会让圆角短暂越过目标值，负值圆角会直接抛异常
            .clip(RoundedCornerShape(corner.coerceAtLeast(0.dp)))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            // 长按与拖选由网格统一处理（见 PhotoGrid）：图块只响应单击，无障碍保留长按「选择」动作
            .clickable(onClick = onClick)
            .semantics { onLongClick(label = "选择") { onLongClick(); true } },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(ImageEntityModel(image)).crossfade(true).build(),
            contentDescription = image.filename,
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .sharedImage("image-${image.id}", sharedScope, animatedScope),
        )
        val showInfoBadges = !selectionMode
        // 合并显示时这张图代表整个家族：大小按全部版本合计
        val stacked = badges.stack && image.variantGroup != null && image.variantCount >= 2
        val bytes = if (stacked) image.variantBytes else image.size
        if (showInfoBadges && badges.favorite && image.isFavorite) {
            TileIconBadge(Icons.Outlined.Favorite, Modifier.align(Alignment.TopStart))
        }
        val threshold = badges.largeThresholdBytes
        if (showInfoBadges && threshold != null && bytes > threshold) {
            TileTextBadge("MB+", Modifier.align(Alignment.TopEnd))
        }
        val phases = badges.analyzedPhases
        val analyzed = showInfoBadges && phases != null && isFullyAnalyzed(image, phases)
        if (showInfoBadges && (stacked || analyzed)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
            ) {
                if (stacked) TileStackBadge(image.variantCount)
                if (analyzed) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.32f))
                            .padding(1.dp),
                        tint = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }
        if (badges.size) {
            TileTextBadge(formatBytes(bytes), Modifier.align(Alignment.BottomEnd))
        }
        SelectionMark(
            visible = selectionMode,
            selected = selected,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )
    }
}

/** 选择标记：选择态出现空心圈，选中后填充主色并弹出对勾 */
@Composable
fun SelectionMark(visible: Boolean, selected: Boolean, modifier: Modifier = Modifier) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(spatial) + fadeIn(effects),
        exit = scaleOut(spatial) + fadeOut(effects),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.28f))
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(visible = selected, enter = scaleIn(spatial), exit = scaleOut(spatial)) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
fun TileTextBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .padding(5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 合并显示的图片家族角标：叠放图标 + 版本数（同 Google 相册的图集） */
@Composable
fun TileStackBadge(count: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(start = 4.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = "共 $count 个版本" },
    ) {
        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text("$count", style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun TileIconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Icon(
        icon,
        contentDescription = null,
        modifier = modifier
            .padding(5.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(3.dp),
        tint = Color.White,
    )
}
