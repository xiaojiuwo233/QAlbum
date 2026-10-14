package com.xjw.qalbum.service.cleanup.rules

import java.time.Instant
import java.time.ZoneId

/** 规则按什么单位处理 */
enum class RuleScope {
    /** 整张图：同一张 QQ 图片的原图、预览图、缩略图一起；每张图只算一次（家族代表图） */
    PICTURE,

    /** 只处理命中的这个文件（例如只删原图、只删缩略图） */
    FILE,
}

enum class RuleAction { TRASH, COMPRESS }

/** 规则的一个可选参数（天数 / 年数 / 大小下限） */
data class RuleOption(val value: Int, val label: String, val subtitle: String)

/** SQL 片段：作用在别名为 `i` 的 images 上的 WHERE 条件与参数 */
data class SqlPart(val sql: String, val args: List<Any> = emptyList())

/**
 * 清理规则：一条 SQL 条件 + 处理范围 + 一个可调参数。只用扫描得到的文件信息（版本、大小、时间、文件头），
 * 不需要预先分析图片，只有 Shizuku 也能用。所有规则都排除归档的图。
 */
sealed class CleanupRule(val id: String) {
    abstract val title: String

    /** 预览页顶部的说明：处理什么、处理后会怎样 */
    abstract val description: String
    abstract val scope: RuleScope
    open val action: RuleAction = RuleAction.TRASH
    open val options: List<RuleOption> = emptyList()
    open val defaultOption: Int = 0

    /** 选中的图能不能直接压缩（命中项里有 JPEG / PNG 原图的规则） */
    open val compressible: Boolean = false

    /** 移入回收站时处理哪些文件（确认框里的说明） */
    open val trashNote: String
        get() = if (scope == RuleScope.PICTURE) "原图、预览图、缩略图一起移入回收站。" else "只处理选中的文件。"

    /** [option] 为 [options] 里的 value；[now] 固定为打开页面的时刻，翻页期间截止时间不漂移 */
    abstract fun where(option: Int, now: Long): SqlPart

    fun option(value: Int): RuleOption? = options.firstOrNull { it.value == value }

    /** 列表上的副标题，如「30 天前」 */
    open fun subtitle(option: Int): String = option(option)?.subtitle.orEmpty()

    /** QQ 缩略图（chatthumb，含高清缩略图）：只删缩略图本身 */
    data object Thumbnails : CleanupRule("thumbs") {
        override val title = "QQ 缩略图"
        override val description = "聊天里先显示的小图，刷过的群图都有一份。删后 QQ 改用本机的预览图或原图，" +
            "都没有时重新下载，过期的就看不到了。"
        override val scope = RuleScope.FILE
        override val trashNote = "只删缩略图，预览图和原图保留。"
        override val options = days(7, 30, 90, 180) + RuleOption(0, "全部", "全部")
        override val defaultOption = 30
        override fun where(option: Int, now: Long) = olderThan(
            "i.source_type = 'chatthumb' AND i.status = 'thumbnail_trash' AND i.is_protected = 0", option, now,
        )
    }

    /** 原图瘦身：同一张图有预览图时只删原图，图在 QQ 与 QAlbum 里都还能看 */
    data object SlimOriginals : CleanupRule("slim") {
        override val title = "原图瘦身"
        override val description = "只删 1 MB 以上的原图，保留预览图，图片照样能看，只是没那么清晰。" +
            "QQ 看大图时可能重新下载原图。舍不得删可以改为压缩。"
        override val scope = RuleScope.FILE
        override val compressible = true
        override val trashNote = "只删原图，预览图和缩略图保留。"
        override val options = days(30, 90, 180, 365)
        override val defaultOption = 90
        override fun where(option: Int, now: Long) = olderThan(
            """i.source_type = 'chatraw' AND i.status = 'active' AND i.is_protected = 0
                AND i.is_animated = 0 AND i.mime <> 'image/gif' AND i.size >= $SLIM_MIN_BYTES
                AND EXISTS (SELECT 1 FROM images p WHERE p.variant_group = i.variant_group
                    AND p.source_type = 'chatimg' AND p.status = 'active')""",
            option, now,
        )
    }

