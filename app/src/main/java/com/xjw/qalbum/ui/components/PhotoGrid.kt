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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.xjw.qalbum.domain.model.TimelineSection
import com.xjw.qalbum.ui.anim.sharedImage
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.imageloading.ImageEntityModel

/** 网格图块上显示哪些角标 */
@Immutable
data class PhotoBadges(
    /** 左上角标出已归档的图（归档照常显示时才会出现在归档页以外） */
    val archived: Boolean = true,
    /** 超过该字节数显示「MB+」；null 不显示 */
    val largeThresholdBytes: Long? = null,
    /** 右下角显示文件大小（按大小排序时更直观） */
    val size: Boolean = false,
    /** 合并显示同一张图的多个版本：左下角显示版本数，大小按全部版本合计 */
    val stack: Boolean = false,
)

/**
 * 图库式照片网格（图库 / 归档 / 标签 / QQ 来源 / 缩略图共用）。
 *
 * - 日期 / 大小分组标题横跨整行；提供 [sectionIds] 时点标题即勾选（或取消）这一组的全部图片，选择态下标题带勾选圈；
 * - 单击：非选择态打开，选择态切换勾选；
 * - 长按进入选择，按住拖动按 Google 相册的方式连续勾选：起点到手指所在位置之间的全部图片随手指增减，
 *   往回拖会恢复成拖动前的状态；从已选中的图开始拖则一路取消；手指停在上下边缘时自动滚动；
 * - 双指捏合调整列数；选中图块缩小并变圆角，勾选标记弹性出现；
 * - 提供 [directory] 时右侧有快速滚动条。
 */
