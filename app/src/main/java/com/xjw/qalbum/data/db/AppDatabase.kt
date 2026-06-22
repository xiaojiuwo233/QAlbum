package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.DedupExclusionDao
import com.xjw.qalbum.data.db.dao.DirSnapshotDao
import com.xjw.qalbum.data.db.dao.EmbeddingDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.MatchedMessageDao
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.dao.UserActionDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.DedupExclusionEntity
import com.xjw.qalbum.data.db.entity.DirSnapshotEntity
import com.xjw.qalbum.data.db.entity.ImageEmbeddingEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.ImageTagEntity
import com.xjw.qalbum.data.db.entity.MatchedMessageEntity
import com.xjw.qalbum.data.db.entity.OcrFtsEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.db.entity.TagEntity
import com.xjw.qalbum.data.db.entity.UserActionEntity

/**
 * QAlbum 主数据库（Phase 0 版本）
 *
 * - 版本管理：每个 Phase 引入新表/字段时递增 version，并在 Migrations 中实现 SQL 升级
 * - sqlite-vec 向量表（Phase 4）会通过 SQLite 扩展加载，不在 Room schema 中声明
 * - FTS5 OCR 索引（Phase 3）会在升级时通过 `CREATE VIRTUAL TABLE` 创建
 */
@Database(
    entities = [
        ImageEntity::class,
        OcrTextEntity::class,
        OcrFtsEntity::class,
        TagEntity::class,
        ImageTagEntity::class,
        CleanupBatchEntity::class,
        CleanupCandidateEntity::class,
        QuarantineEntity::class,
        MatchedMessageEntity::class,
        UserActionEntity::class,
        ImageEmbeddingEntity::class,
        DirSnapshotEntity::class,
        DedupExclusionEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageDao(): ImageDao
    abstract fun ocrDao(): OcrDao
    abstract fun tagDao(): TagDao
    abstract fun cleanupDao(): CleanupDao
    abstract fun matchedMessageDao(): MatchedMessageDao
    abstract fun userActionDao(): UserActionDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun dirSnapshotDao(): DirSnapshotDao
    abstract fun dedupExclusionDao(): DedupExclusionDao

    companion object {
        const val DB_NAME = "qalbum.db"

        /**
         * v4 → v5：为 images.size 建索引，支撑图库「大到小」排序避免 25w 图全表 filesort。
         * 必须用显式 Migration（而非 destructive 回退），否则会清库导致已扫描数据全部丢失、需重扫数小时。
         * 索引名沿用 Room 约定 `index_<table>_<col>`，与实体生成的 schema 完全一致。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_images_size` ON `images` (`size`)")
            }
        }

        /**
         * v5 → v6：扫描管线重构（目录剪枝增量）。
         * 1. 新增 dir_snapshots 目录快照表 —— 增量扫描只 stat 目录层即可跳过未变化目录。
         * 2. 一次性补齐历史 chatthumb 行的回收站状态（原来每次扫描都全表跑
         *    markThumbnailsAsTrash 兜底，重写全部缩略图行；现在收敛为插入/更新时
         *    设置 + 本次迁移修复存量，扫描路径不再做全表 UPDATE）。
         *    排除已是 thumbnail_trash 的行，正常情况下匹配 0 行，瞬时完成。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dir_snapshots` (
                        `dir_path` TEXT NOT NULL,
                        `mtime` INTEGER NOT NULL,
                        `scanned_at` INTEGER NOT NULL,
                        PRIMARY KEY(`dir_path`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE images
                    SET status = 'thumbnail_trash',
                        hash_done = 0,
                        ocr_done = 0,
                        ai_done = 0,
                        embedding_done = 0,
                        variant_group = NULL,
                        is_variant_primary = 0,
                        duplicate_group_id = NULL,
                        is_duplicate_best = 0
                    WHERE source_type = 'chatthumb'
                      AND status NOT IN ('deleted', 'quarantined', 'thumbnail_trash')
                    """.trimIndent()
                )
            }
        }

        /**
         * v6 → v7：为 images 新增 deleted_at（永久删除时间戳，可空），支撑首页「今日已清理」统计。
         * 仅加可空列＝SQLite 元数据级 ALTER，对 200w 行瞬时完成、不重写任何行；存量已删行 deleted_at 留空，
         * 不计入任何「今日」窗口（符合语义）。必须显式迁移而非 destructive 回退，否则清库需重扫数小时。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `images` ADD COLUMN `deleted_at` INTEGER")
            }
        }

        /**
         * v7 → v8：重复检测增强。
         * 1. images 新增 block_hash（256bit 分块指纹，BLOB 可空）—— 视觉去重精筛信号；存量行留空，
         *    由后续分析「随增量」回填（pendingHashIds 会纳入 block_hash IS NULL 的图）。
         *    仅加可空列＝元数据级 ALTER，对 200w 行瞬时完成、不重写任何行。
         * 2. 新增 dedup_exclusions 表 —— 记录用户手动拆分重复组形成的「禁止再聚」约束。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `images` ADD COLUMN `block_hash` BLOB")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dedup_exclusions` (
                        `cohort_id` INTEGER NOT NULL,
                        `image_id` INTEGER NOT NULL,
                        PRIMARY KEY(`cohort_id`, `image_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dedup_exclusions_image_id` ON `dedup_exclusions` (`image_id`)"
                )
            }
        }

        /**
         * v8 → v9：images 新增 feature_grid（32 字节特征网格：16 亮度 + 16 边缘密度，BLOB 可空）——
         * 视觉去重的判别特征（区分深/浅背景与版式）；存量行留空，随分析增量回填
         * （pendingHashIds 纳入 feature_grid IS NULL 的图）。仅加可空列＝元数据级 ALTER，瞬时完成。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `images` ADD COLUMN `feature_grid` BLOB")
            }
        }

        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                // 已注册的版本走 Migration 保数据；只有遇到未覆盖的版本跳变才回退重建兜底
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