    /** 原图压缩：大的 JPEG / PNG 原图重新压缩，原地替换 */
    data object CompressOriginals : CleanupRule("compress") {
        override val title = "原图压缩"
        override val description = "重新压缩 JPEG、PNG 原图，一般省一半以上，仍比预览图清晰，QQ 照常显示。" +
            "先看对比再替换；PNG 会转成 JPEG，透明的跳过。原图可在回收站暂留，期间能还原。"
        override val scope = RuleScope.FILE
        override val action = RuleAction.COMPRESS
        override val compressible = true
        override val trashNote = "删除原图（不是压缩），预览图和缩略图保留。"
        override val options = listOf(
            RuleOption(300, "300 KB 以上", "300 KB 以上"),
            RuleOption(500, "500 KB 以上", "500 KB 以上"),
            RuleOption(1024, "1 MB 以上", "1 MB 以上"),
            RuleOption(1536, "1.5 MB 以上", "1.5 MB 以上"),
            RuleOption(3072, "3 MB 以上", "3 MB 以上"),
        )
        override val defaultOption = 1536
        override fun where(option: Int, now: Long) = SqlPart(
            """i.source_type = 'chatraw' AND i.status = 'active' AND i.is_protected = 0
                AND i.mime IN ('image/jpeg', 'image/png') AND i.is_animated = 0 AND i.optimized = 0 AND i.size >= ?""",
            listOf(option.coerceAtLeast(1) * 1024L),
        )
    }

    /** 动图和表情包：动图，或尺寸小、接近正方形的小图 */
    data object Stickers : CleanupRule("stickers") {
        override val title = "动图和表情包"
        override val description = "动图，以及边长 512 以内、近似正方形、小于 200 KB 的小图（多是表情包）。" +
            "只看尺寸，可能混进头像等小图，删前看一眼。"
        override val scope = RuleScope.PICTURE
        override fun where(option: Int, now: Long) = SqlPart(
            """$PICTURE AND (i.is_animated = 1 OR (i.width > 0 AND i.height > 0
                AND MAX(i.width, i.height) <= 512 AND MIN(i.width, i.height) * 5 >= MAX(i.width, i.height) * 4
                AND i.size <= 204800))""",
        )
    }

    /** 旧图片：整张图 N 年前 */
    data object OldPictures : CleanupRule("old") {
        override val title = "旧图片"
        override val description = "原图、预览图、缩略图一起删，私聊照片也算在内，想留的先归档。"
        override val scope = RuleScope.PICTURE
        override val compressible = true
        override val options = listOf(
            RuleOption(1, "1 年前", "一年前"),
            RuleOption(2, "2 年前", "两年前"),
            RuleOption(3, "3 年前", "三年前"),
        )
        override val defaultOption = 2
        override fun where(option: Int, now: Long): SqlPart {
            val years = option.coerceAtLeast(1).toLong()
            val zone = ZoneId.systemDefault()
            val cutoff = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                .minusYears(years).atStartOfDay(zone).toInstant().toEpochMilli()
            return SqlPart("$PICTURE AND i.mtime < ?", listOf(cutoff))
        }
    }

    /** 按时间清理选中的一段时间：整张图 [from, until) */
    data class TimeRange(val from: Long, val until: Long, val label: String) : CleanupRule("range:$from:$until:$label") {
        override val title get() = label
        override val description = "这段时间的整张图（原图、预览图、缩略图），想留的先归档。"
        override val scope = RuleScope.PICTURE
        override val compressible = true
        override fun where(option: Int, now: Long) = SqlPart("$PICTURE AND i.mtime >= ? AND i.mtime < ?", listOf(from, until))
    }

    companion object {
        /** 图库里看得到的每张图（家族代表图）、未归档 */
        const val PICTURE = "i.status = 'active' AND i.source_type <> 'chatthumb' AND i.variant_hidden = 0 AND i.is_protected = 0"
        private const val SLIM_MIN_BYTES = 1_048_576L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * 清理页列出的规则（按时间清理单独入口）。必须延迟创建：先用到某条规则（如 CompressOriginals）时
         * 父类在子对象构造途中初始化，此时直接建表会把这个还没赋值的对象记成 null。
         */
        val listed: List<CleanupRule> by lazy { listOf(Thumbnails, SlimOriginals, CompressOriginals, Stickers, OldPictures) }

        fun byId(id: String): CleanupRule? = listed.firstOrNull { it.id == id } ?: id.takeIf { it.startsWith("range:") }?.let {
            val parts = it.split(':')
            val from = parts.getOrNull(1)?.toLongOrNull() ?: return null
            val until = parts.getOrNull(2)?.toLongOrNull() ?: return null
            TimeRange(from, until, parts.drop(3).joinToString(":").ifBlank { "这段时间" })
        }

        private fun days(vararg values: Int) = values.map { RuleOption(it, "$it 天前", "$it 天前") }

        private fun olderThan(base: String, days: Int, now: Long): SqlPart =
            if (days <= 0) SqlPart(base) else SqlPart("$base AND i.mtime < ?", listOf(now - days * DAY_MS))
    }
}
