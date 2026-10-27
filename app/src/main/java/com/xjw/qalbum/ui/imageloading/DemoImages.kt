package com.xjw.qalbum.ui.imageloading

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.unit.IntRect
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 演示模式的示意图（见 DemoMode）：按种子（图片 id）画，坐标都按比例算，同一张图在格子和看图页里画面一样，比例与原图相同。
 * 普通图画成渐变的山、光斑或波浪，长图画成聊天截图的样子。
 */
internal object DemoImages {
    /** 天空上下两端、远山、近山、太阳 */
    private class Palette(val top: Int, val bottom: Int, val far: Int, val near: Int, val sun: Int)

    private val PALETTES = listOf(
        Palette(0xFFFFC3A0.toInt(), 0xFFFFAFBD.toInt(), 0xFFC06C84.toInt(), 0xFF6C3A5C.toInt(), 0xFFFFF4E0.toInt()),
        Palette(0xFF89F7FE.toInt(), 0xFF66A6FF.toInt(), 0xFF3A6EA5.toInt(), 0xFF1F3F73.toInt(), 0xFFFFFFFF.toInt()),
        Palette(0xFFD4FC79.toInt(), 0xFF96E6A1.toInt(), 0xFF3F8F5B.toInt(), 0xFF1E5631.toInt(), 0xFFFFFDE7.toInt()),
        Palette(0xFFE0C3FC.toInt(), 0xFF8EC5FC.toInt(), 0xFF6A5ACD.toInt(), 0xFF3D2C8D.toInt(), 0xFFFFFFFF.toInt()),
        Palette(0xFFFFECD2.toInt(), 0xFFFCB69F.toInt(), 0xFFE07A5F.toInt(), 0xFF9C4A3A.toInt(), 0xFFFFFFFF.toInt()),
        Palette(0xFF2C3E50.toInt(), 0xFFFD746C.toInt(), 0xFF1B2838.toInt(), 0xFF0E1621.toInt(), 0xFFFFE0B2.toInt()),
        Palette(0xFFA8EDEA.toInt(), 0xFFFED6E3.toInt(), 0xFF4FB3A9.toInt(), 0xFF2A7F78.toInt(), 0xFFFFFFFF.toInt()),
        Palette(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF16303D.toInt(), 0xFF07141A.toInt(), 0xFFF5F5DC.toInt()),
        Palette(0xFFF6D365.toInt(), 0xFFFDA085.toInt(), 0xFFD9744A.toInt(), 0xFF8F3F25.toInt(), 0xFFFFFFFF.toInt()),
        Palette(0xFFE0EAFC.toInt(), 0xFFCFDEF3.toInt(), 0xFF7F9CC9.toInt(), 0xFF4A6491.toInt(), 0xFFFFF8E1.toInt()),
    )

    /** 高宽比到这个数按长图画 */
    private const val LONG_RATIO = 2.5f

    /** 长边上限 */
    private const val MAX_SIDE = 4096

    /** 长图至少按这个宽度画（看图时会放大到铺满宽度） */
    private const val LONG_MIN_WIDTH = 1080

    /** 请求不限大小时的长边 */
    private const val DEFAULT_SIDE = 1440

    /**
     * 画多大：[width] × [height] 是原图尺寸（只用比例，不知道时按方图），铺满（格子裁切）或放进（看图）请求的大小，
     * 长边不超过 [MAX_SIDE]。
     */
    fun pixelSize(width: Int, height: Int, size: Size, scale: Scale): Pair<Int, Int> {
        val aspect = if (width > 0 && height > 0) width.toFloat() / height else 1f
        val boxW = (size.width as? Dimension.Pixels)?.px?.toFloat()
        val boxH = (size.height as? Dimension.Pixels)?.px?.toFloat()
        var w = when {
            boxW == null && boxH == null -> if (aspect >= 1f) DEFAULT_SIDE.toFloat() else DEFAULT_SIDE * aspect
            boxW == null -> boxH!! * aspect
            boxH == null -> boxW
            scale == Scale.FILL -> max(boxW, boxH * aspect)
            else -> min(boxW, boxH * aspect)
        }
        if (1f / aspect >= LONG_RATIO) w = max(w, min(LONG_MIN_WIDTH.toFloat(), MAX_SIDE * aspect))
        var h = w / aspect
        val fit = min(1f, MAX_SIDE / max(w, h))
        w *= fit
        h *= fit
        return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
    }

