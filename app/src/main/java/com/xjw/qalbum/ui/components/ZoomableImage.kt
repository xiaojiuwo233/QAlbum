package com.xjw.qalbum.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.ui.imageloading.LocalImageTileSources
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** 长图在「宽度占满」的基础上还能再放大几倍 */
private const val MAX_ZOOM_OVER_BASE = 3f

/** 长图先整张看一下，再自动放大到顶上 */
private const val LONG_IMAGE_PREVIEW_MS = 300L

/** 手势：还没定（没过触摸阈值）、平移、交给外面（拖到边了横滑翻页）、双指缩放 */
private enum class Gesture { Undecided, Pan, Released, Transform }

/**
 * 可缩放图片：双指捏合放大/缩小、双击放大/还原、放大后单指拖动平移（松手带惯性）。
 *
 * 长图（比屏幕细长得多，见 [ImageZoomGeometry.isLong]）打开后先整张显示一下，再自动放大到宽度占满、从顶上开始看，
 * 上下拖着看；这个「宽度占满」就是它的正常大小：报给外面的缩放（[onZoomChanged]）以它为 1，返回键直接关掉看图页，
 * 双击在它的基础上放大。普通图片不自动放大。
 *
 * 与 HorizontalPager 协作：没放大时**不消费**单指手势（横滑交给 Pager 翻页）；放大后拖动用来平移，
 * 拖到左右边缘再横着拖就交给 Pager（长图宽度占满时一直在边缘，横滑照样翻页）。状态按 [stateKey] 记忆，换图回到整张。
 *
 * 图片节点按宽高比放在视口中间（手势仍然整个视口都能用）：[imageModifier]（如共享元素）的边界就是
 * 图片本身，而不是整屏；转场里边界变成正方形格子时内容按 Crop 裁成正方形，不会被拉成竖屏整屏。
 * 放大到整图看不清时，看得见的部分按原图分块解码叠在上面（[ImageTileLayer]），超长的图放大也是清楚的。
 */
