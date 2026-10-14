package com.xjw.qalbum.ui.compress

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import kotlin.math.roundToInt

private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 3f

/**
 * 压缩前后对比：两张图叠在一起，分隔条左边是压缩前、右边是压缩后，拖动分隔条（或点一下）比较。
 * [zoomable] 时双指缩放、双击放大，两张图同步移动；放大后才看得出压缩痕迹。
 *
 * [decodeSize] 是两张图统一的解码尺寸（像素，一般取压缩后的尺寸），两张按同一比例显示才能对齐；
 * [initialScale] / [focus]（0~1 的相对位置）用于一打开就放大到细节处（设置页的演示）。
 */
@Composable
fun CompareSlider(
    before: Any?,
    after: Any?,
    decodeSize: IntSize,
    modifier: Modifier = Modifier,
    beforeLabel: String = "压缩前",
    afterLabel: String = "压缩后",
    zoomable: Boolean = true,
    initialScale: Float = 1f,
    focus: Offset = Offset(0.5f, 0.5f),
) {
    val context = LocalContext.current
    var divider by remember { mutableFloatStateOf(0.5f) }
    var scale by remember(initialScale) { mutableFloatStateOf(initialScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(value: Offset, s: Float): Offset {
        val maxX = (s - 1f) * box.width / 2f
        val maxY = (s - 1f) * box.height / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    // 一打开就放大到 [focus]：把那一点移到中央
    LaunchedEffect(box, initialScale, focus) {
        if (box == IntSize.Zero) return@LaunchedEffect
        val center = Offset(box.width / 2f, box.height / 2f)
        val point = Offset(focus.x * box.width, focus.y * box.height)
        offset = clamp((center - point) * scale, scale)
    }

    val beforeRequest = remember(before, decodeSize) { request(context, before, decodeSize) }
    val afterRequest = remember(after, decodeSize) { request(context, after, decodeSize) }
    val layer = Modifier
        .fillMaxSize()
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(zoomable) {
                detectTapGestures(
                    onTap = { divider = (it.x / size.width).coerceIn(0f, 1f) },
                    onDoubleTap = if (zoomable) {
                        { tap ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                // 放大后点的位置留在手指下
                                val next = DOUBLE_TAP_SCALE
                                offset = clamp((tap - center) - (tap - center - offset) * (next / scale), next)
                                scale = next
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            .then(
                if (zoomable) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            val anchored = (centroid - center) - (centroid - center - offset) * (next / scale)
                            scale = next
                            offset = clamp(anchored + pan, next)
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        AsyncImage(model = afterRequest, contentDescription = afterLabel, contentScale = ContentScale.Fit, modifier = layer)
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(right = size.width * divider) { this@drawWithContent.drawContent() } },
        ) {
            AsyncImage(model = beforeRequest, contentDescription = beforeLabel, contentScale = ContentScale.Fit, modifier = layer)
        }

        // 分隔线与把手：按住把手左右拖
        val dividerX = { (box.width * divider).roundToInt() }
        Box(
            Modifier
                .fillMaxHeight()
                .width(2.dp)
                .offset { IntOffset(dividerX() - 1.dp.roundToPx(), 0) }
                .background(Color.White.copy(alpha = 0.9f)),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(dividerX() - 24.dp.roundToPx(), 0) }
                .size(48.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, amount ->
                        change.consume()
                        if (box.width > 0) divider = (divider + amount / box.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.CompareArrows,
                    contentDescription = "拖动比较",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        CompareLabel(beforeLabel, Modifier.align(Alignment.TopStart).padding(8.dp))
        CompareLabel(afterLabel, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

@Composable
private fun CompareLabel(text: String, modifier: Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** 两张图按同一尺寸解码（精确缩放），叠在一起才能对齐 */
private fun request(context: android.content.Context, data: Any?, size: IntSize): ImageRequest =
    ImageRequest.Builder(context)
        .data(data)
        .size(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        .precision(Precision.EXACT)
        .build()
