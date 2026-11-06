package com.xjw.qalbum.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 来源统计的 SQL：增量触发器与整体重建步骤。
 *
 * 统计分两类：图片（status = active 且不是 chatthumb）与 QQ 缩略图（chatthumb，status = thumbnail_trash）。
 * - 缓存未标脏（qq_source_cache.dirty = 0）时，新增的关联（image_qq_origins）与图片的状态 / 大小变化由触发器
 *   增量更新 qq_image_contexts、各分类计数与汇总；单张删除只改动这张图涉及的几行。
 * - 读取聊天来源后由 [applyNew] 增量更新：只处理读取前最大 rowid 之后新增的来源与关联，名称取较大值并入，改了名的才重算。
 * - 清除索引、升级等标脏后，触发器暂停，由 QqSourceStatsBuilder 分段重建（每段一个短事务，不长时间占住写锁，
 *   每段只在内存里放一小段，见下面的「整体重建」）。
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

    // ---- 整体重建（QqSourceStatsBuilder 分段执行，每段一个短事务）与读取后的增量更新 ----
    //
    // Android 的 SQLite 把临时表、排序与去重都放在内存里：在几百万条来源上整表 GROUP BY / DISTINCT 要几百 MB 到上 GB，
    // 手机上内存不足时整个进程崩溃。下面每一步只把一小段放进内存：沿索引顺序分组（不排序）、按来源行号分段，
    // 或者一个分类一个分类地算（只放这一类的数据）。

    /** 会话分类：名称（取最大，同 v17）、搜索文本（同名称）、账号、会话类型、最近一张的时间。都取最大，分段并入与一次算完相同 */
    private const val CONVERSATION_VALUES =
        "MAX(conversation_name), MAX(conversation_name), MAX(account_key), MAX(chat_type), MAX(message_time)"

    /** 发送人分类：名称、搜索文本（先同名称，再由 [updateSenderSearchText] 加上群名片）、账号、类型 0、最近一张的时间 */
    private const val SENDER_VALUES = "MAX(sender_name), MAX(sender_name), MAX(account_key), 0, MAX(message_time)"

    /** 两种分类：会话（0）、发送人（1）；来源表里的键、键上的索引与分类的值 */
    enum class FacetKind(val kind: Int, val column: String, val index: String, val values: String) {
        CONVERSATION(0, "conversation_key", "index_qq_origins_conversation_key", CONVERSATION_VALUES),
        SENDER(1, "sender_key", "index_qq_origins_sender_key", SENDER_VALUES),
    }

    private const val INSERT_FACETS = """INSERT OR IGNORE INTO qq_source_facets(kind, `key`, name, search_text, account_key, chat_type,
        latest_time, image_count, image_bytes, thumb_count, thumb_bytes)"""

    /** 重建第 1 步按键分段：[after] 之后第 [count] 个键（只读索引）；剩下不到 [count] 个时为 null */
    fun facetKeyBoundary(db: SupportSQLiteDatabase, kind: FacetKind, after: String, count: Int): String? =
        db.query("""SELECT ${kind.column} FROM qq_origins INDEXED BY ${kind.index} WHERE ${kind.column} > ?
            GROUP BY ${kind.column} LIMIT 1 OFFSET ${count - 1}""", arrayOf(after)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** 重建第 1 步：键在 (after, upTo] 范围内（[upTo] 为 null 时到末尾）、还没有的分类按全部来源建上（沿索引分组，不排序） */
    fun insertFacets(db: SupportSQLiteDatabase, kind: FacetKind, after: String, upTo: String?) {
        val range = if (upTo == null) "${kind.column} > ?" else "${kind.column} > ? AND ${kind.column} <= ?"
        db.execSQL("""$INSERT_FACETS SELECT ${kind.kind}, ${kind.column}, ${kind.values}, 0, 0, 0, 0
            FROM qq_origins INDEXED BY ${kind.index} WHERE $range GROUP BY ${kind.column}""", listOfNotNull(after, upTo).toTypedArray())
    }

    /** 重建第 1 步：已有的分类（id 在 (afterId, upToId] 范围内）按全部来源重算名称等 */
    fun refreshFacets(db: SupportSQLiteDatabase, kind: FacetKind, afterId: Long, upToId: Long) =
        db.execSQL(refresh(kind, "+kind = ${kind.kind} AND id > ? AND id <= ?"), arrayOf<Any>(afterId, upToId))

    /** 选出的分类按全部来源重算（沿索引只读这一类的来源） */
    private fun refresh(kind: FacetKind, where: String) = """UPDATE qq_source_facets
        SET (name, search_text, account_key, chat_type, latest_time) =
            (SELECT ${kind.values} FROM qq_origins WHERE ${kind.column} = qq_source_facets.`key`)
        WHERE $where AND EXISTS (SELECT 1 FROM qq_origins WHERE ${kind.column} = qq_source_facets.`key`)"""

    /**
     * 重建第 2 步（按来源行号分段）与增量更新：(afterRowid, upToRowid] 范围内的来源按会话 + 发送人汇总群名片与最近时间，
     * 并进 qq_source_members（都取较大值，分段并入与一次算完相同）。
     */
    fun mergeMembers(db: SupportSQLiteDatabase, afterRowid: Long, upToRowid: Long) {
        db.execSQL("DROP TABLE IF EXISTS temp.qq_member_part")
        db.execSQL("""CREATE TEMP TABLE qq_member_part(cid INTEGER NOT NULL, sid INTEGER NOT NULL, card TEXT NOT NULL,
            latest INTEGER NOT NULL, PRIMARY KEY(cid, sid))""")
        db.execSQL("""INSERT INTO temp.qq_member_part SELECT cf.id, sf.id, MAX(o.member_name), MAX(o.message_time) FROM qq_origins o
            CROSS JOIN qq_source_facets cf ON cf.kind = 0 AND cf.`key` = o.conversation_key
            CROSS JOIN qq_source_facets sf ON sf.kind = 1 AND sf.`key` = o.sender_key
            WHERE o.rowid > ? AND o.rowid <= ? GROUP BY o.conversation_key, o.sender_key""", arrayOf<Any>(afterRowid, upToRowid))
        db.execSQL("""UPDATE qq_source_members SET (card, latest_time) = (SELECT max(qq_source_members.card, p.card),
                max(qq_source_members.latest_time, p.latest) FROM temp.qq_member_part p WHERE p.cid = conversation_id AND p.sid = sender_id)
            WHERE (conversation_id, sender_id) IN (SELECT cid, sid FROM temp.qq_member_part)""")
        db.execSQL("""INSERT OR IGNORE INTO qq_source_members(conversation_id, sender_id, card, latest_time)
            SELECT cid, sid, card, latest FROM temp.qq_member_part""")
        db.execSQL("DROP TABLE temp.qq_member_part")
    }

    /**
     * 发送人的搜索文本：名称加上他在各群的群名片（按其中任一个都能搜到）。群名片取自 qq_source_members
     * （一个人在几个群就几行），不必把他的全部来源再读一遍。[touchedOnly] 时只算 temp.qq_touched 里的发送人（增量更新）。
     */
    fun updateSenderSearchText(db: SupportSQLiteDatabase, touchedOnly: Boolean) {
        val touched = "`key` IN (SELECT `key` FROM temp.qq_touched WHERE kind = 1)"
        val senders = if (touchedOnly) "sender_id IN (SELECT id FROM qq_source_facets WHERE kind = 1 AND $touched)" else "1"
        db.execSQL("DROP TABLE IF EXISTS temp.qq_sender_cards")
        db.execSQL("CREATE TEMP TABLE qq_sender_cards(sid INTEGER PRIMARY KEY NOT NULL, cards TEXT)")
        db.execSQL("""INSERT INTO temp.qq_sender_cards SELECT sender_id, GROUP_CONCAT(DISTINCT NULLIF(card, ''))
            FROM qq_source_members WHERE $senders GROUP BY sender_id""")
        db.execSQL("""UPDATE qq_source_facets SET search_text = COALESCE(NULLIF(name, ''), '') || ' ' ||
                COALESCE((SELECT cards FROM temp.qq_sender_cards WHERE sid = qq_source_facets.id), '')
            WHERE kind = 1${if (touchedOnly) " AND $touched" else ""}""")
        db.execSQL("DROP TABLE temp.qq_sender_cards")
    }

    /** 重建第 1、2 步一次做完（测试与小库用；仓库里由 QqSourceStatsBuilder 分段执行）：补齐分类、刷新名称、群名片与搜索文本 */
    fun rebuildNames(db: SupportSQLiteDatabase) {
        db.execSQL(ENSURE_CACHE_ROW)
        val existing = db.long("SELECT COALESCE(MAX(id), 0) FROM qq_source_facets")
        FacetKind.entries.forEach { kind ->
            insertFacets(db, kind, "", null)
            refreshFacets(db, kind, 0, existing)
        }
        db.execSQL("DELETE FROM qq_source_members")
        mergeMembers(db, 0, db.long("SELECT COALESCE(MAX(rowid), 0) FROM qq_origins"))
        updateSenderSearchText(db, touchedOnly = false)
    }

    /** 关联表涉及的图片 id 范围（第 3 步按范围分段） */
    const val LINKED_IMAGE_RANGE = "SELECT COALESCE(MIN(image_id), 0), COALESCE(MAX(image_id), 0) FROM image_qq_origins"

    /** 重建第 3 步：把 (lo, hi] 范围内的消息关联投影成（图片, 会话, 发送人）；已存在的行跳过 */
    const val CONTEXTS_IN_RANGE = """INSERT OR IGNORE INTO qq_image_contexts(image_id, conversation_id, sender_id)
        SELECT l.image_id, cf.id, sf.id FROM image_qq_origins l
        CROSS JOIN qq_origins o ON o.id = l.origin_id
        CROSS JOIN qq_source_facets cf ON cf.kind = 0 AND cf.`key` = o.conversation_key
        CROSS JOIN qq_source_facets sf ON sf.kind = 1 AND sf.`key` = o.sender_key
        WHERE l.image_id > ? AND l.image_id <= ?"""

    fun contextsInRange(db: SupportSQLiteDatabase, afterId: Long, upToId: Long) =
        db.execSQL(CONTEXTS_IN_RANGE, arrayOf<Any>(afterId, upToId))

    /** 张数、大小（图片与 QQ 缩略图），图片按当前状态算（用 + 号避开索引） */
    private const val COUNTS = """COALESCE(SUM(+i.status = 'active' AND +i.source_type <> 'chatthumb'), 0),
        COALESCE(SUM(CASE WHEN +i.status = 'active' AND +i.source_type <> 'chatthumb' THEN i.size ELSE 0 END), 0),
        COALESCE(SUM(+i.source_type = 'chatthumb' AND +i.status = 'thumbnail_trash'), 0),
        COALESCE(SUM(CASE WHEN +i.source_type = 'chatthumb' AND +i.status = 'thumbnail_trash' THEN i.size ELSE 0 END), 0)"""

    /** 一个分类的计数：只把这一类涉及的图片 id 放进内存（不超过图库的图片数） */
    private fun facetCounts(column: String) = """(SELECT $COUNTS FROM images i
        WHERE i.id IN (SELECT image_id FROM qq_image_contexts WHERE $column = qq_source_facets.id))"""

    /**
     * 重建第 4 步（一个事务里：期间删图、改状态的增量触发器还停着，分开做会漏掉）：各分类按当前状态重算，汇总只算有来源的图片，
     * 清除脏标记，此后触发器接管增量维护。
     */
    val recount: List<String> = listOf(
        ENSURE_CACHE_ROW,
        "UPDATE qq_source_facets SET (image_count, image_bytes, thumb_count, thumb_bytes) = ${facetCounts("conversation_id")} WHERE kind = 0",
        "UPDATE qq_source_facets SET (image_count, image_bytes, thumb_count, thumb_bytes) = ${facetCounts("sender_id")} WHERE kind = 1",
        """UPDATE qq_source_cache SET dirty = 0, (image_count, image_bytes, thumb_count, thumb_bytes) =
            (SELECT $COUNTS FROM images i WHERE EXISTS (SELECT 1 FROM qq_image_contexts c WHERE c.image_id = i.id))
            WHERE id = 1""",
    )

    /**
     * 读取来源后的增量更新（缓存未标脏时，一个事务里）：[origins] / [links] 是读取前 qq_origins / image_qq_origins 的最大 rowid，
     * [renamed] 是这次改了名的会话（0）/ 发送人（1）。只读新增的行（加上改了名的几个分类各自的来源）：
     * 1. 新来源的分类取较大值并入（名称、账号、类型、最近时间都取最大，与按全部来源算相同）；改了名的按全部来源重算
     *    （名称变小时取较大值不对）；
     * 2. 群名片与最近时间并入 qq_source_members，有新来源或改了名的发送人按群名片重算搜索文本；
     * 3. 新关联投影成（图片, 会话, 发送人），图片第一次出现在某个会话 / 发送人里才计入它的统计，第一次有来源才计入汇总。
     * 结果与整体重建一致，不用把大群、常发图的人的全部历史再读一遍。
     */
    fun applyNew(db: SupportSQLiteDatabase, origins: Long, links: Long, renamed: Set<Pair<Int, String>>) {
        db.execSQL("DROP TABLE IF EXISTS temp.qq_new_facets")
        db.execSQL("""CREATE TEMP TABLE qq_new_facets(kind INTEGER NOT NULL, `key` TEXT NOT NULL, name TEXT NOT NULL,
            search_text TEXT NOT NULL, account_key TEXT NOT NULL, chat_type INTEGER NOT NULL, latest INTEGER NOT NULL,
            PRIMARY KEY(kind, `key`))""")
        // NOT INDEXED：只按行号取新增的几行；否则为了省掉排序会沿键的索引把全部来源走一遍
        FacetKind.entries.forEach { kind ->
            db.execSQL("""INSERT INTO temp.qq_new_facets SELECT ${kind.kind}, ${kind.column}, ${kind.values}
                FROM qq_origins NOT INDEXED WHERE rowid > ? GROUP BY ${kind.column}""", arrayOf<Any>(origins))
        }
        db.execSQL("$INSERT_FACETS SELECT kind, `key`, name, search_text, account_key, chat_type, latest, 0, 0, 0, 0 FROM temp.qq_new_facets")
        db.execSQL("""UPDATE qq_source_facets SET (name, search_text, account_key, chat_type, latest_time) =
            (SELECT max(qq_source_facets.name, n.name), max(qq_source_facets.search_text, n.search_text),
                max(qq_source_facets.account_key, n.account_key), max(qq_source_facets.chat_type, n.chat_type),
                max(qq_source_facets.latest_time, n.latest)
             FROM temp.qq_new_facets n WHERE n.kind = qq_source_facets.kind AND n.`key` = qq_source_facets.`key`)
            WHERE (kind, `key`) IN (SELECT kind, `key` FROM temp.qq_new_facets)""")
        db.execSQL("DROP TABLE IF EXISTS temp.qq_renamed")
        db.execSQL("CREATE TEMP TABLE qq_renamed(kind INTEGER NOT NULL, `key` TEXT NOT NULL, PRIMARY KEY(kind, `key`))")
        renamed.forEach { (kind, key) -> db.execSQL("INSERT OR IGNORE INTO temp.qq_renamed(kind, `key`) VALUES(?, ?)", arrayOf<Any>(kind, key)) }
        FacetKind.entries.forEach { kind ->
            db.execSQL(refresh(kind, "kind = ${kind.kind} AND `key` IN (SELECT `key` FROM temp.qq_renamed WHERE kind = ${kind.kind})"))
        }
        val last = db.long("SELECT COALESCE(MAX(rowid), 0) FROM qq_origins")
        var after = origins
        while (after < last) {
            val upTo = minOf(after + MEMBER_ROWS, last)
            mergeMembers(db, after, upTo)
            after = upTo
        }
        db.execSQL("DROP TABLE IF EXISTS temp.qq_touched")
        db.execSQL("CREATE TEMP TABLE qq_touched(kind INTEGER NOT NULL, `key` TEXT NOT NULL, PRIMARY KEY(kind, `key`))")
        db.execSQL("INSERT INTO temp.qq_touched SELECT kind, `key` FROM temp.qq_renamed WHERE kind = 1")
        db.execSQL("INSERT OR IGNORE INTO temp.qq_touched SELECT kind, `key` FROM temp.qq_new_facets WHERE kind = 1")
        updateSenderSearchText(db, touchedOnly = true)
        db.execSQL("DROP TABLE temp.qq_touched")
        db.execSQL("DROP TABLE temp.qq_renamed")
        db.execSQL("DROP TABLE temp.qq_new_facets")
        db.execSQL("DROP TABLE IF EXISTS temp.qq_new_contexts")
        db.execSQL("""CREATE TEMP TABLE qq_new_contexts(image_id INTEGER NOT NULL, conversation_id INTEGER NOT NULL,
            sender_id INTEGER NOT NULL, PRIMARY KEY(image_id, conversation_id, sender_id))""")
        db.execSQL(NEW_CONTEXTS, arrayOf<Any>(links))
        newCounts.forEach(db::execSQL)
    }

    /** 群名片按来源行号分段汇总时每段的行数 */
    const val MEMBER_ROWS = 20_000L

    private fun SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

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
