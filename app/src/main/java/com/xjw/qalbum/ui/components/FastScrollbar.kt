package com.xjw.qalbum.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 快速滚动条的目录：按显示顺序列出各分组（每天 / 每个大小档）的数量、拖动时气泡里的文字、
 * 轨道上的刻度（年份 / 月份 / 大小档，与上一组相同时为 null），以及该组在列表里的标题键（落点对齐用）。
 */
@Immutable
data class ScrollDirectory(val entries: List<Entry>) {
    data class Entry(val count: Int, val label: String, val mark: String?, val key: String? = null)

    val total: Int = entries.sumOf { it.count }

    /** 每组在全部内容里的起点比例 */
    private val starts: FloatArray = run {
        var before = 0
        FloatArray(entries.size) { index ->
            (if (total == 0) 0f else before.toFloat() / total).also { before += entries[index].count }
        }
    }

    /** 比例位置所在的分组下标（没有分组时为 -1） */
    fun entryAt(fraction: Float): Int {
        if (entries.isEmpty()) return -1
        var low = 0
        var high = entries.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= fraction) low = mid else high = mid - 1
        }
        return low
    }

    /** 分组的起点比例 */
    fun startOf(entry: Int): Float = starts.getOrElse(entry) { 0f }

    fun labelAt(fraction: Float): String? = entries.getOrNull(entryAt(fraction))?.label

    /** 轨道刻度：(起点比例, 文字) */
    val marks: List<Pair<Float, String>> = entries.indices.mapNotNull { index -> entries[index].mark?.let { starts[index] to it } }
}

/**
 * 拖动增益：手指移动越慢，位置变化越精细（慢拖可以逐天挪动），快速一划才按满比例跨越。
 * [speed] 为平滑后的手指速度（dp/ms）。
 */
internal fun scrubGain(speed: Float): Float {
    val t = ((speed - SLOW_SPEED) / (FAST_SPEED - SLOW_SPEED)).coerceIn(0f, 1f)
    return MIN_GAIN + (1f - MIN_GAIN) * t
}

/**
 * Google 相册式的快速滚动条：滚动时右侧出现把手，停下 1.5 秒后收起；按住把手上下拖动定位，
 * 旁边气泡显示所在的那一天（按大小排序时为大小档），轨道旁列出年份等刻度。
 *
 * - 慢拖精细、快划跨越（见 [scrubGain]），落点吸附到分组（某一天）的开头，换组时轻震；
 * - 跳转限频，拖得再快也按节奏加载；快速翻动期间 [onScrubbingChange] 通知调用方暂停加载图片，停下再加载；
 * - 松手后等目标分组的标题加载出来（[headerIndex]），对齐到标题。
 *
 * 位置按项目序号估算（标题也算一项）；[directory] 为 null 时只有把手、按比例定位。
 */