@Composable
internal fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    stateKey: Any? = model,
    isActive: Boolean = true,
    resetSignal: Any? = null,
    onTap: (() -> Unit)? = null,
    onZoomChanged: (Float) -> Unit = {},
    /** 图片的宽 / 高（库里记的，加载前就知道）；不知道时按加载出来的图 */
    aspectRatio: Float? = null,
    /** 加在图片节点上、缩放之前的修饰（如共享元素） */
    imageModifier: Modifier = Modifier,
    /** 图片加载出来之前先显示的内存缓存（网格里已经解码好的小图） */
    placeholderKey: MemoryCache.Key? = null,
    /** 页面的打开转场已经结束（共享元素落定）：长图到这时才自动放大 */
    settled: Boolean = true,
    /** 页面正在离开（返回中）：先缩回整张，共享元素从整张图飞回格子；取消返回时恢复 */
    leaving: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember(stateKey) { mutableFloatStateOf(1f) }
    var offset by remember(stateKey) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var nodeSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(stateKey) { mutableStateOf(Size.Zero) }
    // 占位小图的尺寸：库里没有宽高时先按它定宽高比
    var placeholderSize by remember(stateKey) { mutableStateOf(Size.Zero) }
    var failed by remember(stateKey) { mutableStateOf(false) }
    // 整图是静态位图（动图不分块）
    var staticImage by remember(stateKey) { mutableStateOf(false) }
    // 长图自动放大过（或用户先动了手）就不再自动放大：每张图、每次翻到这一页只做一次
    var autoFitDone by remember(stateKey) { mutableStateOf(false) }
    // 离开前的缩放与位置：取消返回时恢复
    var beforeLeaving by remember(stateKey) { mutableStateOf<Pair<Float, Offset>?>(null) }
    var animation by remember { mutableStateOf<Job?>(null) }
    // 缩放动画的终点：动画期间分块按终点准备，落定时就是清楚的（不去解中途经过的那几档）
    var zoomTarget by remember(stateKey) { mutableStateOf<Pair<Float, Offset>?>(null) }
    var fling by remember { mutableStateOf<Job?>(null) }
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val latestAspectRatio by rememberUpdatedState(aspectRatio)
    val latestModel by rememberUpdatedState(model)

    fun viewportSize() = Size(viewport.width.toFloat(), viewport.height.toFloat())
    fun ratio(): Float? {
        val loaded = (imageSize.takeIf { it.width > 0f && it.height > 0f } ?: placeholderSize.takeIf { it.width > 0f && it.height > 0f })
            ?.let { it.width / it.height }
        val known = latestAspectRatio?.takeIf { it > 0f && it.isFinite() }
        return when {
            known == null -> loaded
            // 库里的宽高和实际解码出来的对不上（如方向没算对）时以实际的为准
            loaded != null && abs(loaded - known) > known * 0.02f -> loaded
            else -> known
        }
    }
    // 几何只看宽高比
    fun content(): Size = ratio()?.let { Size(it, 1f) } ?: imageSize
    fun isLong() = ImageZoomGeometry.isLong(viewportSize(), content())
    /** 正常大小：长图宽度占满，其余整张 */
    fun base() = if (isLong()) ImageZoomGeometry.fillWidthScale(viewportSize(), content()) else 1f
    fun maxScale() = max(MAX_ZOOM, base() * MAX_ZOOM_OVER_BASE)
    fun clamp(value: Offset, atScale: Float) = ImageZoomGeometry.clamp(value, atScale, viewportSize(), content())

    /** 缩放到 [target]，[toOffset] 给出那个缩放下的位置（缩放过程中按比例过渡） */
    fun animateZoom(target: Float, durationMs: Int = 220, toOffset: (Float) -> Offset) {
        animation?.cancel()
        fling?.cancel()
        val startScale = scale
        val startOffset = offset
        zoomTarget = target to clamp(toOffset(target), target)
        animation = scope.launch {
            try {
                animate(0f, 1f, animationSpec = tween(durationMs, easing = FastOutSlowInEasing)) { t, _ ->
                    val value = startScale + (target - startScale) * t
                    scale = value
                    offset = clamp(lerp(startOffset, toOffset(target), t), value)
                }
            } finally {
                zoomTarget = null
            }
        }
    }

    /** 长图：从整张放大到宽度占满，顶边一直贴着屏幕顶上 */
    fun fitWidthFromTop() {
        animation?.cancel()
        val start = scale
        val target = base()
        zoomTarget = target to ImageZoomGeometry.topAligned(target, viewportSize(), content())
        animation = scope.launch {
            try {
                animate(start, target, animationSpec = tween(380, easing = FastOutSlowInEasing)) { value, _ ->
                    scale = value
                    offset = ImageZoomGeometry.topAligned(value, viewportSize(), content())
                }
            } finally {
                zoomTarget = null
            }
        }
    }

    // 换图、翻走：回到整张；再翻回来时长图重新自动放大
    LaunchedEffect(stateKey, isActive) {
        animation?.cancel()
        fling?.cancel()
        scale = 1f
        offset = Offset.Zero
        autoFitDone = false
    }
    // 返回键（放大时）：缩回正常大小（长图回到宽度占满，位置不变；普通图回到整张）
    val lastReset = remember { mutableStateOf(resetSignal) }
    LaunchedEffect(resetSignal) {
        if (lastReset.value == resetSignal) return@LaunchedEffect
        lastReset.value = resetSignal
        val target = if (isLong() && autoFitDone) base() else 1f
        val startScale = scale
        val startOffset = offset
        animateZoom(target) { s -> if (s <= 1f) Offset.Zero else startOffset * (s / startScale) }
    }
    LaunchedEffect(stateKey, isActive) {
        // 相对正常大小的缩放；差一点点就算正好（宽高比换成解码出来的之后正常大小会有零点几的出入）
        snapshotFlow { (scale / base()).let { if (abs(it - 1f) < 0.01f) 1f else it } }
            .distinctUntilChanged()
            .collect { latestOnZoomChanged(it) }
    }
    LaunchedEffect(viewport, imageSize) {
        offset = clamp(offset, scale)
    }
    val displayed = imageSize != Size.Zero || placeholderSize != Size.Zero
    val long = isLong()
    LaunchedEffect(stateKey, isActive, settled, long, displayed, leaving) {
        if (!isActive || !settled || !long || !displayed || leaving || autoFitDone) return@LaunchedEffect
        delay(LONG_IMAGE_PREVIEW_MS)
        if (autoFitDone || scale != 1f) return@LaunchedEffect
        autoFitDone = true
        fitWidthFromTop()
    }
    LaunchedEffect(leaving) {
        if (leaving) {
            if (scale != 1f) {
                beforeLeaving = scale to offset
                animateZoom(1f, durationMs = 200) { Offset.Zero }
            }
        } else {
            beforeLeaving?.let { (s, o) ->
                beforeLeaving = null
                animateZoom(s) { o }
            }
        }
    }

    // 分块清晰图：只给当前这一页、静态图
    val tileSources = LocalImageTileSources.current
    val tiles = remember(stateKey, model, tileSources) {
        tileSources?.let { sources -> ImageTileLayer(scope) { sources.open(latestModel) } }
    }
    DisposableEffect(tiles) { onDispose { tiles?.release() } }
    LaunchedEffect(tiles, isActive, staticImage) {
        val layer = tiles ?: return@LaunchedEffect
        if (!isActive || !staticImage) {
            layer.clear()
            return@LaunchedEffect
        }
        snapshotFlow {
            val (atScale, atOffset) = zoomTarget ?: (scale to offset)
            TileInput(viewportSize(), nodeSize.toSize(), atScale, atOffset, imageSize)
        }.collect { layer.update(it) }
    }

    val ratio = ratio()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(stateKey) {
                detectTapGestures(
                    onTap = { latestOnTap?.invoke() },
                    onDoubleTap = { position ->
                        val base = base()
                        val start = scale
                        // 放大过 → 回到正常大小；长图整张时 → 宽度占满；正常大小 → 放大
                        val target = when {
                            start > base * 1.05f -> base
                            start < base * 0.95f -> base
                            else -> base * DOUBLE_TAP_ZOOM
                        }
                        autoFitDone = true
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val focused = ImageZoomGeometry.zoomOffset(offset, position, center, target / start)
                        animateZoom(target) { s -> clamp(focused, s) }
                    },
                )
            }
            .pointerInput(stateKey) {
                val slop = viewConfiguration.touchSlop
                val decay = splineBasedDecay<Offset>(this)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    val velocity = VelocityTracker()
                    var gesture = Gesture.Undecided
                    var drag = Offset.Zero
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) gesture = Gesture.Transform
                        val pan = event.calculatePan()
                        when (gesture) {
                            Gesture.Transform -> {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (!centroid.isSpecified) continue
                                animation?.cancel()
                                autoFitDone = true
                                val next = (scale * event.calculateZoom()).coerceIn(1f, maxScale())
                                val center = Offset(size.width / 2f, size.height / 2f)
                                offset = clamp(
                                    ImageZoomGeometry.zoomOffset(offset, centroid, center, next / scale, pan),
                                    next,
                                )
                                scale = next
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            // 放大后：过了触摸阈值再定是平移，还是拖到边了横滑交给翻页
                            Gesture.Undecided -> if (scale > 1.001f) {
                                drag += pan
                                if (drag.getDistance() > slop) {
                                    val horizontal = abs(drag.x) > abs(drag.y)
                                    gesture = if (horizontal && !ImageZoomGeometry.canPanX(offset, scale, viewportSize(), content(), drag.x)) {
                                        Gesture.Released
                                    } else {
                                        animation?.cancel()
                                        autoFitDone = true
                                        offset = clamp(offset + drag, scale)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        Gesture.Pan
                                    }
                                }
                            }
                            Gesture.Pan -> {
                                offset = clamp(offset + pan, scale)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            Gesture.Released -> Unit
                        }
                        event.changes.firstOrNull()?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                    } while (event.changes.any { it.pressed })
                    if (gesture == Gesture.Pan) {
                        val speed = velocity.calculateVelocity()
                        // 横着本来就拖不动时只按竖直方向滑，撞到边就停
                        val canX = ImageZoomGeometry.maxPan(scale, viewportSize(), content()).x >= 0.5f
                        val initial = Offset(if (canX) speed.x else 0f, speed.y)
                        if (initial.getDistance() > 300f) {
                            fling = scope.launch {
                                AnimationState(Offset.VectorConverter, offset, Offset.VectorConverter.convertToVector(initial))
                                    .animateDecay(decay) {
                                        val clamped = clamp(value, scale)
                                        offset = clamped
                                        if (clamped != value) cancelAnimation()
                                    }
                            }
                        }
                    }
                }
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(model).placeholderMemoryCacheKey(placeholderKey).crossfade(true).build(),
            contentDescription = contentDescription,
            // 节点已按宽高比放好，Crop 与 Fit 看起来一样；转场中边界变形时 Crop 填满、不留空
            contentScale = if (ratio != null) ContentScale.Crop else ContentScale.Fit,
            onLoading = { state -> state.painter?.intrinsicSize?.let { placeholderSize = it } },
            onSuccess = {
                imageSize = it.painter.intrinsicSize
                staticImage = it.result.image is BitmapImage
                failed = false
            },
            onError = { failed = true },
            modifier = (if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize())
                .then(imageModifier)
                .onSizeChanged { nodeSize = it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .drawWithContent {
                    drawContent()
                    tiles?.draw(this)
                },
        )
        if (failed) Text("图片加载失败", color = Color.White, modifier = Modifier.align(Alignment.Center))
    }
}
