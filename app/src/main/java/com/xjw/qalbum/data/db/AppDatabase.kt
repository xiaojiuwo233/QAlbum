package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.CleanupRuleDao
import com.xjw.qalbum.data.db.dao.OriginalBackupDao
import com.xjw.qalbum.data.db.dao.QqSourceDao
import com.xjw.qalbum.data.db.dao.VideoDao
import com.xjw.qalbum.data.db.entity.QqAutoArchiveEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqOriginPathEntity
import com.xjw.qalbum.data.db.entity.QqReadMarkEntity
import com.xjw.qalbum.data.db.entity.QqPreviewCheckEntity
import com.xjw.qalbum.data.db.entity.ImageQqOriginEntity
import com.xjw.qalbum.data.db.entity.QqSourceFacetCacheEntity
import com.xjw.qalbum.data.db.entity.QqSourceCacheEntity
import com.xjw.qalbum.data.db.entity.QqImageContextEntity
import com.xjw.qalbum.data.db.entity.QqSourceMemberEntity
import com.xjw.qalbum.data.db.dao.DirSnapshotDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.DirSnapshotEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OriginalBackupEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.db.entity.VideoEntity

/**
 * QAlbum 主数据库
 *
 * - 版本管理：每次 schema 变更递增 version，并写显式 Migration —— 真机已有几十万行数据，
 *   任何版本跳变都必须保数据，未覆盖的跳变宁可 fail-fast 抛异常也不允许静默清库。
 * - v20 起只保存扫描得到的文件信息、归档、回收站、原图压缩记录与聊天来源；
 *   OCR / AI / 标签 / 查重 / 审核相关的表已在 v19 → v20 删除。
 */
