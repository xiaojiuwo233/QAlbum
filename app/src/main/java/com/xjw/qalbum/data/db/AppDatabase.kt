package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.AiAnalysisDao
import com.xjw.qalbum.data.db.dao.QqSourceDao
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqOriginPathEntity
import com.xjw.qalbum.data.db.entity.ImageQqOriginEntity
import com.xjw.qalbum.data.db.entity.QqSourceFacetCacheEntity
import com.xjw.qalbum.data.db.entity.QqSourceCacheEntity
import com.xjw.qalbum.data.db.dao.DedupExclusionDao
import com.xjw.qalbum.data.db.dao.DirSnapshotDao
import com.xjw.qalbum.data.db.dao.EmbeddingDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.AiAnalysisEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.DedupExclusionEntity
import com.xjw.qalbum.data.db.entity.DirSnapshotEntity
import com.xjw.qalbum.data.db.entity.ImageEmbeddingEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.ImageTagEntity
import com.xjw.qalbum.data.db.entity.OcrFtsEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.db.entity.TagEntity

/**
 * QAlbum 主数据库
 *
 * - 版本管理：每次 schema 变更递增 version，并写显式 Migration —— 真机已有 200w 行数据，
 *   任何版本跳变都必须保数据，未覆盖的跳变宁可 fail-fast 抛异常也不允许静默清库。
 * - sqlite-vec 向量表（Phase 4）会通过 SQLite 扩展加载，不在 Room schema 中声明
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
        ImageEmbeddingEntity::class,
        DirSnapshotEntity::class,
        DedupExclusionEntity::class,
        AiAnalysisEntity::class,
        QqSourceAccountEntity::class,
        QqOriginEntity::class,
        QqOriginPathEntity::class,
        ImageQqOriginEntity::class,
        QqSourceFacetCacheEntity::class,
        QqSourceCacheEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageDao(): ImageDao
    abstract fun ocrDao(): OcrDao
    abstract fun tagDao(): TagDao
    abstract fun cleanupDao(): CleanupDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun dirSnapshotDao(): DirSnapshotDao
    abstract fun dedupExclusionDao(): DedupExclusionDao
    abstract fun aiAnalysisDao(): AiAnalysisDao
    abstract fun qqSourceDao(): QqSourceDao

    companion object {
        const val DB_NAME = "qalbum.db"

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
         * 4. 重建 ocr_fts 内容：simple 分词器无法切分中文（整行中文成单个巨型 token，前缀查询
         *    永远命中不了行中位置的词），从 ocr_text 原文经 FtsTokenize 逐字切分后重灌。
         *    游标流式逐行读写（不整表装载进内存），行数 = 已 OCR 图数。
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

                // 4. 重建 ocr_fts：游标流式重灌（空文本跳过，本就命中不了任何查询）
                db.execSQL("DELETE FROM ocr_fts")
                val insert = db.compileStatement("INSERT INTO ocr_fts(rowid, text) VALUES (?, ?)")
                db.query("SELECT image_id, text FROM ocr_text").use { cursor ->
                    while (cursor.moveToNext()) {
                        val text = cursor.getString(1)
                        if (text.isNullOrBlank()) continue
                        insert.bindLong(1, cursor.getLong(0))
                        insert.bindString(2, FtsTokenize.tokenizeCjkForIndex(text))
                        insert.executeInsert()
                        insert.clearBindings()
                    }
                }
                db.execSQL("INSERT INTO ocr_fts(ocr_fts) VALUES('optimize')")

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

        private fun createQqSourceTriggers(db: SupportSQLiteDatabase) {
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

        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createAnalysisInvalidationTrigger(db)
                        createQqSourceTriggers(db)
                        createQqSourceCacheTriggers(db)
                        createQqSourceSizeTrigger(db)
                    }
                })
                // 刻意不配置任何 destructive 回退：未覆盖的版本跳变（含降级）宁可 fail-fast
                // 抛异常，也不允许静默清掉 200w 行索引与「已清理」统计（quarantine 被清后
                // 回收站文件会成为无法恢复的孤儿）。新增版本必须同步补显式 Migration。
                .build()
        }
    }
}
