package com.xjw.qalbum.qq

import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqOriginFamilyEntity
import com.xjw.qalbum.data.db.entity.QqOriginPathEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.zetetic.database.sqlcipher.SQLiteDatabase
import timber.log.Timber

/**
 * 一个账号的读取结果。[marks] 是读完整的消息表新的水位（表名 → 最大 rowid），没读完整的表不在其中（下次从头读）；
 * 个别读不了的坏行不影响水位（QQ 库里本来就坏的行每次都一样，从头重读也读不出来）。
 * [renamed] 是名字变了的会话（0）/ 发送人（1），来源统计要重算它们的名称。
 */
internal data class QqImportResult(
    val pictures: Int,
    val skipped: Int,
    val unreadable: Int,
    val incompleteTables: Int,
    val marks: Map<String, Long> = emptyMap(),
    val renamed: Set<Pair<Int, String>> = emptySet(),
)
internal data class QqPerson(val uid: String, val nickname: String, val remark: String, val card: String = "") {
    val displayName: String get() = when {
        nickname.isNotBlank() && remark.isNotBlank() && nickname != remark -> "$nickname ($remark)"
        nickname.isNotBlank() -> nickname
        else -> remark
    }
}

internal class QqSourceImporter(private val db: AppDatabase) {
    /** 一条消息的一张图：第一个 MD5 的家族键记在 [origin] 上，[families] 是其余 MD5 的，[paths] 是推不出家族键的路径 */
    private class Pending(val origin: QqOriginEntity, val families: List<Long>, val paths: List<String>)

    private fun toPending(origin: QqOriginEntity, picture: QqPictureElement, root: String): Pending {
        val families = QqCachePaths.families(picture)
        return Pending(origin.copy(family = families.firstOrNull()), families.drop(1), QqCachePaths.extraPaths(root, picture, families))
    }

