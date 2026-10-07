package com.xjw.qalbum.qq

import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqOriginPathEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.zetetic.database.sqlcipher.SQLiteDatabase
import timber.log.Timber

internal data class QqImportResult(val pictures: Int, val skipped: Int, val unreadable: Int, val incompleteTables: Int)
internal data class QqPerson(val uid: String, val nickname: String, val remark: String, val card: String = "") {
    val displayName: String get() = when {
        nickname.isNotBlank() && remark.isNotBlank() && nickname != remark -> "$nickname ($remark)"
        nickname.isNotBlank() -> nickname
        else -> remark
    }
}

internal class QqSourceImporter(private val db: AppDatabase) {
    suspend fun import(
        snapshot: QqDatabaseSnapshot,
        account: QqAccount,
        accountNumber: Int,
        chatpicRoot: String,
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
        db.withTransaction {
            val displayName = names.person(account.uid, "").displayName.ifBlank { "QQ 账号 $accountNumber" }
            dao.putAccount(QqSourceAccountEntity(account.key, displayName, System.currentTimeMillis(), 0))
            val origins = mutableListOf<QqOriginEntity>()
            val paths = mutableListOf<QqOriginPathEntity>()
            suspend fun flush() {
                if (origins.isEmpty()) return
                dao.insertOrigins(origins)
                dao.insertPaths(paths)
                origins.clear()
                paths.clear()
            }
            for ((table, chatType) in tables) {
                val columns = messages.columns(table)
                val previousProcessed = processed
                val stats = QqMessageReader(messages).forEachBatch(table, columns) { records, batchStats ->
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
                            origins += QqOriginEntity(id, account.key, conversationKey, conversationName, chatType,
                                senderKey, senderName, memberName, record.sentAt * 1000L, picture.md5s.first())
                            QqCachePaths.paths(chatpicRoot, picture).forEach { paths += QqOriginPathEntity(it, id) }
                            pictures++
                        }
                        // 合并转发统一进入虚拟分类。所有层的图片使用最外层转发者，不借用内层人物或原群。
                        record.forwardedPictures.forEach { forwarded ->
                            val picture = forwarded.picture
                            val id = opaqueKey(account.key, table, msgId, "forwarded", forwarded.identity, picture.md5s.joinToString(","))
                            origins += QqOriginEntity(id, account.key, QqForwardSource.KEY, QqForwardSource.NAME, QqForwardSource.CHAT_TYPE,
                                senderKey, senderName, "", record.sentAt * 1000L, picture.md5s.first())
                            QqCachePaths.paths(chatpicRoot, picture).forEach { paths += QqOriginPathEntity(it, id) }
                            pictures++
                        }
                        if (origins.size >= 400) flush()
                    }
                }
                skipped += stats.skipped
                unreadable += stats.unreadable
                if (stats.incompleteTable) incompleteTables++
                Timber.i("QQ copied message scan: table=%s inspected=%d skipped=%d unreadable=%d incomplete=%s",
                    table, stats.inspected, stats.skipped, stats.unreadable, stats.incompleteTable)
            }
            flush()
            // 更新名称时不丢弃旧消息来源；QQ 自身清理历史记录后仍可筛选已经关联的本地图片。
            conversationNames.forEach { (key, name) -> dao.updateConversationName(account.key, key, name) }
            senderNames.forEach { (key, name) -> dao.updateSenderName(account.key, key, name) }
            dao.putAccount(QqSourceAccountEntity(account.key, displayName, System.currentTimeMillis(), pictures))
            dao.reconcileLinks()
        }
        onProgress(processed, 0)
        QqImportResult(pictures, skipped, unreadable, incompleteTables)
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
