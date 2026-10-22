package com.xjw.qalbum.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 来源统计的 SQL：增量触发器与整体重建步骤。
 *
 * 统计分两类：图片（status = active 且不是 chatthumb）与 QQ 缩略图（chatthumb，status = thumbnail_trash）。
 * - 缓存未标脏（qq_source_cache.dirty = 0）时，新增的关联（image_qq_origins）与图片的状态 / 大小变化由触发器
 *   增量更新 qq_image_contexts、各分类计数与汇总；单张删除只改动这张图涉及的几行。
 * - 读取聊天来源后由 [applyNew] 增量更新：只处理读取前最大 rowid 之后新增的来源与关联，名称只重算涉及的会话与发送人。
 * - 清除索引、升级等标脏后，触发器暂停，由 [rebuildNames]、[contextsInRange]、[recount] 分段重建
 *   （每段一个短事务，不长时间占住写锁）。
 *
 * 纯 SQL 便于迁移测试直接在 [SupportSQLiteDatabase] 上执行同一套语句。
 */
object QqSourceStatsSql {

    private fun img(row: String) = "($row.status = 'active' AND $row.source_type <> 'chatthumb')"
    private fun thumb(row: String) = "($row.source_type = 'chatthumb' AND $row.status = 'thumbnail_trash')"
    private fun counted(row: String) = "(${img(row)} OR ${thumb(row)})"

    /** 计数列的增量：先减去 [minus] 行的贡献再加上 [plus] 行的贡献（null 表示没有这一项） */
    private fun deltas(minus: String?, plus: String?): String {
        fun term(sign: String, row: String?, kind: (String) -> String, bytes: Boolean) =
            row?.let { " $sign ${kind(it)}" + if (bytes) " * $it.size" else "" }.orEmpty()
        fun column(name: String, kind: (String) -> String, bytes: Boolean) =
            "$name = $name" + term("-", minus, kind, bytes) + term("+", plus, kind, bytes)
        return listOf(
            column("image_count", ::img, false),
            column("image_bytes", ::img, true),
            column("thumb_count", ::thumb, false),
            column("thumb_bytes", ::thumb, true),
        ).joinToString(", ")
    }

    private const val CLEAN = "(SELECT dirty FROM qq_source_cache WHERE id = 1) = 0"
    private fun facetsOf(imageId: String) = """id IN (
        SELECT conversation_id FROM qq_image_contexts WHERE image_id = $imageId
        UNION SELECT sender_id FROM qq_image_contexts WHERE image_id = $imageId)"""
    private const val CONTEXTS_OF_NEW_PATH = """INSERT OR IGNORE INTO qq_image_contexts(image_id, conversation_id, sender_id)
        SELECT NEW.id, cf.id, sf.id FROM qq_origin_paths p
        CROSS JOIN qq_origins o ON o.id = p.origin_id
        CROSS JOIN qq_source_facets cf ON cf.kind = 0 AND cf.`key` = o.conversation_key
        CROSS JOIN qq_source_facets sf ON sf.kind = 1 AND sf.`key` = o.sender_key
        WHERE p.path = NEW.path"""

    /** v16 及以前「任何变化都标脏、整体重建」的触发器，v17 起由增量触发器取代 */
    val legacyTriggers = listOf("dirty_qq_links_insert", "dirty_qq_image_state", "dirty_qq_image_size")

    /** 汇总行（缺失时补上，标脏等待重建） */
    const val ENSURE_CACHE_ROW =
        "INSERT OR IGNORE INTO qq_source_cache(id, dirty, image_count, image_bytes, thumb_count, thumb_bytes) VALUES(1, 1, 0, 0, 0, 0)"

    const val MARK_DIRTY = "UPDATE qq_source_cache SET dirty = 1 WHERE id = 1"

    /** v27 换掉的两个按路径补关联的触发器（改由 [createTriggers] 里按关联增量的 qq_stats_link_insert 处理） */
    val replacedInV27 = listOf("qq_stats_image_insert", "qq_stats_image_path")

