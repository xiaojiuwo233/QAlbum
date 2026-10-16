package com.xjw.qalbum.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 图片家族（同一张 QQ 图片的原图 / 预览图 / 缩略图）的派生字段维护。
 *
 * 家族键 `variant_group` 由路径算出（见 QqCachePaths.familyKey）。每次家族成员的状态 / 大小 / 类型变化，
 * 触发器重算这个家族（成员通常 2~4 个）：
 * - 代表图：在图库可见的成员（active 且非缩略图）里最大的那张；其余可见成员 `variant_hidden = 1`；
 * - 代表图记录家族现存版本数（缩略图也算在内，≥2 个版本才记）；
 * - `variant_bytes` 是合并显示时这张图的大小：代表图为全部版本合计，其余（含不属于任何家族的图）为自身大小，
 *   按大小排序与分组都用它。
 *
 * 回填写键时先撤掉家族索引与触发器（[suspendForBackfill]），写完恢复后由 [recomputeRange] 分段补齐。
 */
object ImageVariantSql {

    private fun rep(group: String) =
        "(SELECT f.id FROM images f WHERE f.variant_group = $group AND f.status = 'active' AND f.source_type <> 'chatthumb' ORDER BY f.size DESC, f.id LIMIT 1)"

    private fun count(group: String) =
        "(SELECT COUNT(*) FROM images f WHERE f.variant_group = $group AND f.status IN ('active', 'thumbnail_trash'))"

    private fun bytes(group: String) =
        "(SELECT COALESCE(SUM(f.size), 0) FROM images f WHERE f.variant_group = $group AND f.status IN ('active', 'thumbnail_trash'))"

    private fun hidden(group: String) = "(status = 'active' AND source_type <> 'chatthumb' AND id <> COALESCE(${rep(group)}, id))"
    private fun stackCount(group: String) = "(CASE WHEN id = ${rep(group)} AND ${count(group)} >= 2 THEN ${count(group)} ELSE 0 END)"
    private fun stackBytes(group: String) = "(CASE WHEN id = ${rep(group)} AND ${count(group)} >= 2 THEN ${bytes(group)} ELSE size END)"

    /** 重算一个家族（在触发器里 [group] 为 NEW / OLD 的列，子查询对整条语句只算一次） */
    private fun recomputeFamily(group: String) =
        "UPDATE images SET variant_hidden = ${hidden(group)}, variant_count = ${stackCount(group)}, " +
            "variant_bytes = ${stackBytes(group)} WHERE variant_group = $group;"

    /** 重算一个家族，家族键是参数 ?1（成员换了家族时用：触发器只管状态 / 大小 / 类型的变化） */
    val recomputeGroup: String = recomputeFamily("?1").removeSuffix(";")

    private val TRIGGERS = listOf("image_variants_insert", "image_variants_update", "image_variants_delete")

    /** 与实体声明的索引一致（Room 迁移校验会比对） */
    private const val FAMILY_INDEX = "CREATE INDEX IF NOT EXISTS `index_images_variant_group` ON `images` (`variant_group`)"

    /**
     * 回填写键前撤掉家族索引与触发器：几十万次随机插入索引是写键最慢的一步，而没有索引时触发器的
     * 家族重算会退化成全表扫描。期间的变化不做维护，由恢复之后的整体重算补上。
     */
    fun suspendForBackfill(db: SupportSQLiteDatabase) {
        TRIGGERS.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
        db.execSQL("DROP INDEX IF EXISTS index_images_variant_group")
    }

    /** 写键结束（含失败）后恢复，已存在时为空操作 */
    fun resumeAfterBackfill(db: SupportSQLiteDatabase) {
        db.execSQL(FAMILY_INDEX)
        createTriggers(db)
    }

    /** 触发器的语句有变化时（数据库升级）先删后建 */
    fun recreateTriggers(db: SupportSQLiteDatabase) {
        (TRIGGERS + PLAIN_TRIGGERS).forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
        createTriggers(db)
    }

    private val PLAIN_TRIGGERS = listOf("image_plain_bytes_insert", "image_plain_bytes_update")

    fun createTriggers(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS image_variants_insert
            AFTER INSERT ON images WHEN NEW.variant_group IS NOT NULL
            BEGIN ${recomputeFamily("NEW.variant_group")} END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS image_variants_update
            AFTER UPDATE OF status, size, source_type ON images
            WHEN NEW.variant_group IS NOT NULL
              AND (OLD.status IS NOT NEW.status OR OLD.size IS NOT NEW.size OR OLD.source_type IS NOT NEW.source_type)
            BEGIN ${recomputeFamily("NEW.variant_group")} END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS image_variants_delete
            AFTER DELETE ON images WHEN OLD.variant_group IS NOT NULL
            BEGIN ${recomputeFamily("OLD.variant_group")} END""")
        // 不属于任何家族的图：合并显示时的大小就是自身大小（入库时通常已写好，触发器兜底）
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS image_plain_bytes_insert
            AFTER INSERT ON images WHEN NEW.variant_group IS NULL AND NEW.variant_bytes IS NOT NEW.size
            BEGIN UPDATE images SET variant_bytes = NEW.size WHERE id = NEW.id; END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS image_plain_bytes_update
            AFTER UPDATE OF size ON images WHEN NEW.variant_group IS NULL AND NEW.variant_bytes IS NOT NEW.size
            BEGIN UPDATE images SET variant_bytes = NEW.size WHERE id = NEW.id; END""")
    }

    /**
     * 回填后按 id 段 (lo, hi] 重算各行所在家族；只写值有变化的行，可重复执行。
     * 此时触发器已在维护后续变化，段与段之间插进来的修改不会丢。
     */
    private val ROW_GROUP = "images.variant_group"
    val recomputeRange: String = """UPDATE images SET variant_hidden = ${hidden(ROW_GROUP)},
        variant_count = ${stackCount(ROW_GROUP)}, variant_bytes = ${stackBytes(ROW_GROUP)}
        WHERE variant_group IS NOT NULL AND id > ? AND id <= ?
          AND (variant_hidden IS NOT ${hidden(ROW_GROUP)} OR variant_count IS NOT ${stackCount(ROW_GROUP)}
               OR variant_bytes IS NOT ${stackBytes(ROW_GROUP)})"""

    /** 回填：id 段 (lo, hi] 内不属于任何家族的图，合并显示时的大小补成自身大小 */
    const val plainBytesRange: String =
        "UPDATE images SET variant_bytes = size WHERE variant_group IS NULL AND id > ? AND id <= ? AND variant_bytes IS NOT size"
}
