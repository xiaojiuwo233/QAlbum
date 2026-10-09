package com.xjw.qalbum.ui.common

import com.xjw.qalbum.data.db.entity.ImageEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

// 全应用共用的展示格式。格式器只在主线程（组合期）使用，SimpleDateFormat 的非线程安全不受影响。

private val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private val secondsFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
private val titleFormatter = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)
private val monthDayFormatter = SimpleDateFormat("M月d日", Locale.CHINA)
private val fullDayFormatter = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

/** 字节数：512 B / 12.3 KB / 1.5 MB / 2.3 GB */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exp)
    return String.format(Locale.ROOT, "%.1f %s", value, units[exp - 1])
}

/** 计数：10000 以上缩写为「1.2万」 */
fun formatCount(n: Int): String =
    if (n >= 10_000) String.format(Locale.ROOT, "%.1f万", n / 10_000f) else n.toString()

/** 带千分位的精确计数：12,345 */
fun formatCountExact(n: Int): String = String.format(Locale.ROOT, "%,d", n)

/** mtime 兼容秒与毫秒 */
fun normalizeEpochMillis(time: Long): Long = if (time in 1 until 1_000_000_000_000L) time * 1000 else time

fun formatDateTime(time: Long): String = dateTimeFormatter.format(Date(normalizeEpochMillis(time)))

fun formatDateTimeSeconds(time: Long): String = secondsFormatter.format(Date(normalizeEpochMillis(time)))

/** 详情标题：2026年10月6日 14:30 */
fun formatTitleDate(time: Long): String = titleFormatter.format(Date(normalizeEpochMillis(time)))

/** 今天 / 昨天 / 10月6日（今年）/ 2025年10月6日 */
fun formatRelativeDay(time: Long): String {
    if (time <= 0) return "未知"
    val ms = normalizeEpochMillis(time)
    val target = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun Calendar.sameDay(other: Calendar) =
        get(Calendar.YEAR) == other.get(Calendar.YEAR) && get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
    return when {
        target.sameDay(today) -> "今天"
        target.sameDay(yesterday) -> "昨天"
        target.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> monthDayFormatter.format(Date(ms))
        else -> fullDayFormatter.format(Date(ms))
    }
}

/** 来源目录的短标签：chatimg→img、chatraw→raw、chatthumb→thumb */
fun sourceLabel(sourceType: String): String = when (sourceType) {
    "chatimg" -> "img"
    "chatraw" -> "raw"
    "chatthumb" -> "thumb"
    else -> sourceType.ifBlank { "其他" }
}

/** 预览副标题：1080×1920 · 1.2 MB · 清晰度 85 */
fun imageMeta(image: ImageEntity): String {
    val dimensions = if (image.width > 0 && image.height > 0) "${image.width}×${image.height}" else "未知尺寸"
    val clarity = image.blurScore?.let { " · 清晰度 ${String.format(Locale.ROOT, "%.0f", it)}" } ?: ""
    return "$dimensions · ${formatBytes(image.size)}$clarity"
}

/** 删除结果文案：因收藏 / 共用 / 文件变化而跳过的要说出来 */
fun deleteResultText(action: String, done: Int, total: Int): String =
    if (done < total) "$action $done 张，${total - done} 张未处理" else "$action $done 张"
