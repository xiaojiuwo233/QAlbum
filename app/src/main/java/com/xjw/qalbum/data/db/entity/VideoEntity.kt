package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 视频页的视频：QQ 聊天里的视频（shortvideo）与收到的文件里的视频（QQfile_recv）。打开视频页时扫描入库，
 * 与图片表互不相干；时长与画面尺寸之后在后台逐个读出（[durationMs] < 0 表示还没读，0 表示读不出来）。
 */
@Entity(
    tableName = "videos",
    indices = [
        Index(value = ["path"], unique = true),
        Index("source", "mtime"),
        Index("size"),
    ],
)
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "path") val path: String,
    /** [SOURCE_CHAT] / [SOURCE_FILE] */
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "mtime") val mtime: Long,
    /** 本地日分桶键（year*1000 + dayOfYear），与图片表同口径 */
    @ColumnInfo(name = "date_bucket") val dateBucket: Long = computeDateBucket(mtime),
    @ColumnInfo(name = "duration_ms") val durationMs: Long = -1,
    /** 按显示方向（已按旋转换过宽高） */
    @ColumnInfo(name = "width") val width: Int = 0,
    @ColumnInfo(name = "height") val height: Int = 0,
    /** 最近一次扫描看到它的时间：扫描结束时没被看到的（文件已不在）删掉 */
    @ColumnInfo(name = "seen_at") val seenAt: Long,
) {
    val filename: String get() = path.substringAfterLast('/')

    companion object {
        const val SOURCE_CHAT = "chat"
        const val SOURCE_FILE = "file"
    }
}