    /**
     * v27 的触发器：关联删除 / 名称变化仍标脏；图片状态、大小变化与新增的关联走增量。
     * 关联由 images 上的 link_qq_image_origin_* 触发器（新入库、改路径、改家族键）与读取来源时补上，新增一条就把
     * 这张图计入它第一次出现的会话 / 发送人；分类还没建（新群、新发送人）的跳过，由读取结束时的 [applyNew] 补上。
     */
    fun createTriggers(db: SupportSQLiteDatabase) {
        createNameAndStateTriggers(db)
        fun added(kind: (String) -> String, bytes: Boolean) =
            "COALESCE((SELECT " + (if (bytes) "CASE WHEN ${kind("i")} THEN i.size ELSE 0 END" else kind("i")) +
                " FROM images i WHERE i.id = NEW.image_id), 0)"
        val additions = listOf(
            "image_count = image_count + " + added(::img, false),
            "image_bytes = image_bytes + " + added(::img, true),
            "thumb_count = thumb_count + " + added(::thumb, false),
            "thumb_bytes = thumb_bytes + " + added(::thumb, true),
        ).joinToString(", ")
        // 两个分类都在才会写入投影；只给这张图还没计入的分类加
        val pair = """FROM qq_origins o
            CROSS JOIN qq_source_facets cf ON cf.kind = 0 AND cf.`key` = o.conversation_key
            CROSS JOIN qq_source_facets sf ON sf.kind = 1 AND sf.`key` = o.sender_key
            WHERE o.id = NEW.origin_id"""
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS qq_stats_link_insert
            AFTER INSERT ON image_qq_origins
            WHEN $CLEAN
            BEGIN
              UPDATE qq_source_facets SET $additions WHERE id IN (
                SELECT cf.id $pair AND NOT EXISTS (SELECT 1 FROM qq_image_contexts c WHERE c.image_id = NEW.image_id AND c.conversation_id = cf.id)
                UNION
                SELECT sf.id $pair AND NOT EXISTS (SELECT 1 FROM qq_image_contexts c WHERE c.image_id = NEW.image_id AND c.sender_id = sf.id));
              UPDATE qq_source_cache SET $additions
                WHERE id = 1 AND NOT EXISTS (SELECT 1 FROM qq_image_contexts WHERE image_id = NEW.image_id) AND EXISTS (SELECT 1 $pair);
              INSERT OR IGNORE INTO qq_image_contexts(image_id, conversation_id, sender_id) SELECT NEW.image_id, cf.id, sf.id $pair;
            END""")
    }

    /** 名称变化标脏、图片状态 / 大小变化与删除走增量（v17 起相同） */
    private fun createNameAndStateTriggers(db: SupportSQLiteDatabase) {
        val dirty = "UPDATE qq_source_cache SET dirty = 1 WHERE id = 1 AND dirty = 0;"
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS dirty_qq_links_delete
            AFTER DELETE ON image_qq_origins BEGIN $dirty END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS dirty_qq_origin_names
            AFTER UPDATE OF conversation_name,sender_name,member_name ON qq_origins
            WHEN OLD.conversation_name <> NEW.conversation_name OR OLD.sender_name <> NEW.sender_name OR OLD.member_name <> NEW.member_name
            BEGIN $dirty END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS dirty_qq_account_names
            AFTER UPDATE OF display_name ON qq_source_accounts WHEN OLD.display_name <> NEW.display_name
            BEGIN $dirty END""")
        // 状态 / 大小 / 类型变化：从这张图所在的每个会话与发送人里减去旧贡献、加上新贡献
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS qq_stats_image_update
            AFTER UPDATE OF status, size, source_type ON images
            WHEN (OLD.status IS NOT NEW.status OR OLD.size IS NOT NEW.size OR OLD.source_type IS NOT NEW.source_type)
              AND (${counted("OLD")} OR ${counted("NEW")})
              AND $CLEAN
              AND EXISTS (SELECT 1 FROM qq_image_contexts WHERE image_id = NEW.id)
            BEGIN
              UPDATE qq_source_facets SET ${deltas("OLD", "NEW")} WHERE ${facetsOf("NEW.id")};
              UPDATE qq_source_cache SET ${deltas("OLD", "NEW")} WHERE id = 1;
            END""")
        // 图片行被删除（极少见）：扣掉贡献并清掉关联
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS qq_stats_image_delete
            AFTER DELETE ON images
            WHEN EXISTS (SELECT 1 FROM qq_image_contexts WHERE image_id = OLD.id)
            BEGIN
              UPDATE qq_source_facets SET ${deltas("OLD", null)} WHERE $CLEAN AND ${counted("OLD")} AND ${facetsOf("OLD.id")};
              UPDATE qq_source_cache SET ${deltas("OLD", null)} WHERE id = 1 AND dirty = 0 AND ${counted("OLD")};
              DELETE FROM qq_image_contexts WHERE image_id = OLD.id;
            END""")
    }

    /** v17～v26 的触发器（旧版本迁移里用，保持当时的样子）：关联删除 / 名称变化仍标脏；图片变化按路径走增量 */
    fun createTriggersV17(db: SupportSQLiteDatabase) {
        createNameAndStateTriggers(db)
        // 扫描新入库的缓存文件：按路径找到已导入的消息，补上关联并计入统计
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS qq_stats_image_insert
            AFTER INSERT ON images
            WHEN $CLEAN AND EXISTS (SELECT 1 FROM qq_origin_paths WHERE path = NEW.path)
            BEGIN
              $CONTEXTS_OF_NEW_PATH;
              UPDATE qq_source_facets SET ${deltas(null, "NEW")} WHERE ${counted("NEW")} AND ${facetsOf("NEW.id")};
              UPDATE qq_source_cache SET ${deltas(null, "NEW")}
                WHERE id = 1 AND ${counted("NEW")} AND EXISTS (SELECT 1 FROM qq_image_contexts WHERE image_id = NEW.id);
            END""")
        // 路径变化（如恢复到新位置）：只给新增的会话 / 发送人计数，再补关联
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS qq_stats_image_path
            AFTER UPDATE OF path ON images
            WHEN OLD.path <> NEW.path AND $CLEAN AND EXISTS (SELECT 1 FROM qq_origin_paths WHERE path = NEW.path)
            BEGIN
              UPDATE qq_source_facets SET ${deltas(null, "NEW")} WHERE ${counted("NEW")} AND id IN (
                SELECT cf.id FROM qq_origin_paths p CROSS JOIN qq_origins o ON o.id = p.origin_id
                  CROSS JOIN qq_source_facets cf ON cf.kind = 0 AND cf.`key` = o.conversation_key
                  WHERE p.path = NEW.path
                    AND NOT EXISTS (SELECT 1 FROM qq_image_contexts c WHERE c.image_id = NEW.id AND c.conversation_id = cf.id)
                UNION
                SELECT sf.id FROM qq_origin_paths p CROSS JOIN qq_origins o ON o.id = p.origin_id
                  CROSS JOIN qq_source_facets sf ON sf.kind = 1 AND sf.`key` = o.sender_key
                  WHERE p.path = NEW.path
                    AND NOT EXISTS (SELECT 1 FROM qq_image_contexts c WHERE c.image_id = NEW.id AND c.sender_id = sf.id));
              UPDATE qq_source_cache SET ${deltas(null, "NEW")}
                WHERE id = 1 AND ${counted("NEW")} AND NOT EXISTS (SELECT 1 FROM qq_image_contexts WHERE image_id = NEW.id);
              $CONTEXTS_OF_NEW_PATH;
            END""")
    }

    /**
     * 重建第 1 步：一次遍历消息来源，补齐会话 / 发送人分类并刷新名称、搜索文本、最近时间与群名片。
     * 已有分类的 id 保持不变（关联表引用它）。
     */
    val rebuildNames: List<String> = listOf(
        ENSURE_CACHE_ROW,
        "DROP TABLE IF EXISTS temp.qq_pairs",
        """CREATE TEMP TABLE qq_pairs(conversation_key TEXT NOT NULL, sender_key TEXT NOT NULL,
            account_key TEXT NOT NULL, chat_type INTEGER NOT NULL, conversation_name TEXT NOT NULL,
            sender_name TEXT NOT NULL, member_name TEXT NOT NULL, latest INTEGER NOT NULL,
            PRIMARY KEY(conversation_key, sender_key))""",
        """INSERT INTO temp.qq_pairs SELECT conversation_key, sender_key, MAX(account_key), MAX(chat_type),
            MAX(conversation_name), MAX(sender_name), MAX(member_name), MAX(message_time)
            FROM qq_origins GROUP BY conversation_key, sender_key""",
        "DROP TABLE IF EXISTS temp.qq_facet_src",
        """CREATE TEMP TABLE qq_facet_src(kind INTEGER NOT NULL, `key` TEXT NOT NULL, name TEXT NOT NULL,
            search_text TEXT NOT NULL, account_key TEXT NOT NULL, chat_type INTEGER NOT NULL, latest INTEGER NOT NULL,
            PRIMARY KEY(kind, `key`))""",
        """INSERT INTO temp.qq_facet_src SELECT 0, conversation_key, MAX(conversation_name), MAX(conversation_name),
            MAX(account_key), MAX(chat_type), MAX(latest) FROM temp.qq_pairs GROUP BY conversation_key""",
        // 发送人可按历史昵称与任一群名片搜索
        """INSERT INTO temp.qq_facet_src SELECT 1, sender_key, MAX(sender_name),
            COALESCE(GROUP_CONCAT(DISTINCT NULLIF(sender_name, '')), '') || ' ' ||
            COALESCE(GROUP_CONCAT(DISTINCT NULLIF(member_name, '')), ''),
            MAX(account_key), 0, MAX(latest) FROM temp.qq_pairs GROUP BY sender_key""",
        """INSERT OR IGNORE INTO qq_source_facets(kind, `key`, name, search_text, account_key, chat_type,
            image_count, image_bytes, thumb_count, thumb_bytes, latest_time)
            SELECT kind, `key`, name, search_text, account_key, chat_type, 0, 0, 0, 0, latest FROM temp.qq_facet_src""",
        """UPDATE qq_source_facets SET
            name = COALESCE((SELECT s.name FROM temp.qq_facet_src s WHERE s.kind = qq_source_facets.kind AND s.`key` = qq_source_facets.`key`), name),
            search_text = COALESCE((SELECT s.search_text FROM temp.qq_facet_src s WHERE s.kind = qq_source_facets.kind AND s.`key` = qq_source_facets.`key`), search_text),
            chat_type = COALESCE((SELECT s.chat_type FROM temp.qq_facet_src s WHERE s.kind = qq_source_facets.kind AND s.`key` = qq_source_facets.`key`), chat_type),
            latest_time = COALESCE((SELECT s.latest FROM temp.qq_facet_src s WHERE s.kind = qq_source_facets.kind AND s.`key` = qq_source_facets.`key`), latest_time)""",
        "DELETE FROM qq_source_members",
        """INSERT OR IGNORE INTO qq_source_members(conversation_id, sender_id, card, latest_time)
            SELECT cf.id, sf.id, p.member_name, p.latest FROM temp.qq_pairs p
            CROSS JOIN qq_source_facets cf ON cf.kind = 0 AND cf.`key` = p.conversation_key
            CROSS JOIN qq_source_facets sf ON sf.kind = 1 AND sf.`key` = p.sender_key""",
        "DROP TABLE temp.qq_facet_src",
        "DROP TABLE temp.qq_pairs",
    )

    /** 关联表涉及的图片 id 范围（第 2 步按范围分段） */
    const val LINKED_IMAGE_RANGE = "SELECT COALESCE(MIN(image_id), 0), COALESCE(MAX(image_id), 0) FROM image_qq_origins"

    /** 重建第 2 步：把 (lo, hi] 范围内的消息关联投影成（图片, 会话, 发送人）；已存在的行跳过 */
    const val CONTEXTS_IN_RANGE = """INSERT OR IGNORE INTO qq_image_contexts(image_id, conversation_id, sender_id)
        SELECT l.image_id, cf.id, sf.id FROM image_qq_origins l
        CROSS JOIN qq_origins o ON o.id = l.origin_id
        CROSS JOIN qq_source_facets cf ON cf.kind = 0 AND cf.`key` = o.conversation_key
        CROSS JOIN qq_source_facets sf ON sf.kind = 1 AND sf.`key` = o.sender_key
        WHERE l.image_id > ? AND l.image_id <= ?"""

    fun contextsInRange(db: SupportSQLiteDatabase, afterId: Long, upToId: Long) =
        db.execSQL(CONTEXTS_IN_RANGE, arrayOf<Any>(afterId, upToId))

    /** 重建第 3 步：按当前状态重算各分类与汇总，清除脏标记，此后触发器接管增量维护 */
    val recount: List<String> = listOf(
        ENSURE_CACHE_ROW,
        "DROP TABLE IF EXISTS temp.qq_facet_counts",
        """CREATE TEMP TABLE qq_facet_counts(fid INTEGER PRIMARY KEY NOT NULL, ic INTEGER NOT NULL,
            ib INTEGER NOT NULL, tc INTEGER NOT NULL, tb INTEGER NOT NULL)""",
        """INSERT INTO temp.qq_facet_counts SELECT x.fid,
            SUM(+i.status = 'active' AND +i.source_type <> 'chatthumb'),
            SUM(CASE WHEN +i.status = 'active' AND +i.source_type <> 'chatthumb' THEN i.size ELSE 0 END),
            SUM(+i.source_type = 'chatthumb' AND +i.status = 'thumbnail_trash'),
            SUM(CASE WHEN +i.source_type = 'chatthumb' AND +i.status = 'thumbnail_trash' THEN i.size ELSE 0 END)
            FROM (SELECT DISTINCT conversation_id AS fid, image_id FROM qq_image_contexts
                  UNION ALL SELECT DISTINCT sender_id, image_id FROM qq_image_contexts) x
            CROSS JOIN images i ON i.id = x.image_id GROUP BY x.fid""",
        """UPDATE qq_source_facets SET
            image_count = COALESCE((SELECT ic FROM temp.qq_facet_counts WHERE fid = qq_source_facets.id), 0),
            image_bytes = COALESCE((SELECT ib FROM temp.qq_facet_counts WHERE fid = qq_source_facets.id), 0),
            thumb_count = COALESCE((SELECT tc FROM temp.qq_facet_counts WHERE fid = qq_source_facets.id), 0),
            thumb_bytes = COALESCE((SELECT tb FROM temp.qq_facet_counts WHERE fid = qq_source_facets.id), 0)""",
        "DROP TABLE temp.qq_facet_counts",
        """UPDATE qq_source_cache SET dirty = 0,
            image_count = (SELECT COUNT(*) FROM images i WHERE +i.status = 'active' AND +i.source_type <> 'chatthumb'
                AND i.id IN (SELECT image_id FROM qq_image_contexts)),
            image_bytes = (SELECT COALESCE(SUM(i.size), 0) FROM images i WHERE +i.status = 'active' AND +i.source_type <> 'chatthumb'
                AND i.id IN (SELECT image_id FROM qq_image_contexts)),
            thumb_count = (SELECT COUNT(*) FROM images i WHERE +i.source_type = 'chatthumb' AND +i.status = 'thumbnail_trash'
                AND i.id IN (SELECT image_id FROM qq_image_contexts)),
            thumb_bytes = (SELECT COALESCE(SUM(i.size), 0) FROM images i WHERE +i.source_type = 'chatthumb' AND +i.status = 'thumbnail_trash'
                AND i.id IN (SELECT image_id FROM qq_image_contexts))
            WHERE id = 1""",
    )

    /** 增量更新前建好要重算名称的会话（0）/ 发送人（1）列表，改了名的用 [TOUCH] 放进去 */
    val prepareTouched: List<String> = listOf(
        "DROP TABLE IF EXISTS temp.qq_touched",
        "CREATE TEMP TABLE qq_touched(kind INTEGER NOT NULL, `key` TEXT NOT NULL, PRIMARY KEY(kind, `key`))",
    )

    const val TOUCH = "INSERT OR IGNORE INTO temp.qq_touched(kind, `key`) VALUES(?, ?)"

    /**
     * 读取来源后的增量更新（与 [prepareTouched] 在同一事务里执行，缓存未标脏时）：[origins] / [links] 是读取前
     * qq_origins / image_qq_origins 的最大 rowid。
     * 1. 新来源涉及的会话与发送人，按它们的全部来源重算名称、搜索文本、最近时间与群名片（口径同 [rebuildNames]）；
     * 2. 新关联投影成（图片, 会话, 发送人），图片第一次出现在某个会话 / 发送人里才计入它的统计，第一次有来源才计入汇总。
     * 结果与整体重建一致，只是不必把几十万条来源与关联都过一遍。
     */
    fun applyNew(db: SupportSQLiteDatabase, origins: Long, links: Long) {
        db.execSQL("INSERT OR IGNORE INTO temp.qq_touched SELECT 0, conversation_key FROM qq_origins WHERE rowid > ?", arrayOf<Any>(origins))
        db.execSQL("INSERT OR IGNORE INTO temp.qq_touched SELECT 1, sender_key FROM qq_origins WHERE rowid > ?", arrayOf<Any>(origins))
        touchedNames.forEach(db::execSQL)
        db.execSQL(NEW_CONTEXTS, arrayOf<Any>(links))
        newCounts.forEach(db::execSQL)
    }

    private const val PAIRS_OF_TOUCHED = """SELECT conversation_key, sender_key, MAX(account_key), MAX(chat_type),
        MAX(conversation_name), MAX(sender_name), MAX(member_name), MAX(message_time) FROM qq_origins"""

    private fun touchedSource(kind: Int) = "SELECT 1 FROM temp.qq_touched t WHERE t.kind = $kind AND t.`key` ="

    private val touchedNames: List<String> = listOf(
        "DROP TABLE IF EXISTS temp.qq_pairs",
        """CREATE TEMP TABLE qq_pairs(conversation_key TEXT NOT NULL, sender_key TEXT NOT NULL,
            account_key TEXT NOT NULL, chat_type INTEGER NOT NULL, conversation_name TEXT NOT NULL,
            sender_name TEXT NOT NULL, member_name TEXT NOT NULL, latest INTEGER NOT NULL,
            PRIMARY KEY(conversation_key, sender_key))""",
        // 涉及的会话、发送人的全部来源（一对会话 / 发送人两边都涉及时两条结果相同，后一条忽略）
        """INSERT OR IGNORE INTO temp.qq_pairs $PAIRS_OF_TOUCHED
            WHERE conversation_key IN (SELECT `key` FROM temp.qq_touched WHERE kind = 0) GROUP BY conversation_key, sender_key""",
        """INSERT OR IGNORE INTO temp.qq_pairs $PAIRS_OF_TOUCHED
            WHERE sender_key IN (SELECT `key` FROM temp.qq_touched WHERE kind = 1) GROUP BY conversation_key, sender_key""",
        "DROP TABLE IF EXISTS temp.qq_facet_src",
        """CREATE TEMP TABLE qq_facet_src(kind INTEGER NOT NULL, `key` TEXT NOT NULL, name TEXT NOT NULL,
            search_text TEXT NOT NULL, account_key TEXT NOT NULL, chat_type INTEGER NOT NULL, latest INTEGER NOT NULL,
            PRIMARY KEY(kind, `key`))""",
        """INSERT INTO temp.qq_facet_src SELECT 0, conversation_key, MAX(conversation_name), MAX(conversation_name),
            MAX(account_key), MAX(chat_type), MAX(latest) FROM temp.qq_pairs p
            WHERE EXISTS (${touchedSource(0)} p.conversation_key) GROUP BY conversation_key""",
        """INSERT INTO temp.qq_facet_src SELECT 1, sender_key, MAX(sender_name),
            COALESCE(GROUP_CONCAT(DISTINCT NULLIF(sender_name, '')), '') || ' ' ||
            COALESCE(GROUP_CONCAT(DISTINCT NULLIF(member_name, '')), ''),
            MAX(account_key), 0, MAX(latest) FROM temp.qq_pairs p
            WHERE EXISTS (${touchedSource(1)} p.sender_key) GROUP BY sender_key""",
        """INSERT OR IGNORE INTO qq_source_facets(kind, `key`, name, search_text, account_key, chat_type,
            image_count, image_bytes, thumb_count, thumb_bytes, latest_time)
            SELECT kind, `key`, name, search_text, account_key, chat_type, 0, 0, 0, 0, latest FROM temp.qq_facet_src""",
        """UPDATE qq_source_facets SET
            name = (SELECT s.name FROM temp.qq_facet_src s WHERE s.kind = qq_source_facets.kind AND s.`key` = qq_source_facets.`key`),
            search_text = (SELECT s.search_text FROM temp.qq_facet_src s WHERE s.kind = qq_source_facets.kind AND s.`key` = qq_source_facets.`key`),
            chat_type = (SELECT s.chat_type FROM temp.qq_facet_src s WHERE s.kind = qq_source_facets.kind AND s.`key` = qq_source_facets.`key`),
            latest_time = (SELECT s.latest FROM temp.qq_facet_src s WHERE s.kind = qq_source_facets.kind AND s.`key` = qq_source_facets.`key`)
            WHERE EXISTS (SELECT 1 FROM temp.qq_facet_src s WHERE s.kind = qq_source_facets.kind AND s.`key` = qq_source_facets.`key`)""",
        """INSERT OR REPLACE INTO qq_source_members(conversation_id, sender_id, card, latest_time)
            SELECT cf.id, sf.id, p.member_name, p.latest FROM temp.qq_pairs p
            CROSS JOIN qq_source_facets cf ON cf.kind = 0 AND cf.`key` = p.conversation_key
            CROSS JOIN qq_source_facets sf ON sf.kind = 1 AND sf.`key` = p.sender_key""",
        "DROP TABLE temp.qq_facet_src",
        "DROP TABLE temp.qq_pairs",
        "DROP TABLE temp.qq_touched",
        "DROP TABLE IF EXISTS temp.qq_new_contexts",
        """CREATE TEMP TABLE qq_new_contexts(image_id INTEGER NOT NULL, conversation_id INTEGER NOT NULL,
            sender_id INTEGER NOT NULL, PRIMARY KEY(image_id, conversation_id, sender_id))""",
    )

    private const val NEW_CONTEXTS = """INSERT OR IGNORE INTO temp.qq_new_contexts SELECT l.image_id, cf.id, sf.id
        FROM image_qq_origins l
        CROSS JOIN qq_origins o ON o.id = l.origin_id
        CROSS JOIN qq_source_facets cf ON cf.kind = 0 AND cf.`key` = o.conversation_key
        CROSS JOIN qq_source_facets sf ON sf.kind = 1 AND sf.`key` = o.sender_key
        WHERE l.rowid > ?
          AND NOT EXISTS (SELECT 1 FROM qq_image_contexts c
              WHERE c.image_id = l.image_id AND c.conversation_id = cf.id AND c.sender_id = sf.id)"""

    /** 新计入的张数或大小，图片按当前状态算（与 [recount] 同样用 + 号避开索引） */
    private fun added(kind: (String) -> String, bytes: Boolean, from: String, where: String) =
        "(SELECT " + (if (bytes) "COALESCE(SUM(i.size), 0)" else "COUNT(*)") + " FROM $from WHERE $where AND ${kind("i")})"

    private fun plainImg(row: String) = "(+$row.status = 'active' AND +$row.source_type <> 'chatthumb')"
    private fun plainThumb(row: String) = "(+$row.source_type = 'chatthumb' AND +$row.status = 'thumbnail_trash')"

    private val newCounts: List<String> = run {
        val facetFrom = "temp.qq_new_facet_images x CROSS JOIN images i ON i.id = x.image_id"
        val facetWhere = "x.fid = qq_source_facets.id"
        val cacheFrom = "images i"
        val cacheWhere = """i.id IN (SELECT image_id FROM temp.qq_new_contexts)
            AND NOT EXISTS (SELECT 1 FROM qq_image_contexts c WHERE c.image_id = i.id)"""
        fun additions(from: String, where: String) = listOf(
            "image_count = image_count + " + added(::plainImg, false, from, where),
            "image_bytes = image_bytes + " + added(::plainImg, true, from, where),
            "thumb_count = thumb_count + " + added(::plainThumb, false, from, where),
            "thumb_bytes = thumb_bytes + " + added(::plainThumb, true, from, where),
        ).joinToString(",\n")
        listOf(
            "DROP TABLE IF EXISTS temp.qq_new_facet_images",
            "CREATE TEMP TABLE qq_new_facet_images(fid INTEGER NOT NULL, image_id INTEGER NOT NULL, PRIMARY KEY(fid, image_id))",
            """INSERT OR IGNORE INTO temp.qq_new_facet_images SELECT n.conversation_id, n.image_id FROM temp.qq_new_contexts n
                WHERE NOT EXISTS (SELECT 1 FROM qq_image_contexts c WHERE c.image_id = n.image_id AND c.conversation_id = n.conversation_id)""",
            """INSERT OR IGNORE INTO temp.qq_new_facet_images SELECT n.sender_id, n.image_id FROM temp.qq_new_contexts n
                WHERE NOT EXISTS (SELECT 1 FROM qq_image_contexts c WHERE c.image_id = n.image_id AND c.sender_id = n.sender_id)""",
            "UPDATE qq_source_facets SET ${additions(facetFrom, facetWhere)} WHERE id IN (SELECT fid FROM temp.qq_new_facet_images)",
            "UPDATE qq_source_cache SET ${additions(cacheFrom, cacheWhere)} WHERE id = 1",
            """INSERT OR IGNORE INTO qq_image_contexts(image_id, conversation_id, sender_id)
                SELECT image_id, conversation_id, sender_id FROM temp.qq_new_contexts""",
            "DROP TABLE temp.qq_new_facet_images",
            "DROP TABLE temp.qq_new_contexts",
        )
    }

    /** 清除来源索引后的空状态（账号级联删除了来源与分类） */
    val clearDerived: List<String> = listOf(
        "DELETE FROM qq_image_contexts",
        "DELETE FROM qq_source_members",
        "DELETE FROM qq_source_facets",
        ENSURE_CACHE_ROW,
        "UPDATE qq_source_cache SET dirty = 0, image_count = 0, image_bytes = 0, thumb_count = 0, thumb_bytes = 0, complete_read_at = 0 WHERE id = 1",
    )
}