    /**
     * 读取一个账号的消息来源。[readStart] 是这次读取的开始时间：读到的每条消息都记 seen_at = [readStart]，
     * 完整读取后仍早于它的消息就是 QQ 里已经删掉的（见「聊天记录已删除」规则）。
     * [startAfter]（表名 → rowid）给出时只读这之后的新消息（增量读取）。
     * 图片按 MD5 的家族键关联（见 QqOriginEntity.family）；以前读过的消息只更新读取时间，[allPaths] 为真时
     * 也补登记它们的其他家族与另记的路径（解析规则更新后）。
     *
     * 写入按几千条一个短事务提交，不长时间占住数据库（期间删图、扫描照常）；关联与统计由调用方在全部读完后补上。
     */
    suspend fun import(
        snapshot: QqDatabaseSnapshot,
        account: QqAccount,
        accountNumber: Int,
        chatpicRoot: String,
        readStart: Long = System.currentTimeMillis(),
        startAfter: Map<String, Long> = emptyMap(),
        allPaths: Boolean = false,
        onProgress: (Int, Int) -> Unit,
    ): QqImportResult = QqCopiedDatabaseReader(snapshot, account).use { reader ->
        val messages = reader.get("nt_msg.db") ?: error("QQ 消息数据库不存在")
        val names = QqNames(reader.get("group_info.db"), reader.get("profile_info.db"))
        val dao = db.qqSourceDao()
        val tables = listOf("group_msg_table" to 2, "c2c_msg_table" to 1).filter { (table, _) ->
            val columns = messages.columns(table)
            if (columns.isEmpty()) false else {
                require(columns.containsAll(listOf("40001", "40020", "40021", "40050", "40800"))) { "QQ 图片来源字段尚不兼容" }
                true
            }
        }
        check(tables.isNotEmpty()) { "QQ 消息表尚不兼容" }
        var processed = 0
        var pictures = 0
        var skipped = 0
        var unreadable = 0
        var incompleteTables = 0
        val conversationNames = mutableMapOf<String, String>()
        val senderNames = mutableMapOf<String, String>()
        val fieldStats = QqPictureFieldStats()
        val marks = mutableMapOf<String, Long>()
        val displayName = names.person(account.uid, "").displayName.ifBlank { "QQ 账号 $accountNumber" }
        dao.addAccount(QqSourceAccountEntity(account.key, displayName, System.currentTimeMillis(), 0))
        val pending = mutableListOf<Pending>()
        suspend fun flush() {
            if (pending.isEmpty()) return
            db.withTransaction {
                pending.chunked(SQL_CHUNK).forEach { chunk ->
                    val rowids = dao.insertOrigins(chunk.map { it.origin })
                    val seen = mutableListOf<QqOriginEntity>()
                    val families = mutableListOf<QqOriginFamilyEntity>()
                    val paths = mutableListOf<QqOriginPathEntity>()
                    chunk.forEachIndexed { index, item ->
                        val added = rowids.getOrElse(index) { -1L } != -1L
                        if (!added) seen += item.origin
                        if (added || allPaths) {
                            item.families.mapTo(families) { QqOriginFamilyEntity(it, item.origin.id) }
                            item.paths.mapTo(paths) { QqOriginPathEntity(it, item.origin.id) }
                        }
                    }
                    if (families.isNotEmpty()) dao.insertFamilies(families)
                    if (paths.isNotEmpty()) dao.insertPaths(paths)
                    // 以前读过的消息补上这次的读取时间与图片属性；按属性分组，每组一条语句
                    seen.groupBy { Triple(it.picType, it.picSubType, it.sentOriginal) }.forEach { (info, group) ->
                        dao.markSeen(group.map { it.id }, readStart, info.first, info.second, info.third)
                    }
                }
            }
            pending.clear()
        }
        for ((table, chatType) in tables) {
            val started = System.currentTimeMillis()
            val columns = messages.columns(table)
            val previousProcessed = processed
            val after = startAfter[table]
            val stats = QqMessageReader(messages, fieldStats).forEachBatch(table, columns, after) { records, batchStats ->
                processed = previousProcessed + batchStats.inspected
                onProgress(processed, 0)
                for (record in records) {
                    currentCoroutineContext().ensureActive()
                    val values = record.values
                    val msgId = values[0]
                    val senderUid = values[1]
                    val peerUid = values[2]
                    val peerUin = values[4].takeUnless { it == "0" || it.isEmpty() }
                        ?: values[3].takeUnless { it == "0" || it.isEmpty() } ?: peerUid
                    val senderUin = values[5].takeUnless { it == "0" }.orEmpty()
                    val profile = names.person(senderUid, senderUin)
                    val member = if (chatType == 2) names.member(peerUin, profile.uid.ifBlank { senderUid }, senderUin) else QqPerson("", "", "")
                    val canonicalSender = profile.uid.ifBlank { member.uid.ifBlank { senderUid.ifBlank { "uin:$senderUin" } } }
                    if (canonicalSender.isBlank() || canonicalSender == "uin:") { skipped++; continue }
                    val senderKey = opaqueKey(account.key, "sender", canonicalSender)
                    val conversationId = if (chatType == 2) peerUin else names.person(peerUid, peerUin).uid.ifBlank { peerUid.ifBlank { "uin:$peerUin" } }
                    val conversationKey = opaqueKey(account.key, "conversation", chatType.toString(), conversationId)
                    val conversationName = if (chatType == 2) names.group(peerUin) else names.person(peerUid, peerUin).displayName
                    val historicalCard = cleanHistoricalName(values[7], senderUin)
                    val historicalNickname = cleanHistoricalName(values[8], senderUin)
                    val memberName = historicalCard.ifBlank { member.card }
                    val senderName = profile.displayName.ifBlank { historicalNickname.ifBlank { member.nickname.ifBlank { memberName } } }
                    if (senderName.isNotBlank()) senderNames[senderKey] = senderName
                    if (record.pictures.isNotEmpty() && conversationName.isNotBlank()) conversationNames[conversationKey] = conversationName
                    record.pictures.forEachIndexed { index, picture ->
                        val id = opaqueKey(account.key, table, msgId, picture.elementId.ifBlank { index.toString() }, picture.md5s.joinToString(","))
                        pending += toPending(QqOriginEntity(id, account.key, conversationKey, conversationName, chatType,
                            senderKey, senderName, memberName, record.sentAt * 1000L, picture.md5s.first(),
                            readStart, picture.picType, picture.picSubType, picture.original), picture, chatpicRoot)
                        pictures++
                    }
                    // 合并转发统一进入虚拟分类。所有层的图片使用最外层转发者，不借用内层人物或原群。
                    record.forwardedPictures.forEach { forwarded ->
                        val picture = forwarded.picture
                        val id = opaqueKey(account.key, table, msgId, "forwarded", forwarded.identity, picture.md5s.joinToString(","))
                        pending += toPending(QqOriginEntity(id, account.key, QqForwardSource.KEY, QqForwardSource.NAME, QqForwardSource.CHAT_TYPE,
                            senderKey, senderName, "", record.sentAt * 1000L, picture.md5s.first(),
                            readStart, picture.picType, picture.picSubType, picture.original), picture, chatpicRoot)
                        pictures++
                    }
                    if (pending.size >= FLUSH_SIZE) flush()
                }
            }
            skipped += stats.skipped
            unreadable += stats.unreadable
            if (stats.incompleteTable) incompleteTables++
            // 读完整的表推进水位（没有新消息时沿用原来的）；读不了的坏行下次从头读也一样读不了，不拦着
            if (!stats.incompleteTable) marks[table] = maxOf(stats.maxRowid, after ?: 0)
            Timber.i("QQ copied message scan: table=%s from=%s inspected=%d skipped=%d unreadable=%d incomplete=%s ms=%d",
                table, if (after == null) "start" else "mark", stats.inspected, stats.skipped, stats.unreadable,
                stats.incompleteTable, System.currentTimeMillis() - started)
        }
        flush()
        // 更新名称时不丢弃旧消息来源；QQ 自身清理历史记录后仍可筛选已经关联的本地图片。
        val renamed = mutableSetOf<Pair<Int, String>>()
        keepingClean {
            conversationNames.forEach { (key, name) -> if (dao.updateConversationName(account.key, key, name) > 0) renamed += 0 to key }
            senderNames.forEach { (key, name) -> if (dao.updateSenderName(account.key, key, name) > 0) renamed += 1 to key }
            dao.putAccount(QqSourceAccountEntity(account.key, displayName, System.currentTimeMillis(), dao.originCount(account.key)))
        }
        onProgress(processed, 0)
        // 诊断：图片段字段分布（只有字段号与小数值），用来核对表情子类型等字段
        fieldStats.lines().forEach { Timber.i(it) }
        QqImportResult(pictures, skipped, unreadable, incompleteTables, marks, renamed)
    }