@Database(
    entities = [
        ImageEntity::class,
        QuarantineEntity::class,
        OriginalBackupEntity::class,
        DirSnapshotEntity::class,
        QqSourceAccountEntity::class,
        QqOriginEntity::class,
        QqOriginPathEntity::class,
        ImageQqOriginEntity::class,
        QqSourceFacetCacheEntity::class,
        QqSourceCacheEntity::class,
        QqImageContextEntity::class,
        QqSourceMemberEntity::class,
        QqAutoArchiveEntity::class,
        QqReadMarkEntity::class,
        QqPreviewCheckEntity::class,
        VideoEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageDao(): ImageDao
    abstract fun cleanupDao(): CleanupDao
    abstract fun originalBackupDao(): OriginalBackupDao
    abstract fun cleanupRuleDao(): CleanupRuleDao
    abstract fun dirSnapshotDao(): DirSnapshotDao
    abstract fun qqSourceDao(): QqSourceDao
    abstract fun videoDao(): VideoDao

    companion object {

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quarantine ADD COLUMN group_id TEXT")
                db.execSQL("ALTER TABLE quarantine ADD COLUMN file_count INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE quarantine ADD COLUMN source_stamp TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quarantine_group_id ON quarantine(group_id)")
            }
        }
        /**
         * v16 → v17：来源统计改为增量维护。
         *
         * 旧版任何关联图片的状态变化都会把分类缓存标脏、整体重建（大图库上一次重建要几十秒，
         * 期间写锁被占住，删图要排队等它）。新版增加去重关联表 qq_image_contexts 与成员名片表，
         * 分类计数分「图片 / 缩略图」两类，由 images 上的触发器随状态变化增量更新。
         * 分类与汇总都是可重建的缓存：这里只重建表结构并标脏，首次进入来源页时在后台分段重建。
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                QqSourceStatsSql.legacyTriggers.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
                db.execSQL("DROP TABLE IF EXISTS qq_source_facets")
                db.execSQL("DROP TABLE IF EXISTS qq_source_cache")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `qq_source_facets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `kind` INTEGER NOT NULL, `key` TEXT NOT NULL, `name` TEXT NOT NULL, `search_text` TEXT NOT NULL,
                    `account_key` TEXT NOT NULL, `chat_type` INTEGER NOT NULL, `image_count` INTEGER NOT NULL,
                    `image_bytes` INTEGER NOT NULL, `thumb_count` INTEGER NOT NULL, `thumb_bytes` INTEGER NOT NULL,
                    `latest_time` INTEGER NOT NULL,
                    FOREIGN KEY(`account_key`) REFERENCES `qq_source_accounts`(`account_key`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_qq_source_facets_kind_key` ON `qq_source_facets` (`kind`, `key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_qq_source_facets_account_key` ON `qq_source_facets` (`account_key`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `qq_image_contexts` (`image_id` INTEGER NOT NULL,
                    `conversation_id` INTEGER NOT NULL, `sender_id` INTEGER NOT NULL,
                    PRIMARY KEY(`image_id`, `conversation_id`, `sender_id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_qq_image_contexts_conversation_id_sender_id_image_id` ON `qq_image_contexts` (`conversation_id`, `sender_id`, `image_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_qq_image_contexts_sender_id_conversation_id_image_id` ON `qq_image_contexts` (`sender_id`, `conversation_id`, `image_id`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `qq_source_members` (`conversation_id` INTEGER NOT NULL,
                    `sender_id` INTEGER NOT NULL, `card` TEXT NOT NULL, `latest_time` INTEGER NOT NULL,
                    PRIMARY KEY(`conversation_id`, `sender_id`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `qq_source_cache` (`id` INTEGER NOT NULL, `dirty` INTEGER NOT NULL,
                    `image_count` INTEGER NOT NULL, `image_bytes` INTEGER NOT NULL, `thumb_count` INTEGER NOT NULL,
                    `thumb_bytes` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL(QqSourceStatsSql.ENSURE_CACHE_ROW)
                QqSourceStatsSql.createTriggers(db)
            }
        }

        /**
         * v17 → v18：同一张 QQ 图片的原图 / 预览图 / 缩略图归为一个家族（图库合并显示、整组删除、查重不再互相判重）。
         * 只加列（常量默认值，不重写全表）、调整两个覆盖索引并建触发器；家族键由启动后的后台回填写入。
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN variant_hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE images ADD COLUMN variant_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE images ADD COLUMN variant_bytes INTEGER NOT NULL DEFAULT 0")
                // 历史 cache_key 变体分组不再使用，由新的家族键回填覆盖
                db.execSQL("UPDATE images SET variant_group = NULL WHERE variant_group IS NOT NULL")
                db.execSQL("DROP INDEX IF EXISTS index_images_status_source_type_mtime")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_images_status_source_type_mtime_variant_hidden` ON `images` (`status`, `source_type`, `mtime`, `variant_hidden`)")
                db.execSQL("DROP INDEX IF EXISTS index_images_status_source_type_date_bucket_is_protected")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_images_status_source_type_date_bucket_is_protected_variant_hidden` ON `images` (`status`, `source_type`, `date_bucket`, `is_protected`, `variant_hidden`)")
                ImageVariantSql.createTriggers(db)
            }
        }

        /**
         * v18 → v19：`variant_bytes` 改为合并显示时的大小（代表图为整组合计，其余为自身大小），供按大小排序与分组；
         * 只建索引并更新触发器，已有行由启动后的后台回填补齐。
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_images_variant_bytes` ON `images` (`variant_bytes`)")
                ImageVariantSql.recreateTriggers(db)
            }
        }

        /**
         * v19 → v20：清理改为按规则成批处理，删除 OCR / AI / 标签 / 查重 / 审核。
         * 1. 「保留」（user_keep）转为归档（整张图），之后由 ArchiveManager.sync 按设置隐藏或显示；
         * 2. 删除 OCR、AI 描述、向量、标签、查重排除、审核批次等表与分析失效触发器；
         * 3. 重建 images：去掉哈希 / 画质 / 完成标记 / 评分 / 重复组 / 保留等列及其索引，新增 optimized。
         *    先删掉所有引用 images 的触发器、换表后按原 SQL 重建（避免改名时校验到不存在的表）；
         *    Room 迁移期间外键未开启，删旧表不会级联删掉来源关联；
         * 4. 新增 original_backups（原图压缩记录）。
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DatabaseUpgrade.report("把「保留」转为归档")
                db.execSQL(
                    """UPDATE images SET is_protected = 1
                    WHERE is_protected = 0 AND status IN ('active', 'thumbnail_trash', 'missing', 'archived')
                      AND (COALESCE(user_keep, 0) = 1 OR variant_group IN (
                          SELECT variant_group FROM images WHERE COALESCE(user_keep, 0) = 1 AND variant_group IS NOT NULL))""",
                )

                DatabaseUpgrade.report("删除旧的分析数据（OCR、AI、标签）")
                db.execSQL("DROP TRIGGER IF EXISTS invalidate_changed_image_analysis")
                LEGACY_TABLES_V20.forEach { db.execSQL("DROP TABLE IF EXISTS `$it`") }

                val triggers = db.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND sql LIKE '%images%'").use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
                }
                triggers.forEach { (name, _) -> db.execSQL("DROP TRIGGER IF EXISTS `$name`") }
                // AUTOINCREMENT 序号随旧表一起删掉：记下来，换表后补回，id 不会被重复使用
                val sequence = db.query("SELECT seq FROM sqlite_sequence WHERE name = 'images'").use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
                db.execSQL(IMAGES_V20.replace("CREATE TABLE IF NOT EXISTS `images`", "CREATE TABLE `images_new`"))
                copyImagesV20(db)
                db.execSQL("DROP TABLE images")
                db.execSQL("ALTER TABLE images_new RENAME TO images")
                db.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, $sequence) WHERE name = 'images'")
                db.execSQL("INSERT INTO sqlite_sequence(name, seq) SELECT 'images', $sequence WHERE $sequence > 0 AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'images')")
                IMAGE_INDEXES_V20.forEachIndexed { index, sql ->
                    DatabaseUpgrade.report("重建索引", index.toLong(), IMAGE_INDEXES_V20.size.toLong())
                    db.execSQL(sql)
                }
                DatabaseUpgrade.report("恢复触发器")
                triggers.forEach { (_, sql) -> db.execSQL(sql) }

                db.execSQL(ORIGINAL_BACKUPS_V20)
                ORIGINAL_BACKUP_INDEXES_V20.forEach(db::execSQL)
            }
        }

        /** 按 id 分段把旧表复制到新表，每段报告一次进度（几十万行要十几秒） */
        private fun copyImagesV20(db: SupportSQLiteDatabase) {
            val (total, maxId) = db.query("SELECT COUNT(*), COALESCE(MAX(id), 0) FROM images").use { c ->
                c.moveToFirst()
                c.getLong(0) to c.getLong(1)
            }
            DatabaseUpgrade.report("整理图片索引", 0, total)
            var copied = 0L
            var from = 0L
            while (from < maxId) {
                val until = from + COPY_CHUNK
                db.execSQL("INSERT INTO images_new ($IMAGE_COLUMNS_V20) SELECT $IMAGE_COLUMNS_V20 FROM images WHERE id > $from AND id <= $until")
                copied += db.query("SELECT changes()").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                DatabaseUpgrade.report("整理图片索引", copied.coerceAtMost(total), total)
                from = until
            }
        }

        private const val COPY_CHUNK = 20_000L

        /** v20 → v21：压缩记录增加压缩质量（之前的压缩固定为 85） */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE original_backups ADD COLUMN quality INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE original_backups SET quality = 85")
            }
        }

        /** v21 → v22：压缩记录增加压缩前的格式（PNG 压缩后是 JPEG，还原时改回来；以前的记录都是 JPEG） */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE original_backups ADD COLUMN original_mime TEXT")
            }
        }

        /**
         * v22 → v23：聊天来源记下每条消息最近一次被读到的时间、消息里的图片类型 / 子类型 / 是否原图，
         * 汇总行记下最近一次完整读取的时间（「聊天记录已删除」规则据此判断）；新增自动归档名单与读取水位（增量读取）。
         * 旧的来源下次读取时补齐这些值。
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE qq_origins ADD COLUMN seen_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE qq_origins ADD COLUMN pic_type INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE qq_origins ADD COLUMN pic_sub_type INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE qq_origins ADD COLUMN sent_original INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE qq_source_cache ADD COLUMN complete_read_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `qq_auto_archive` (`conversation_key` TEXT NOT NULL, `sender_key` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_key`, `sender_key`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `qq_read_marks` (`account_key` TEXT NOT NULL, `table_name` TEXT NOT NULL, " +
                        "`max_rowid` INTEGER NOT NULL, `full_read_at` INTEGER NOT NULL, PRIMARY KEY(`account_key`, `table_name`), " +
                        "FOREIGN KEY(`account_key`) REFERENCES `qq_source_accounts`(`account_key`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        /**
         * v23 → v24：记下发送图片缓存的核对结果（自己发图时 QQ 另存、之后不再读取的 chatimg 预览），
         * 「发送图片缓存」列表与图库合并显示据此判断；下次读取聊天来源时补上。
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `qq_preview_checks` (`path` TEXT NOT NULL, `size` INTEGER NOT NULL, " +
                        "`raw_path` TEXT, `checked_at` INTEGER NOT NULL, PRIMARY KEY(`path`))",
                )
            }
        }

        /** v24 → v25：视频页的视频表（打开视频页时扫描入库，与图片表互不相干） */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `videos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `size` INTEGER NOT NULL, `mtime` INTEGER NOT NULL, `date_bucket` INTEGER NOT NULL, " +
                        "`duration_ms` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `seen_at` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_videos_path` ON `videos` (`path`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_source_mtime` ON `videos` (`source`, `mtime`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_size` ON `videos` (`size`)")
            }
        }

        /** v25 → v26：压缩记录记下省下的空间何时腾出来（首页「今日已清理」算压缩省下的） */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE original_backups ADD COLUMN freed_at INTEGER")
            }
        }

        private val LEGACY_TABLES_V20 = listOf(
            "ocr_fts", "ocr_text", "image_tags", "tags", "ai_analysis", "image_embeddings",
            "dedup_exclusions", "cleanup_candidates", "cleanup_batches",
        )

        /** v20 的 images 列（不含新增的 optimized，由默认值补上） */
        private const val IMAGE_COLUMNS_V20 =
            "id, path, filename, parent_dir, source_type, cache_key, variant_group, variant_hidden, variant_count, " +
                "variant_bytes, size, mtime, date_bucket, first_seen, last_seen, mime, width, height, is_animated, " +
                "status, is_protected, deleted_at"

        // 与 Room 导出的 schemas/20.json 一致（迁移后 Room 会逐表校验列、索引）
        private const val IMAGES_V20 =
            "CREATE TABLE IF NOT EXISTS `images` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `filename` TEXT NOT NULL, `parent_dir` TEXT NOT NULL, `source_type` TEXT NOT NULL, `cache_key` TEXT NOT NULL, `variant_group` INTEGER, `variant_hidden` INTEGER NOT NULL DEFAULT 0, `variant_count` INTEGER NOT NULL DEFAULT 0, `variant_bytes` INTEGER NOT NULL DEFAULT 0, `size` INTEGER NOT NULL, `mtime` INTEGER NOT NULL, `date_bucket` INTEGER NOT NULL DEFAULT 0, `first_seen` INTEGER NOT NULL, `last_seen` INTEGER NOT NULL, `mime` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `is_animated` INTEGER NOT NULL, `status` TEXT NOT NULL, `is_protected` INTEGER NOT NULL, `optimized` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER)"
        private val IMAGE_INDEXES_V20 = listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_images_path` ON `images` (`path`)",
            "CREATE INDEX IF NOT EXISTS `index_images_variant_group` ON `images` (`variant_group`)",
            "CREATE INDEX IF NOT EXISTS `index_images_variant_bytes` ON `images` (`variant_bytes`)",
            "CREATE INDEX IF NOT EXISTS `index_images_status` ON `images` (`status`)",
            "CREATE INDEX IF NOT EXISTS `index_images_mtime` ON `images` (`mtime`)",
            "CREATE INDEX IF NOT EXISTS `index_images_size` ON `images` (`size`)",
            "CREATE INDEX IF NOT EXISTS `index_images_status_source_type_date_bucket_is_protected_variant_hidden` ON `images` (`status`, `source_type`, `date_bucket`, `is_protected`, `variant_hidden`)",
            "CREATE INDEX IF NOT EXISTS `index_images_status_source_type_mtime_variant_hidden` ON `images` (`status`, `source_type`, `mtime`, `variant_hidden`)",
        )
        private const val ORIGINAL_BACKUPS_V20 =
            "CREATE TABLE IF NOT EXISTS `original_backups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `image_id` INTEGER NOT NULL, `path` TEXT NOT NULL, `backup_path` TEXT, `original_size` INTEGER NOT NULL, `original_mtime` INTEGER NOT NULL, `original_width` INTEGER NOT NULL, `original_height` INTEGER NOT NULL, `compressed_size` INTEGER NOT NULL, `compressed_width` INTEGER NOT NULL, `compressed_height` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, `state` TEXT NOT NULL)"
        private val ORIGINAL_BACKUP_INDEXES_V20 = listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_original_backups_image_id` ON `original_backups` (`image_id`)",
            "CREATE INDEX IF NOT EXISTS `index_original_backups_state` ON `original_backups` (`state`)",
        )

        const val DB_NAME = "qalbum.db"

        /** 当前数据库版本（启动时据此判断要不要显示升级进度） */
        const val VERSION = 26

        /** v1 → v2：Phase 3 增加 Room FTS4 OCR 索引。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `ocr_fts` USING FTS4(`text` TEXT NOT NULL)")
            }
        }

        /** v2 → v3：Phase 4 增加图片语义向量表。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `image_embeddings` (
                        `image_id` INTEGER NOT NULL,
                        `model` TEXT NOT NULL,
                        `dim` INTEGER NOT NULL,
                        `vector` BLOB NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`image_id`),
                        FOREIGN KEY(`image_id`) REFERENCES `images`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 → v4：为 images.path 建索引（当年靠 destructive 回退带出的变更，此处补显式迁移）。
         * 仍处于 v3 的真机若无此迁移会命中回退整库重建：200w 行索引/OCR/「已清理」统计全部丢失，
         * quarantine 表被清后回收站文件成为无记录孤儿——等价于用户照片丢失。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_images_path` ON `images` (`path`)")
            }
        }

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

        /**
         * v9 → v10：本轮审查修复的全部 schema 变更，集中一次迁移承载。
         *
         * 1. images 新增 date_bucket（本地日分桶键，入库预计算）并全表回填 —— 分桶计数
         *    从「200w 行逐行 2 次 strftime 的全表聚合」改为对预计算整列走覆盖索引 GROUP BY。
         *    回填是一次性的整表 UPDATE（单条语句、集合式），200w 行约数十秒，仅此升级付一次。
         * 2. images.path 升级为唯一索引。deleted/quarantined 先迁入内部 path 命名空间；其余重复行
         *    按 active/用户状态/分析完整度选择 survivor，并合并 OCR、标签、向量、清理候选和排斥关系。
         * 3. 新增 (status, source_type, date_bucket, is_protected) 与 (status, source_type, mtime)
         *    覆盖索引：分桶计数与时间线 rank/窗口查询只扫索引条目，不做 200w 次行回表。
         * 4. 清空 ocr_fts（原为按中文逐字切分后重灌；OCR 已在 v20 删除，这里不再重建）。
         * 5. 删除零读写方的死表 user_actions / matched_messages（对应实体与 DAO 一并移除）。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. date_bucket 列 + 回填（口径与 ImageEntity.computeDateBucket 严格一致）
                db.execSQL("ALTER TABLE `images` ADD COLUMN `date_bucket` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE images
                    SET date_bucket = CAST(strftime('%Y', mtime / 1000, 'unixepoch', 'localtime') AS INTEGER) * 1000
                        + CAST(strftime('%j', mtime / 1000, 'unixepoch', 'localtime') AS INTEGER)
                    """.trimIndent()
                )

                // 2. 终态行不再占用真实文件 path；剩余重复行合并全部业务关联后再删除 loser。
                retireTerminalImagePaths(db)
                mergeDuplicateImages(db)
                db.execSQL("DROP INDEX IF EXISTS `index_images_path`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_images_path` ON `images` (`path`)")

                // 3. 聚合/定位覆盖索引（回填完成后建，避免回填期间维护索引）
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_images_status_source_type_date_bucket_is_protected` " +
                        "ON `images` (`status`, `source_type`, `date_bucket`, `is_protected`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_images_status_source_type_mtime` " +
                        "ON `images` (`status`, `source_type`, `mtime`)"
                )

                // 4. OCR 索引在 v20 整体删除，旧内容不再重建
                db.execSQL("DELETE FROM ocr_fts")

                // 5. 死表清理
                db.execSQL("DROP TABLE IF EXISTS `user_actions`")
                db.execSQL("DROP TABLE IF EXISTS `matched_messages`")
            }
        }

        /**
         * v10 → v11：回收站操作日志与真实路径代际。
         * - quarantine 增加 pending/ready/restoring 状态和 image_id 唯一约束；
         * - 存量 deleted/quarantined 行迁入内部命名空间，释放真实路径给后来重新落盘的文件。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `quarantine` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'ready'")
                // 历史异常若为同一图片留下多条记录，确定性保留最新记录；目标文件名本就按 imageId 唯一。
                db.execSQL(
                    """
                    DELETE FROM quarantine
                    WHERE EXISTS (
                        SELECT 1 FROM quarantine newer
                        WHERE newer.image_id = quarantine.image_id AND newer.id > quarantine.id
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_quarantine_image_id` ON `quarantine` (`image_id`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quarantine_state` ON `quarantine` (`state`)")
                retireTerminalImagePaths(db)
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ai_analysis` (
                        `image_id` INTEGER NOT NULL PRIMARY KEY,
                        `description` TEXT NOT NULL,
                        `info_type` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        FOREIGN KEY(`image_id`) REFERENCES `images`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                createAnalysisInvalidationTrigger(db)
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS qq_source_accounts (
                    account_key TEXT NOT NULL PRIMARY KEY, display_name TEXT NOT NULL,
                    imported_at INTEGER NOT NULL, picture_count INTEGER NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS qq_origins (
                    id TEXT NOT NULL PRIMARY KEY, account_key TEXT NOT NULL,
                    conversation_key TEXT NOT NULL, conversation_name TEXT NOT NULL,
                    chat_type INTEGER NOT NULL, sender_key TEXT NOT NULL, sender_name TEXT NOT NULL,
                    member_name TEXT NOT NULL, message_time INTEGER NOT NULL, md5 TEXT NOT NULL,
                    FOREIGN KEY(account_key) REFERENCES qq_source_accounts(account_key) ON DELETE CASCADE)""")
                listOf("account_key", "conversation_key", "sender_key").forEach { column ->
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_qq_origins_$column ON qq_origins($column)")
                }
                db.execSQL("""CREATE TABLE IF NOT EXISTS qq_origin_paths (
                    path TEXT NOT NULL, origin_id TEXT NOT NULL, PRIMARY KEY(path, origin_id),
                    FOREIGN KEY(origin_id) REFERENCES qq_origins(id) ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_qq_origin_paths_origin_id ON qq_origin_paths(origin_id)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS image_qq_origins (
                    image_id INTEGER NOT NULL, origin_id TEXT NOT NULL, PRIMARY KEY(image_id, origin_id),
                    FOREIGN KEY(image_id) REFERENCES images(id) ON DELETE CASCADE,
                    FOREIGN KEY(origin_id) REFERENCES qq_origins(id) ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_image_qq_origins_origin_id ON image_qq_origins(origin_id)")
                createQqSourceTriggers(db)
            }
        }

        internal fun createQqSourceTriggers(db: SupportSQLiteDatabase) {
            listOf("INSERT", "UPDATE OF path").forEachIndexed { index, operation ->
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS link_qq_image_origin_$index
                    AFTER $operation ON images BEGIN
                    INSERT OR IGNORE INTO image_qq_origins(image_id, origin_id)
                    SELECT NEW.id, origin_id FROM qq_origin_paths WHERE path = NEW.path;
                    END""")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS qq_source_facets (
                    kind INTEGER NOT NULL, `key` TEXT NOT NULL, name TEXT NOT NULL,
                    search_text TEXT NOT NULL, account_key TEXT NOT NULL, image_count INTEGER NOT NULL,
                    PRIMARY KEY(kind, `key`),
                    FOREIGN KEY(account_key) REFERENCES qq_source_accounts(account_key) ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_qq_source_facets_account_key ON qq_source_facets(account_key)")
                db.execSQL("CREATE TABLE IF NOT EXISTS qq_source_cache (id INTEGER NOT NULL PRIMARY KEY, dirty INTEGER NOT NULL, linked_count INTEGER NOT NULL)")
                createQqSourceCacheTriggers(db)
            }
        }

        internal fun createQqSourceCacheTriggers(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT OR IGNORE INTO qq_source_cache(id,dirty,linked_count) VALUES(1,1,0)")
            val dirty = "UPDATE qq_source_cache SET dirty=1 WHERE id=1 AND dirty=0;"
            listOf("INSERT", "DELETE").forEach { operation ->
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS dirty_qq_links_${operation.lowercase()}
                    AFTER $operation ON image_qq_origins BEGIN $dirty END""")
            }
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS dirty_qq_image_state
                AFTER UPDATE OF status,source_type ON images
                WHEN (OLD.status <> NEW.status OR OLD.source_type <> NEW.source_type)
                  AND EXISTS(SELECT 1 FROM image_qq_origins WHERE image_id=NEW.id)
                BEGIN $dirty END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS dirty_qq_origin_names
                AFTER UPDATE OF conversation_name,sender_name,member_name ON qq_origins
                WHEN OLD.conversation_name <> NEW.conversation_name OR OLD.sender_name <> NEW.sender_name OR OLD.member_name <> NEW.member_name
                BEGIN $dirty END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS dirty_qq_account_names
                AFTER UPDATE OF display_name ON qq_source_accounts WHEN OLD.display_name <> NEW.display_name
                BEGIN $dirty END""")
        }

        /**
         * v14 → v15：来源统计增加占用空间。分类缓存补字节总和、会话类型（私聊也进入分类）
         * 与最近消息时间；已关联图片的大小变化同样让缓存失效。只加列与触发器，来源索引原样保留，
         * 缓存标脏后由来源页按新口径重建。
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE qq_source_facets ADD COLUMN chat_type INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE qq_source_facets ADD COLUMN total_bytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE qq_source_facets ADD COLUMN latest_time INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE qq_source_cache ADD COLUMN linked_bytes INTEGER NOT NULL DEFAULT 0")
                createQqSourceSizeTrigger(db)
                db.execSQL("UPDATE qq_source_cache SET dirty=1")
            }
        }

        internal fun createQqSourceSizeTrigger(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS dirty_qq_image_size
                AFTER UPDATE OF size ON images
                WHEN OLD.size <> NEW.size AND EXISTS(SELECT 1 FROM image_qq_origins WHERE image_id=NEW.id)
                BEGIN UPDATE qq_source_cache SET dirty=1 WHERE id=1 AND dirty=0; END""")
        }

        private fun createAnalysisInvalidationTrigger(db: SupportSQLiteDatabase) {
            // 文件被 QQ 重写时，标记和派生内容一起失效，避免旧 OCR/标签/向量污染新图片。
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS invalidate_changed_image_analysis
                AFTER UPDATE OF size, mtime ON images
                WHEN OLD.size <> NEW.size OR OLD.mtime <> NEW.mtime
                BEGIN
                    DELETE FROM ocr_fts WHERE rowid = NEW.id;
                    DELETE FROM ocr_text WHERE image_id = NEW.id;
                    DELETE FROM image_tags WHERE image_id = NEW.id AND source = 'ai';
                    DELETE FROM ai_analysis WHERE image_id = NEW.id;
                    DELETE FROM image_embeddings WHERE image_id = NEW.id;
                END
            """.trimIndent())
        }

        private fun retireTerminalImagePaths(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE images
                SET path = 'qalbum-internal://' || status || '/' || id
                WHERE status IN ('deleted', 'quarantined')
                  AND path NOT LIKE 'qalbum-internal://%'
                """.trimIndent()
            )
        }

        /**
         * v9 之前 path 不是唯一键。这里先建立 oldId→survivorId 映射并迁移所有引用，再删除 loser；
         * 不能直接保留最小 id，否则会丢收藏/保留状态、OCR/标签/向量，并留下 quarantine 等悬空引用。
         */
        private fun mergeDuplicateImages(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TEMP TABLE `image_path_survivors` (
                    `path` TEXT PRIMARY KEY NOT NULL,
                    `survivor_id` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO image_path_survivors(path, survivor_id)
                SELECT grouped.path,
                       (SELECT candidate.id
                        FROM images candidate
                        WHERE candidate.path = grouped.path
                        ORDER BY
                            CASE candidate.status
                                WHEN 'active' THEN 0
                                WHEN 'missing' THEN 1
                                WHEN 'thumbnail_trash' THEN 2
                                ELSE 3
                            END,
                            candidate.is_protected DESC,
                            CASE candidate.user_keep WHEN 1 THEN 0 WHEN 0 THEN 1 ELSE 2 END,
                            (candidate.hash_done + candidate.ocr_done + candidate.ai_done + candidate.embedding_done) DESC,
                            candidate.last_seen DESC,
                            candidate.id ASC
                        LIMIT 1)
                FROM images grouped
                GROUP BY grouped.path
                HAVING COUNT(*) > 1
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TEMP TABLE `duplicate_image_map` (
                    `old_id` INTEGER PRIMARY KEY NOT NULL,
                    `survivor_id` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO duplicate_image_map(old_id, survivor_id)
                SELECT image.id, survivor.survivor_id
                FROM images image
                JOIN image_path_survivors survivor ON survivor.path = image.path
                WHERE image.id <> survivor.survivor_id
                """.trimIndent()
            )

            // 合并用户状态与生命周期字段，避免 survivor 的选择遗漏另一行上的显式保护。
            db.execSQL(
                """
                UPDATE images
                SET is_protected = (SELECT MAX(peer.is_protected) FROM images peer WHERE peer.path = images.path),
                    user_keep = CASE
                        WHEN EXISTS(SELECT 1 FROM images peer WHERE peer.path = images.path AND peer.user_keep = 1) THEN 1
                        WHEN EXISTS(SELECT 1 FROM images peer WHERE peer.path = images.path AND peer.user_keep = 0) THEN 0
                        ELSE NULL
                    END,
                    first_seen = (SELECT MIN(peer.first_seen) FROM images peer WHERE peer.path = images.path),
                    last_seen = (SELECT MAX(peer.last_seen) FROM images peer WHERE peer.path = images.path)
                WHERE id IN (SELECT survivor_id FROM image_path_survivors)
                """.trimIndent()
            )

            // OCR 与 embedding 各保留该路径更新时间最新的一份。
            db.execSQL(
                """
                INSERT OR REPLACE INTO ocr_text(
                    image_id, text, confidence, has_phone, has_amount, has_address,
                    has_id_number, has_order_no, updated_at
                )
                SELECT survivor.survivor_id, source.text, source.confidence, source.has_phone,
                       source.has_amount, source.has_address, source.has_id_number,
                       source.has_order_no, source.updated_at
                FROM image_path_survivors survivor
                JOIN ocr_text source ON source.image_id = (
                    SELECT candidate.image_id
                    FROM ocr_text candidate
                    JOIN images owner ON owner.id = candidate.image_id
                    WHERE owner.path = survivor.path
                    ORDER BY candidate.updated_at DESC, candidate.image_id ASC
                    LIMIT 1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO image_embeddings(image_id, model, dim, vector, updated_at)
                SELECT survivor.survivor_id, source.model, source.dim, source.vector, source.updated_at
                FROM image_path_survivors survivor
                JOIN image_embeddings source ON source.image_id = (
                    SELECT candidate.image_id
                    FROM image_embeddings candidate
                    JOIN images owner ON owner.id = candidate.image_id
                    WHERE owner.path = survivor.path
                    ORDER BY candidate.updated_at DESC, candidate.image_id ASC
                    LIMIT 1
                )
                """.trimIndent()
            )

            // 标签取并集；同一标签保留最高置信度。无 FK 的业务引用显式重映射。
            db.execSQL(
                """
                CREATE TEMP TABLE `merged_image_tags` AS
                SELECT survivor.survivor_id AS survivor_id,
                       tag.tag_id AS tag_id,
                       MAX(tag.confidence) AS confidence,
                       MIN(tag.source) AS source
                FROM image_path_survivors survivor
                JOIN images owner ON owner.path = survivor.path
                JOIN image_tags tag ON tag.image_id = owner.id
                GROUP BY survivor.survivor_id, tag.tag_id
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE merged_image_tags
                SET source = 'user'
                WHERE EXISTS (
                    SELECT 1
                    FROM image_path_survivors survivor
                    JOIN images owner ON owner.path = survivor.path
                    JOIN image_tags tag ON tag.image_id = owner.id
                    WHERE survivor.survivor_id = merged_image_tags.survivor_id
                      AND tag.tag_id = merged_image_tags.tag_id
                      AND tag.source = 'user'
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO image_tags(image_id, tag_id, confidence, source)
                SELECT survivor_id, tag_id, confidence, source FROM merged_image_tags
                """.trimIndent()
            )
            db.execSQL("DROP TABLE merged_image_tags")
            db.execSQL(
                """
                UPDATE cleanup_candidates
                SET image_id = (SELECT survivor_id FROM duplicate_image_map WHERE old_id = cleanup_candidates.image_id)
                WHERE image_id IN (SELECT old_id FROM duplicate_image_map)
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE quarantine
                SET image_id = (SELECT survivor_id FROM duplicate_image_map WHERE old_id = quarantine.image_id)
                WHERE image_id IN (SELECT old_id FROM duplicate_image_map)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO dedup_exclusions(cohort_id, image_id)
                SELECT exclusion.cohort_id, mapping.survivor_id
                FROM dedup_exclusions exclusion
                JOIN duplicate_image_map mapping ON mapping.old_id = exclusion.image_id
                """.trimIndent()
            )
            db.execSQL("DELETE FROM dedup_exclusions WHERE image_id IN (SELECT old_id FROM duplicate_image_map)")

            // OCR/标签/embedding 已复制到 survivor；删除 loser 时 FK CASCADE 只清理旧 id 的副本。
            db.execSQL("DELETE FROM images WHERE id IN (SELECT old_id FROM duplicate_image_map)")
            db.execSQL("DROP TABLE duplicate_image_map")
            db.execSQL("DROP TABLE image_path_survivors")
        }

        /** 新建数据库时补上 Room 不负责的触发器（升级路径由各 Migration 建立同一套） */
        val creationCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createQqSourceTriggers(db)
                db.execSQL(QqSourceStatsSql.ENSURE_CACHE_ROW)
                QqSourceStatsSql.createTriggers(db)
                ImageVariantSql.createTriggers(db)
            }
        }

        /** 全部迁移，按版本顺序 */
        fun allMigrations(): List<Migration> = listOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
            MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
            MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
            MIGRATION_24_25, MIGRATION_25_26,
        )

        /** 每个迁移开始时报告升级到哪一版（升级弹窗里显示），具体步骤由迁移自己细报 */
        private fun reporting(migration: Migration): Migration = object : Migration(migration.startVersion, migration.endVersion) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DatabaseUpgrade.report("升级数据库（$startVersion → $endVersion）")
                migration.migrate(db)
            }
        }

        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(*allMigrations().map(::reporting).toTypedArray())
                .addCallback(creationCallback)
                // 刻意不配置任何 destructive 回退：未覆盖的版本跳变（含降级）宁可 fail-fast
                // 抛异常，也不允许静默清掉 200w 行索引与「已清理」统计（quarantine 被清后
                // 回收站文件会成为无法恢复的孤儿）。新增版本必须同步补显式 Migration。
                .build()
        }
    }
}