    fun render(seed: Long, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), seed, width.toFloat(), height.toFloat())
        return bitmap
    }

    /** 按 [width] × [height] 画时其中的 [region]，每 [sample] 个像素取一个（看图放大后的分块） */
    fun renderRegion(seed: Long, width: Int, height: Int, region: IntRect, sample: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(
            ((region.width + sample - 1) / sample).coerceAtLeast(1),
            ((region.height + sample - 1) / sample).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(1f / sample, 1f / sample)
        canvas.translate(-region.left.toFloat(), -region.top.toFloat())
        draw(canvas, seed, width.toFloat(), height.toFloat())
        return bitmap
    }

    /** 整张图画在 [w] × [h] 上（画分块时画布已经平移缩放好，画面外的自动裁掉） */
    private fun draw(canvas: Canvas, seed: Long, w: Float, h: Float) {
        // 先打散：相邻的 id 直接当种子，头几个随机数差不多（配色都一样）
        val random = Random(mix(seed))
        val palette = PALETTES[random.nextInt(PALETTES.size)]
        when {
            h / w >= LONG_RATIO -> chat(canvas, w, h, random, palette)
            else -> when (random.nextInt(3)) {
                0 -> bokeh(canvas, w, h, random, palette)
                1 -> waves(canvas, w, h, random, palette)
                else -> hills(canvas, w, h, random, palette)
            }
        }
    }

    private fun sky(canvas: Canvas, w: Float, h: Float, p: Palette, paint: Paint) {
        paint.shader = LinearGradient(0f, 0f, w * 0.35f, h, p.top, p.bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** 太阳和三层山，远的浅、近的深 */
    private fun hills(canvas: Canvas, w: Float, h: Float, random: Random, p: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        sky(canvas, w, h, p, paint)
        val cx = w * (0.2f + random.nextFloat() * 0.6f)
        val cy = h * (0.18f + random.nextFloat() * 0.2f)
        val r = min(w, h) * (0.07f + random.nextFloat() * 0.06f)
        paint.shader = RadialGradient(cx, cy, r * 3f, withAlpha(p.sun, 0x80), withAlpha(p.sun, 0), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 3f, paint)
        paint.shader = null
        paint.color = p.sun
        canvas.drawCircle(cx, cy, r, paint)
        for (i in 0 until 3) {
            paint.color = blend(blend(p.far, p.top, 0.45f), p.near, i / 2f)
            ridge(canvas, w, h, base = h * (0.56f + 0.14f * i), amp = h * (0.07f - 0.015f * i), random, paint, slow = 1.2f, fast = 3f)
        }
    }

    /** 柔和的光斑 */
    private fun bokeh(canvas: Canvas, w: Float, h: Float, random: Random, p: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        sky(canvas, w, h, p, paint)
        val m = min(w, h)
        repeat(14 + random.nextInt(8)) {
            val cx = w * random.nextFloat()
            val cy = h * random.nextFloat()
            val r = m * (0.05f + random.nextFloat() * 0.18f)
            val color = if (random.nextBoolean()) Color.WHITE else p.sun
            val alpha = 40 + random.nextInt(90)
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha * 2 / 3), withAlpha(color, 0)),
                floatArrayOf(0f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, paint)
        }
        paint.shader = null
    }

    /** 几道海浪 */
    private fun waves(canvas: Canvas, w: Float, h: Float, random: Random, p: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        sky(canvas, w, h, p, paint)
        for (i in 0 until 4) {
            paint.color = withAlpha(blend(p.far, p.near, i / 3f), 150 + 30 * i)
            ridge(canvas, w, h, base = h * (0.5f + 0.12f * i), amp = h * 0.035f, random, paint, slow = 1.5f, fast = 4f)
        }
    }

    /** 从 [base] 起伏 [amp] 的轮廓线，下面填满；取样点之间用二次曲线连（放大看也是圆滑的） */
    private fun ridge(canvas: Canvas, w: Float, h: Float, base: Float, amp: Float, random: Random, paint: Paint, slow: Float, fast: Float) {
        val f1 = slow + random.nextFloat() * 1.6f
        val f2 = fast + random.nextFloat() * 3f
        val p1 = random.nextFloat() * 2 * PI.toFloat()
        val p2 = random.nextFloat() * 2 * PI.toFloat()
        fun x(s: Int) = w * s / STEPS
        fun y(s: Int): Float {
            val u = s.toFloat() / STEPS * 2 * PI.toFloat()
            return base - amp * (0.7f * sin(f1 * u + p1) + 0.3f * sin(f2 * u + p2))
        }
        val path = Path()
        path.moveTo(0f, h)
        path.lineTo(x(0), y(0))
        for (s in 1 until STEPS) path.quadTo(x(s), y(s), (x(s) + x(s + 1)) / 2, (y(s) + y(s + 1)) / 2)
        path.lineTo(x(STEPS), y(STEPS))
        path.lineTo(w, h)
        path.close()
        canvas.drawPath(path, paint)
    }

    private const val STEPS = 48

    /** 长图：一屏屏的聊天气泡，有字（灰条）也有图 */
    private fun chat(canvas: Canvas, w: Float, h: Float, random: Random, p: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(blend(p.top, Color.WHITE, 0.86f))
        val unit = w / 100f
        val avatar = 9 * unit
        val rect = RectF()
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        val inside = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        var y = 6 * unit
        while (y < h) {
            val mine = random.nextInt(3) == 0
            val picture = random.nextInt(5) == 0
            val lines = 1 + random.nextInt(3)
            val bubbleW = if (picture) 46 * unit else (30 + random.nextInt(38)) * unit
            val bubbleH = if (picture) bubbleW * (0.7f + random.nextFloat() * 0.6f) else (lines * 6.2f + 4.4f) * unit
            val lastLine = 0.35f + random.nextFloat() * 0.4f
            val avatarX = if (mine) w - 4 * unit - avatar else 4 * unit
            paint.color = if (mine) p.near else blend(p.far, Color.WHITE, 0.25f)
            canvas.drawCircle(avatarX + avatar / 2, y + avatar / 2, avatar / 2, paint)
            val left = if (mine) avatarX - 2.5f * unit - bubbleW else avatarX + avatar + 2.5f * unit
            rect.set(left, y, left + bubbleW, y + bubbleH)
            if (picture) {
                // 图片气泡：先画圆角的底（抗锯齿），图只留在底上面
                val scene = PALETTES[random.nextInt(PALETTES.size)]
                val outer = canvas.saveLayer(rect, null)
                canvas.drawRoundRect(rect, 3.5f * unit, 3.5f * unit, mask)
                canvas.saveLayer(rect, inside)
                canvas.translate(rect.left, rect.top)
                hills(canvas, bubbleW, bubbleH, random, scene)
                canvas.restoreToCount(outer)
            } else {
                paint.color = if (mine) blend(p.bottom, Color.WHITE, 0.35f) else Color.WHITE
                canvas.drawRoundRect(rect, 3.5f * unit, 3.5f * unit, paint)
                paint.color = 0x26000000
                for (line in 0 until lines) {
                    val full = bubbleW - 8 * unit
                    val lineW = if (line == lines - 1 && lines > 1) full * lastLine else full
                    val top = y + 3.6f * unit + line * 6.2f * unit
                    rect.set(left + 4 * unit, top, left + 4 * unit + lineW, top + 2.6f * unit)
                    canvas.drawRoundRect(rect, 1.3f * unit, 1.3f * unit, paint)
                }
            }
            y += max(bubbleH, avatar) + 5 * unit
        }
    }

    /** SplitMix64 */
    private fun mix(seed: Long): Long {
        var z = seed + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).roundToInt() and 0xFF
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
