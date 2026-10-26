package com.xjw.qalbum.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.xjw.qalbum.ui.imageloading.ImageTileSource
import com.xjw.qalbum.ui.imageloading.TileMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 算分块要的几样：视口、图片节点（整张放下的大小）、缩放与偏移（同 ZoomableImage）、整图解码出来的像素大小 */
internal data class TileInput(val viewport: Size, val node: Size, val scale: Float, val offset: Offset, val base: Size)

/**
 * 放大后叠在整图上的清晰分块。整图按看图页大小解码，放大几倍后就糊了（长图整张放下时只有一百多像素宽）：
 * 缩放超过整图的清晰度时，按当前缩放选 2 的幂的采样率，只解码看得见的几块（四周多备一些），由近到远一块块解，
 * 画在整图上面；没解好的地方先露出整图。所有看图页共用一个解码线程，翻走、换图时释放。
 */
internal class ImageTileLayer(
    private val scope: CoroutineScope,
    private val openSource: suspend () -> ImageTileSource?,
) {
    private data class Key(val sample: Int, val col: Int, val row: Int)

    /** 一块：[rect] 是图上的范围，[src] 是它在解出来的位图里的范围（四周多解了一圈） */
    private class Tile(val bitmap: Bitmap, val rect: IntRect, val src: Rect)

    private var source: ImageTileSource? = null
    private var opening: Job? = null
    private var unusable = false
    /** 转正后的原图大小与用的方向（和整图对得上的那个） */
    private var image: IntSize? = null
    private var orientation = ExifInterface.ORIENTATION_NORMAL
    private val tiles = mutableStateMapOf<Key, Tile>()
    private val added = ArrayDeque<Key>()
    private val failed = HashSet<Key>()
    private var wanted: List<Key> = emptyList()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var last: TileInput? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    /** 缩放或位置变了：重新算要哪些块 */
    fun update(input: TileInput) {
        last = input
        if (unusable || input.base.width <= 0f || input.node.width <= 0f || input.viewport.width <= 0f || input.scale <= 0f) return
        // 整图的清晰度还够（每个整图像素放大不到 1.15 倍）就不用分块
        if (input.node.width * input.scale / input.base.width <= SHARP_ENOUGH) return setWanted(emptyList())
        val image = image ?: return ensureOpen(input)
        val k = max(input.node.width / image.width, input.node.height / image.height)
        val sample = TileMath.sampleSize(k * input.scale)
        // 整图本身就有这么清楚（原图本来就不大）
        if (sample >= image.width / input.base.width) return setWanted(emptyList())
        // 先解屏幕上看得见的（离屏幕中心近的先），再解四周多备的
        val shown = visible(input, image, k, margin = false)
        val core = TileMath.tilesCovering(shown, sample, image)
        val around = TileMath.tilesCovering(visible(input, image, k, margin = true), sample, image).filterNot { it in core }
        setWanted((core + around).map { (col, row) -> Key(sample, col, row) })
    }

    /** 不需要了（翻走、缩回整张）：丢掉解好的块，解码器留着 */
    fun clear() {
        wanted = emptyList()
        tiles.clear()
        added.clear()
        failed.clear()
    }

    fun release() {
        worker?.cancel()
        opening?.cancel()
        clear()
        source?.close()
        source = null
    }

    /** 在图片节点里画（节点大小 = 整张放下的大小，缩放平移由外面的 graphicsLayer 做）：粗的先画，细的盖在上面 */
    fun draw(scope: DrawScope) {
        // 先读 tiles（快照状态，[image] 不是）：头一次画时还没有块也订阅上，块解好了才会重画
        if (tiles.isEmpty()) return
        val image = image ?: return
        val node = scope.size
        val k = max(node.width / image.width, node.height / image.height)
        val left = (node.width - image.width * k) / 2f
        val top = (node.height - image.height * k) / 2f
        scope.drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            tiles.entries.sortedByDescending { it.key.sample }.forEach { (_, tile) ->
                dst.set(left + tile.rect.left * k, top + tile.rect.top * k, left + tile.rect.right * k, top + tile.rect.bottom * k)
                native.drawBitmap(tile.bitmap, tile.src, dst, paint)
            }
        }
    }

    /** 视口在图上（转正后的原图像素）的范围；[margin] 时四周多备一些（上下各半屏、左右各四分之一屏），滑动时不用等 */
    private fun visible(input: TileInput, image: IntSize, k: Float, margin: Boolean): IntRect {
        val left = (input.node.width - image.width * k) / 2f
        val top = (input.node.height - image.height * k) / 2f
        fun toImage(v: Offset): Offset {
            val node = Offset(input.node.width / 2f, input.node.height / 2f) +
                (v - Offset(input.viewport.width / 2f, input.viewport.height / 2f) - input.offset) / input.scale
            return Offset((node.x - left) / k, (node.y - top) / k)
        }
        val a = toImage(Offset.Zero)
        val b = toImage(Offset(input.viewport.width, input.viewport.height))
        val marginX = if (margin) (b.x - a.x) * 0.25f else 0f
        val marginY = if (margin) (b.y - a.y) * 0.5f else 0f
        return IntRect(
            floor(a.x - marginX).toInt().coerceAtLeast(0),
            floor(a.y - marginY).toInt().coerceAtLeast(0),
            (b.x + marginX).roundToInt().coerceAtMost(image.width),
            (b.y + marginY).roundToInt().coerceAtMost(image.height),
        )
    }

    private fun setWanted(keys: List<Key>) {
        wanted = keys
        if (keys.isEmpty()) {
            if (tiles.isNotEmpty()) clear()
            return
        }
        evict()
        if (worker?.isActive != true) worker = scope.launch { decodeLoop() }
        wake.trySend(Unit)
    }

    private fun ensureOpen(input: TileInput) {
        if (opening != null || source != null) return
        opening = scope.launch {
            val opened = openSource()
            opening = null
            if (opened == null) {
                unusable = true
                return@launch
            }
            // 方向用和整图对得上的那个：Coil 套用了 EXIF 就跟着转，没套用就不转；都对不上就不分块
            val baseAspect = input.base.width / input.base.height
            fun matches(size: IntSize) = abs(size.width.toFloat() / size.height - baseAspect) <= baseAspect * 0.02f
            val withExif = TileMath.orientedSize(opened.rawSize.width, opened.rawSize.height, opened.exifOrientation)
            when {
                matches(withExif) -> { orientation = opened.exifOrientation; image = withExif }
                matches(opened.rawSize) -> { orientation = ExifInterface.ORIENTATION_NORMAL; image = opened.rawSize }
                else -> {
                    opened.close()
                    unusable = true
                    return@launch
                }
            }
            source = opened
            last?.let(::update)
        }
    }

    private suspend fun decodeLoop() {
        while (true) {
            val src = source ?: return
            val image = image ?: return
            val next = wanted.firstOrNull { it !in tiles && it !in failed }
            if (next == null) {
                wake.receive()
                continue
            }
            val rect = TileMath.tileRect(next.col, next.row, next.sample, image)
            val area = TileMath.withOverlap(rect, next.sample, image)
            val bitmap = withContext(TileDecoding) { src.decode(area, next.sample, orientation) }
            if (bitmap == null) {
                failed += next
                continue
            }
            val sx = bitmap.width.toFloat() / area.width
            val sy = bitmap.height.toFloat() / area.height
            val inner = Rect(
                ((rect.left - area.left) * sx).roundToInt(),
                ((rect.top - area.top) * sy).roundToInt(),
                min(bitmap.width, ((rect.right - area.left) * sx).roundToInt()),
                min(bitmap.height, ((rect.bottom - area.top) * sy).roundToInt()),
            )
            tiles[next] = Tile(bitmap, rect, inner)
            added.addLast(next)
            evict()
        }
    }

    /** 超过上限时先丢不在当前视口附近的块（最早解的先丢） */
    private fun evict() {
        if (tiles.size <= MAX_TILES) return
        val keep = wanted.toHashSet()
        val iterator = added.iterator()
        while (tiles.size > MAX_TILES && iterator.hasNext()) {
            val key = iterator.next()
            if (key in keep && key in tiles) continue
            iterator.remove()
            tiles.remove(key)
        }
    }

    private companion object {
        const val SHARP_ENOUGH = 1.15f

        /** 最多留多少块（512×512 一块约 1 MB） */
        const val MAX_TILES = 40

        /** 所有看图页共用一个解码线程：同一时间只有当前这张在解，不和缩略图加载抢 */
        @OptIn(ExperimentalCoroutinesApi::class)
        val TileDecoding = Dispatchers.IO.limitedParallelism(1)
    }
}