@Composable
fun PhotoGrid(
    items: LazyPagingItems<GalleryTimelineItem>,
    columns: Int,
    selectedIds: Set<Long>,
    onOpen: (ImageEntity) -> Unit,
    onSelectedIdsChange: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onColumnsChange: ((Int) -> Unit)? = null,
    minColumns: Int = 2,
    maxColumns: Int = 8,
    badges: PhotoBadges = PhotoBadges(),
    sectionIds: (suspend (TimelineSection) -> Collection<Long>)? = null,
    directory: ScrollDirectory? = null,
    fastScroll: Boolean = false,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    /** 格子显示什么：默认是图片本身的小版本（见 ImageEntityModel.tile），回收站等页面换成自己的文件 */
    tileModel: (GalleryTimelineItem.Photo) -> Any = { ImageEntityModel(it.image, tile = true) },
    leadingContent: (LazyGridScope.() -> Unit)? = null,
) {
    val selectionMode = selectedIds.isNotEmpty()
    // 命中矩形只被手势闭包读取、不参与组合：普通 HashMap 免去滚动期逐帧快照写入；
    // 图块离屏销毁时移除（见 DisposableEffect），规模约等于可见项数
    val photoBounds = remember { HashMap<Long, Rect>() }
    var containerBounds by remember { mutableStateOf(Rect.Zero) }
    // 手势协程只在 key 变化时重启，经 rememberUpdatedState 读取最新的选择集与回调
    val currentSelected by rememberUpdatedState(selectedIds)
    val currentOnChange by rememberUpdatedState(onSelectedIdsChange)
    val currentSectionIds by rememberUpdatedState(sectionIds)
    // 顶栏盖在网格上时（图库）上方留白就是顶栏：拖到它下沿附近才开始往上自动滚
    val topInset by rememberUpdatedState(contentPadding.calculateTopPadding())
    val haptic = LocalHapticFeedback.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    // 分组标题对应的全部图片：选择态下按需查询并缓存（标题文案带数量，数量一变就重新取）；退出选择即清空
    val sectionMembers = remember { mutableStateMapOf<String, Set<Long>>() }
    LaunchedEffect(selectionMode) { if (!selectionMode) sectionMembers.clear() }
    // 拖快速滚动条快速翻动时先不加载图片（一闪而过的格子不读文件），停下再加载
    var scrubbing by remember { mutableStateOf(false) }

    fun sectionKey(header: GalleryTimelineItem.Header) = header.key + "|" + header.label

    fun toggleSection(header: GalleryTimelineItem.Header) {
        val query = currentSectionIds ?: return
        scope.launch {
            val key = sectionKey(header)
            val members = sectionMembers[key] ?: query(header.section).toSet().also { sectionMembers[key] = it }
            if (members.isEmpty()) return@launch
            val current = currentSelected
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnChange(if (current.containsAll(members)) current - members else current + members)
        }
    }

    /** 网格位置 → 分页位置：分页项之前可能还有调用方的前置项（筛选条、说明卡），只有寥寥几个，往前找到同一张图 */
    fun pagingIndexOf(gridIndex: Int, id: Long): Int? =
        (minOf(gridIndex, items.itemCount - 1) downTo maxOf(0, gridIndex - MAX_LEADING_ITEMS)).firstOrNull { index ->
            (items.peek(index) as? GalleryTimelineItem.Photo)?.image?.id == id
        }

    /** 手指（网格内坐标）下的图片在分页数据里的位置；只看可见项（滑出屏幕的图块可能还留着旧位置） */
    fun photoIndexAt(position: Offset): Int? {
        val root = position + Offset(containerBounds.left, containerBounds.top)
        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            val id = info.key as? Long ?: return@firstOrNull false
            photoBounds[id]?.contains(root) == true
        } ?: return null
        return pagingIndexOf(info.index, info.key as Long)
    }

    /** 网格里分页项之前的前置项个数：用一张可见图片的网格位置与分页位置之差 */
    fun leadingCount(): Int {
        gridState.layoutInfo.visibleItemsInfo.forEach { info ->
            val id = info.key as? Long ?: return@forEach
            pagingIndexOf(info.index, id)?.let { return info.index - it }
        }
        val append = if (items.loadState.append is LoadState.Loading) 1 else 0
        return (gridState.layoutInfo.totalItemsCount - items.itemCount - append).coerceAtLeast(0)
    }

    /** 分页位置 [from]..[to]（含两端，顺序不限）之间全部已加载图片的 id */
    fun idsBetween(from: Int, to: Int): Set<Long> {
        val ids = HashSet<Long>()
        for (index in minOf(from, to)..maxOf(from, to)) {
            (items.peek(index) as? GalleryTimelineItem.Photo)?.let { ids += it.image.id }
        }
        return ids
    }

    Box(modifier.fillMaxSize()) {
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
            modifier = Modifier
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
                // 长按选择 + 拖动范围勾选：在 Initial 阶段先于图块与网格滚动拿到事件。长按成立前不消费任何事件
                // （单击交给图块、滑动交给网格滚动）；成立后独占后续事件直到抬起，图块不会再触发单击。
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
                            val startIndex = photoIndexAt(down.position) ?: return@awaitEachGesture
                            val startId = (items.peek(startIndex) as? GalleryTimelineItem.Photo)?.image?.id ?: return@awaitEachGesture
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // 以拖动开始前的选择为底：选中模式加上整段范围，取消模式减去整段范围；
                            // 范围随手指伸缩，退回去的图恢复原状
                            val base = currentSelected
                            val selecting = startId !in base
                            var endIndex = startIndex
                            fun applyRange() {
                                val range = idsBetween(startIndex, endIndex)
                                currentOnChange(if (selecting) base + range else base - range)
                            }
                            applyRange()
                            var pointer = down.position
                            fun follow() {
                                val index = photoIndexAt(pointer) ?: return
                                if (index == endIndex) return
                                endIndex = index
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                applyRange()
                            }
                            val autoScroll = launch {
                                while (isActive) {
                                    val top = topInset.toPx() + edge
                                    val step = when {
                                        pointer.y < top -> -maxStep * (top - pointer.y) / edge
                                        pointer.y > size.height - edge -> maxStep * (pointer.y - (size.height - edge)) / edge
                                        else -> 0f
                                    }
                                    if (step != 0f) {
                                        gridState.scrollBy(step)
                                        follow()
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
                                    follow()
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
                    is GalleryTimelineItem.Header -> {
                        val selectable = sectionIds != null
                        val key = sectionKey(item)
                        val members = sectionMembers[key]
                        if (selectable && selectionMode && members == null) {
                            LaunchedEffect(key) { sectionIds?.let { query -> sectionMembers[key] = query(item.section).toSet() } }
                        }
                        PhotoSectionHeader(
                            label = item.label,
                            selectionMode = selectable && selectionMode,
                            checked = !members.isNullOrEmpty() && selectedIds.containsAll(members),
                            onClick = if (selectable) { { toggleSection(item) } } else null,
                            modifier = Modifier.animateItem(),
                        )
                    }
                    is GalleryTimelineItem.Photo -> {
                        val image = item.image
                        val id = image.id
                        DisposableEffect(id) { onDispose { photoBounds.remove(id) } }
                        val selected = id in selectedIds
                        PhotoTile(
                            image = image,
                            model = tileModel(item),
                            selected = selected,
                            selectionMode = selectionMode,
                            badges = badges,
                            showImage = !scrubbing,
                            modifier = Modifier
                                .animateItem()
                                .onGloballyPositioned { photoBounds[id] = it.boundsInRoot() },
                            onClick = {
                                if (selectionMode) onSelectedIdsChange(if (selected) selectedIds - id else selectedIds + id) else onOpen(image)
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelectedIdsChange(selectedIds + id)
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

        if (fastScroll) {
            FastScrollbar(
                gridState = gridState,
                directory = directory,
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
                headerIndex = { key ->
                    val index = items.itemSnapshotList.indexOfFirst { (it as? GalleryTimelineItem.Header)?.key == key }
                    if (index < 0) null else index + leadingCount()
                },
                onScrubbingChange = { scrubbing = it },
            )
        }
    }
}

/** 分组标题：可点时点一下勾选（或取消）整组，选择态下右侧显示整组是否已全选 */
@Composable
fun PhotoSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    checked: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .clickable(onClickLabel = if (checked) "取消选择这一组" else "选择这一组", onClick = onClick)
                        .semantics { if (selectionMode) this.selected = checked }
                },
            )
            .padding(start = 14.dp, top = 12.dp, end = 10.dp, bottom = 4.dp)
            .heightIn(min = 36.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SelectionMark(visible = selectionMode, selected = checked)
    }
}

/** 单个图块：选中时缩小 + 圆角，勾选标记弹性缩放进出 */
@Composable
fun PhotoTile(
    image: ImageEntity,
    model: Any = ImageEntityModel(image, tile = true),
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    badges: PhotoBadges = PhotoBadges(),
    contentScale: ContentScale = ContentScale.Crop,
    showImage: Boolean = true,
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
        if (showImage) {
            // 格子读同一张图更小的版本（QQ 高清缩略图 / 预览图），见 ImageEntityModel.tile
            AsyncImage(
                model = ImageRequest.Builder(context).data(model).crossfade(true).build(),
                contentDescription = image.filename,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .sharedImage("image-${image.id}", sharedScope, animatedScope),
            )
        }
        val showInfoBadges = !selectionMode
        // 合并显示时这张图代表整个家族：大小按全部版本合计
        val stacked = badges.stack && image.variantGroup != null && image.variantCount >= 2
        val bytes = if (stacked) image.variantBytes else image.size
        if (showInfoBadges && badges.archived && image.isArchived) {
            TileIconBadge(Icons.Outlined.Archive, Modifier.align(Alignment.TopStart))
        }
        val threshold = badges.largeThresholdBytes
        if (showInfoBadges && threshold != null && bytes > threshold) {
            TileTextBadge("MB+", Modifier.align(Alignment.TopEnd))
        }
        if (showInfoBadges && stacked) {
            TileStackBadge(image.variantCount, Modifier.align(Alignment.BottomStart).padding(5.dp))
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

/** 网格里分页项之前调用方前置项的上限（筛选条、说明卡、空状态等） */
private const val MAX_LEADING_ITEMS = 8