@Composable
fun FastScrollbar(
    gridState: LazyGridState,
    directory: ScrollDirectory?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    headerIndex: (String) -> Int? = { null },
    onScrubbingChange: (Boolean) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentDirectory by rememberUpdatedState(directory)
    val currentHeaderIndex by rememberUpdatedState(headerIndex)
    val currentOnScrubbing by rememberUpdatedState(onScrubbingChange)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    var trackHeight by remember { mutableFloatStateOf(0f) }
    val thumbHeight = with(density) { THUMB_HEIGHT.toPx() }

    // 内容够长（约三屏以上）才出现
    val worthIt by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            info.totalItemsCount > info.visibleItemsInfo.size * 3 && (gridState.canScrollForward || gridState.canScrollBackward)
        }
    }
    val scrolling = gridState.isScrollInProgress
    LaunchedEffect(scrolling, dragging, worthIt) {
        if (!worthIt) {
            shown = false
        } else if (scrolling || dragging) {
            shown = true
        } else {
            delay(HIDE_DELAY_MS)
            shown = false
        }
    }

    // 滚动位置 → 比例：首个可见项序号 + 首行已滚过的部分（换算成项数）
    val scrollFraction by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val visible = info.visibleItemsInfo
            val first = visible.firstOrNull() ?: return@derivedStateOf 0f
            val span = (info.totalItemsCount - visible.size).coerceAtLeast(1)
            val rowItems = visible.takeWhile { it.offset.y == first.offset.y }.size.coerceAtLeast(1)
            val rowHeight = (first.size.height + info.mainAxisItemSpacing).coerceAtLeast(1)
            val progress = gridState.firstVisibleItemIndex + gridState.firstVisibleItemScrollOffset.toFloat() / rowHeight * rowItems
            (progress / span).coerceIn(0f, 1f)
        }
    }
    fun fraction() = if (dragging) dragFraction else scrollFraction
    fun thumbOffset() = (fraction() * (trackHeight - thumbHeight).coerceAtLeast(0f)).roundToInt()

    /** 比例 → 列表位置：有目录时吸附到所在分组的开头 */
    fun targetIndex(value: Float): Int {
        val info = gridState.layoutInfo
        val snapped = currentDirectory?.let { it.startOf(it.entryAt(value)) } ?: value
        val last = (info.totalItemsCount - 1).coerceAtLeast(0)
        return (snapped * (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)).roundToInt().coerceIn(0, last)
    }

    // 限频跳转：拖动中最多每 JUMP_INTERVAL_MS 跳一次，总是跳到最新目标；跳过之后一小会儿算「快速翻动」
    val jumps = remember { JumpThrottle() }
    fun markScrubbing() {
        currentOnScrubbing(true)
        jumps.scrubJob?.cancel()
        jumps.scrubJob = scope.launch {
            delay(SETTLE_MS)
            currentOnScrubbing(false)
        }
    }
    fun requestJump(target: Int) {
        jumps.pending = target
        if (jumps.job?.isActive == true) return
        jumps.job = scope.launch {
            while (true) {
                val wait = JUMP_INTERVAL_MS - (System.currentTimeMillis() - jumps.lastAt)
                if (wait > 0) delay(wait)
                val next = jumps.pending ?: break
                jumps.pending = null
                jumps.lastAt = System.currentTimeMillis()
                if (next != gridState.firstVisibleItemIndex) {
                    markScrubbing()
                    gridState.scrollToItem(next)
                }
            }
        }
    }

    /** 松手：跳到最终位置，等该组标题加载出来后对齐到标题 */
    fun settle(value: Float) {
        val directoryNow = currentDirectory
        val key = directoryNow?.entries?.getOrNull(directoryNow.entryAt(value))?.key
        val target = targetIndex(value)
        jumps.pending = null
        jumps.job?.cancel()
        scope.launch {
            if (target != gridState.firstVisibleItemIndex) gridState.scrollToItem(target)
            jumps.scrubJob?.cancel()
            currentOnScrubbing(false)
            if (key != null) {
                val exact = withTimeoutOrNull(ALIGN_TIMEOUT_MS) {
                    snapshotFlow { currentHeaderIndex(key) }.filterNotNull().first()
                }
                if (exact != null && exact != gridState.firstVisibleItemIndex) gridState.scrollToItem(exact)
            }
        }
    }

    val marks = directory?.marks.orEmpty()
    val label by remember { derivedStateOf { if (dragging) currentDirectory?.labelAt(dragFraction) else null } }

    Box(
        modifier
            .fillMaxSize()
            .padding(contentPadding)
            .onSizeChanged { trackHeight = it.height.toFloat() },
    ) {
        // 拖动时：轨道旁的刻度，挨得太近的跳过
        AnimatedVisibility(visible = dragging && marks.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)
                val minGap = with(density) { MARK_GAP.toPx() }
                val half = with(density) { 10.dp.toPx() }
                var lastY = Float.NEGATIVE_INFINITY
                marks.forEach { (start, text) ->
                    val y = start * travel + thumbHeight / 2
                    if (y - lastY < minGap) return@forEach
                    lastY = y
                    // 叠在图片上也要看得清：浅色胶囊衬底
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(0, (y - half).roundToInt()) }
                            .padding(end = THUMB_WIDTH + 12.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = shown || dragging,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbOffset()) },
        ) {
            Box {
                // 拖动时：把手左侧的气泡（有刻度时让开刻度那一列）
                AnimatedVisibility(
                    visible = label != null,
                    enter = scaleIn(transformOrigin = TransformOrigin(1f, 0.5f)) + fadeIn(),
                    exit = scaleOut(transformOrigin = TransformOrigin(1f, 0.5f)) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = THUMB_WIDTH + if (marks.isEmpty()) 12.dp else 64.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shadowElevation = 3.dp,
                    ) {
                        Text(
                            label.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                val container by animateColorAsState(
                    if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.motionScheme.fastEffectsSpec(),
                    label = "fastScrollThumb",
                )
                val scale by animateFloatAsState(
                    if (dragging) 1.08f else 1f,
                    MaterialTheme.motionScheme.fastSpatialSpec(),
                    label = "fastScrollScale",
                )
                Surface(
                    shape = CircleShape,
                    color = container,
                    contentColor = if (dragging) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .size(THUMB_WIDTH, THUMB_HEIGHT)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .semantics { contentDescription = "快速滚动" }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                // 按下即进入拖动（不等滑过触摸阈值），气泡立刻出现
                                dragFraction = scrollFraction
                                dragging = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                var previousEntry = currentDirectory?.entryAt(dragFraction) ?: -1
                                var speed = 0f
                                var lastTime = down.uptimeMillis
                                try {
                                    verticalDrag(down.id) { change ->
                                        val moved = change.positionChange().y
                                        change.consume()
                                        val elapsed = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                                        lastTime = change.uptimeMillis
                                        // 手指速度（dp/ms）做平滑，避免单个事件的抖动忽快忽慢
                                        speed = speed * 0.6f + abs(moved) / density.density / elapsed * 0.4f
                                        val range = (trackHeight - thumbHeight).coerceAtLeast(1f)
                                        dragFraction = (dragFraction + moved * scrubGain(speed) / range).coerceIn(0f, 1f)
                                        val directoryNow = currentDirectory
                                        if (directoryNow == null) {
                                            requestJump(targetIndex(dragFraction))
                                            return@verticalDrag
                                        }
                                        val entry = directoryNow.entryAt(dragFraction)
                                        if (entry != previousEntry && entry >= 0) {
                                            // 越过年 / 月刻度总是轻震；慢拖逐组挪动时每组也震一下
                                            if (directoryNow.entries[entry].mark != null || speed < SLOW_SPEED * 3) {
                                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                            }
                                            previousEntry = entry
                                            requestJump(targetIndex(dragFraction))
                                        }
                                    }
                                } finally {
                                    dragging = false
                                    settle(dragFraction)
                                }
                            }
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.UnfoldMore, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

/** 跳转节流的状态：只被手势与跳转协程读写，不参与组合 */
private class JumpThrottle {
    var pending: Int? = null
    var job: Job? = null
    var scrubJob: Job? = null
    var lastAt = 0L
}

private val THUMB_WIDTH = 34.dp
private val THUMB_HEIGHT = 52.dp
private val MARK_GAP = 26.dp
private const val HIDE_DELAY_MS = 1_500L

/** 跳转最短间隔：每次跳转都会让分页在新位置重新加载，拖得快时按这个节奏来 */
private const val JUMP_INTERVAL_MS = 90L

/** 最后一次跳转后这么久没再跳，算是停下来了，可以加载图片 */
private const val SETTLE_MS = 180L

/** 松手后等目标标题加载出来的最长时间 */
private const val ALIGN_TIMEOUT_MS = 1_500L

/** 低于这个手指速度（dp/ms）按最精细的比例移动 */
private const val SLOW_SPEED = 0.08f

/** 高于这个手指速度按满比例移动 */
private const val FAST_SPEED = 1.6f

/** 最精细时的比例：约 0.05 时，20 万张图的库慢拖十几 dp 挪一天 */
private const val MIN_GAIN = 0.05f
