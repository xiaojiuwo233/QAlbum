package com.xjw.qalbum.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * 可缩放图片：双指捏合放大/缩小、双击切换 1x↔[DOUBLE_TAP_ZOOM]、放大后单指拖动平移。
 *
 * 与 HorizontalPager 协作：缩放=1 时**不消费**手势（横滑交给 Pager 翻页）；
 * 放大后才消费拖动用于平移，避免与翻页冲突。状态按 [model] 记忆——切换到另一张图自动复位为 1x。
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember(stateKey) { mutableFloatStateOf(1f) }
    var offset by remember(stateKey) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(stateKey) { mutableStateOf(Size.Zero) }
    var failed by remember(stateKey) { mutableStateOf(false) }
    var zoomAnimation by remember { mutableStateOf<Job?>(null) }
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnZoomChanged by rememberUpdatedState(onZoomChanged)
    fun viewportSize() = Size(viewport.width.toFloat(), viewport.height.toFloat())

    LaunchedEffect(stateKey, isActive, resetSignal) {
        zoomAnimation?.cancel()
        scale = 1f
        offset = Offset.Zero
    }
    LaunchedEffect(stateKey, isActive) {
        snapshotFlow { scale }.distinctUntilChanged().collect { latestOnZoomChanged(it) }
    }
    LaunchedEffect(viewport, imageSize) {
        offset = ImageZoomGeometry.clamp(offset, scale, viewportSize(), imageSize)
    }

    Box(modifier = modifier.clipToBounds().onSizeChanged { viewport = it }) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(model).crossfade(true).build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            onSuccess = { imageSize = it.painter.intrinsicSize; failed = false },
            onError = { failed = true },
            modifier = Modifier.fillMaxSize().pointerInput(stateKey) {
                detectTapGestures(
                    onTap = { latestOnTap?.invoke() },
                    onDoubleTap = { position ->
                        zoomAnimation?.cancel()
                        val start = scale
                        val target = if (start > 1f) 1f else DOUBLE_TAP_ZOOM
                        val startOffset = offset
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val targetOffset = ImageZoomGeometry.clamp(
                            ImageZoomGeometry.zoomOffset(offset, position, center, target / start),
                            target, viewportSize(), imageSize,
                        )
                        zoomAnimation = scope.launch {
                            animate(start, target, animationSpec = tween(180)) { value, _ ->
                                val fraction = (value - start) / (target - start)
                                scale = value
                                offset = ImageZoomGeometry.clamp(
                                    startOffset + (targetOffset - startOffset) * fraction,
                                    value, viewportSize(), imageSize,
                                )
                            }
                        }
                    },
                )
            }
            .pointerInput(stateKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressedCount = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (pressedCount >= 2 || (scale > 1f && pan != Offset.Zero)) {
                            zoomAnimation?.cancel()
                            val next = (scale * if (pressedCount >= 2) zoom else 1f).coerceIn(1f, MAX_ZOOM)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = ImageZoomGeometry.clamp(
                                ImageZoomGeometry.zoomOffset(offset, event.calculateCentroid(useCurrent = false), center, next / scale, pan),
                                next, viewportSize(), imageSize,
                            )
                            scale = next
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
        if (failed) Text("图片加载失败", color = Color.White, modifier = Modifier.align(Alignment.Center))
    }
}