    /**
     * 在一个事务里执行 [block]：其中的改名、改账号名称会让触发器把来源统计标脏，原本不脏时撤掉
     * （名称由读取后的增量更新重算，计数不受影响），原本就脏的不动（等整体重建）。
     */
    private suspend fun <T> keepingClean(block: suspend () -> T): T = db.withTransaction {
        val dao = db.qqSourceDao()
        val clean = !dao.cacheDirty()
        block().also { if (clean) dao.markClean() }
    }

    private companion object {
        /** 攒够这么多条来源提交一次 */
        const val FLUSH_SIZE = 2_000

        /** 单条语句的参数个数（旧系统的 SQLite 最多 999 个） */
        const val SQL_CHUNK = 400
    }
}

private fun cleanHistoricalName(name: String, uin: String): String {
    if (uin.isBlank()) return name
    if (name == uin) return ""
    // 旧 QQ 迁入的群名片可能在昵称后自动附上 QQ 号。
    return name.removeSuffix("($uin)").trim()
}

/** 名称按图片消息涉及的身份按需查询；不读取个人签名、手机号或其他资料。 */
private class QqNames(private val groups: SQLiteDatabase?, private val profiles: SQLiteDatabase?) {
    private val groupColumns = groups?.let { mapOf("group_list" to it.columns("group_list"), "group_detail_info_ver1" to it.columns("group_detail_info_ver1"), "group_member3" to it.columns("group_member3")) }.orEmpty()
    private val profileColumns = profiles?.columns("profile_info_v6").orEmpty()
    private val people = mutableMapOf<Pair<String, String>, QqPerson>()
    private val members = mutableMapOf<Triple<String, String, String>, QqPerson>()
    private val groupNames = mutableMapOf<String, String>()

    fun person(uid: String, uin: String): QqPerson = people.getOrPut(uid to uin) {
        if (profiles == null || !profileColumns.containsAll(listOf("1000", "20002"))) return@getOrPut QqPerson(uid.takeIf { it.startsWith("u_") }.orEmpty(), "", "")
        val fields = "[1000],[20002]," + if ("20009" in profileColumns) "[20009]" else "NULL"
        fun query(condition: String, value: String): QqPerson? = profiles.rawQuery("SELECT $fields FROM profile_info_v6 WHERE $condition = ? LIMIT 1", arrayOf(value)).use { c ->
            if (c.moveToFirst()) QqPerson(c.text(0), c.text(1), c.text(2)) else null
        }
        if (uid.startsWith("u_")) query("[1000]", uid)?.let { return@getOrPut it }
        if (uin.matches(Regex("[1-9][0-9]*")) && "1002" in profileColumns) query("[1002]", uin)?.let { return@getOrPut it }
        QqPerson(uid.takeIf { it.startsWith("u_") }.orEmpty(), "", "")
    }

    fun member(group: String, uid: String, uin: String): QqPerson = members.getOrPut(Triple(group, uid, uin)) {
        val columns = groupColumns["group_member3"].orEmpty()
        if (groups == null || !columns.containsAll(listOf("60001", "1000", "20002", "64003"))) return@getOrPut QqPerson("", "", "")
        val byUid = uid.startsWith("u_")
        if (!byUid && (uin.isBlank() || "1002" !in columns)) return@getOrPut QqPerson("", "", "")
        val field = if (byUid) "1000" else "1002"
        groups.rawQuery("SELECT [1000],[20002],[64003] FROM group_member3 WHERE [60001]=? AND [$field]=? LIMIT 1", arrayOf(group, if (byUid) uid else uin)).use { c ->
            if (c.moveToFirst()) QqPerson(c.text(0), c.text(1), "", c.text(2)) else QqPerson("", "", "")
        }
    }

    fun group(uin: String): String = groupNames.getOrPut(uin) {
        if (groups == null) return@getOrPut ""
        for (table in listOf("group_list", "group_detail_info_ver1")) {
            if (!groupColumns[table].orEmpty().containsAll(listOf("60001", "60007"))) continue
            groups.rawQuery("SELECT [60007] FROM $table WHERE [60001] = ? LIMIT 1", arrayOf(uin)).use { c ->
                if (c.moveToFirst() && c.text(0).isNotBlank()) return@getOrPut c.text(0)
            }
        }
        ""
    }
}
