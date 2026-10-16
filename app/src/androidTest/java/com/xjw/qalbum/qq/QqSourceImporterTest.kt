package com.xjw.qalbum.qq

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.CodedOutputStream
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqScopeIds
import com.xjw.qalbum.data.db.dao.QqScopeSql
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QqAutoArchiveEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.service.cleanup.rules.RuleSql
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 读取聊天来源的全过程：按 QQ 的加密参数造一份 nt_msg.db 只读副本，跑 [QqSourceImporter]，再核对
 * 读取时间（完整读取 / 删消息后重读 / 增量读取）、图片属性、按聊天的两条规则、按时间清理与自动归档。
 */
@RunWith(AndroidJUnit4::class)
class QqSourceImporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
    private val account = QqAccount("account-key", "u_owner")
    private val header = QqCipherHeader("0123456789abcdef", "HMAC_SHA1", "PBKDF2_HMAC_SHA512")
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_760_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = File(context.cacheDir, "importer-test-${UUID.randomUUID()}.preferences_pb")
    private lateinit var db: AppDatabase
    private lateinit var directory: File

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback).build()
        directory = File(context.cacheDir, "qq-source-snapshots/${UUID.randomUUID()}").apply { mkdirs() }
        messages { db ->
            listOf("group_msg_table", "c2c_msg_table").forEach { table ->
                db.execSQL(
                    "CREATE TABLE $table (\"40001\" INTEGER, \"40020\" TEXT, \"40021\" TEXT, \"40027\" INTEGER, \"40030\" INTEGER, " +
                        "\"40033\" INTEGER, \"40050\" INTEGER, \"40090\" TEXT, \"40093\" TEXT, \"40011\" INTEGER, \"40800\" BLOB, \"40900\" BLOB)",
                )
            }
        }
    }

    @After
    fun tearDown() {
        db.close()
        directory.parentFile?.deleteRecursively()
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun fullReadsMarkMessagesAndIncrementalReadsOnlyParseNewOnes() = runBlocking {
        val old = now - 200 * day
        val recent = now - 10 * day
        // 群 1001：Alice 的照片（原图发送）、Bob 发的也在私聊里出现的图、最近的 GIF
        val photo = picture(1)
        val shared = picture(2)
        val gif = picture(3)
        val photoRaw = images(photo, old)
        val sharedRaw = images(shared, old)
        images(gif, recent)
        message("group_msg_table", 1, "u_alice", 1001, old, element(photo, picType = 1000, original = true))
        message("group_msg_table", 2, "u_bob", 1001, old, element(shared, picType = 1000))
        message("c2c_msg_table", 3, "u_carol", 0, old, element(shared, picType = 1000), peer = "u_carol")
        message("group_msg_table", 4, "u_alice", 1001, recent, element(gif, picType = 2000))
        // 纯文字消息：读取时在 SQL 里就被筛掉，不影响结果，但水位照样推进
        message("group_msg_table", 6, "u_bob", 1001, recent, encode { out ->
            out.writeUInt64(45001, 99)
            out.writeInt32(45002, 1)
            out.writeString(45101, "hello")
        })

        val first = now - 2 * day
        val result = import(first)
        assertEquals(4, result.pictures)
        assertEquals(mapOf("group_msg_table" to 4L, "c2c_msg_table" to 1L), result.marks)
        assertEquals(4, scalar("SELECT COUNT(*) FROM qq_origins WHERE seen_at = $first"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM qq_origins WHERE pic_type = 1000 AND sent_original = 1"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM qq_origins WHERE pic_type = 2000"))
        QqSourceStatsBuilder(db).rebuild {}

        // 群聊旧图：只有 Alice 那张（Bob 那张私聊里也有，GIF 是最近的）
        assertEquals(setOf(photoRaw), ids(CleanupRule.GroupOld, 3))
        // 按聊天清理只清 3 个月前的：群里的两张旧图（各有预览图与原图），最近的 GIF 不算
        val group = db.qqSourceDao().facetId(0, conversationKey())!!
        val counts = db.qqSourceDao().queryCounts(
            QqScopeSql.counts(QqScopeIds(conversationId = group), setOf(QqContent.IMAGES), includeShared = true, before = CleanupRule.monthsBefore(now, 3)),
        )
        assertEquals(4, counts.total)
        assertEquals(6, db.qqSourceDao().queryCounts(QqScopeSql.counts(QqScopeIds(conversationId = group), setOf(QqContent.IMAGES), true)).total)

        // QQ 里删掉 Alice 那条消息，再完整读取一次：那张图成了「聊天记录已删除」
        messages { it.execSQL("DELETE FROM group_msg_table WHERE \"40001\" = 1") }
        val second = now - day
        assertEquals(3, import(second).pictures)
        db.qqSourceDao().setCompleteReadAt(second)
        assertEquals(1, scalar("SELECT COUNT(*) FROM qq_origins WHERE seen_at < $second"))
        assertEquals(setOf(photoRaw), ids(CleanupRule.DeletedChats, 1))

        // 增量读取：只解析新消息，旧消息的读取时间不动
        val fresh = picture(5)
        images(fresh, now)
        message("group_msg_table", 5, "u_bob", 1001, now, element(fresh, picType = 1001))
        val third = now
        val incremental = import(third, startAfter = mapOf("group_msg_table" to 4L, "c2c_msg_table" to 1L))
        assertEquals(1, incremental.pictures)
        assertEquals(mapOf("group_msg_table" to 5L, "c2c_msg_table" to 1L), incremental.marks)
        assertEquals(1, scalar("SELECT COUNT(*) FROM qq_origins WHERE seen_at = $third"))
        assertEquals(3, scalar("SELECT COUNT(*) FROM qq_origins WHERE seen_at = $second"))
        assertTrue(sharedRaw !in ids(CleanupRule.DeletedChats, 0))
    }

    @Test
    fun autoArchiveCatchesNewPicturesOfListedChats() = runBlocking {
        val first = picture(1)
        images(first, now - 30 * day)
        message("group_msg_table", 1, "u_alice", 1001, now - 30 * day, element(first, picType = 1000))
        import(now - day)
        QqSourceStatsBuilder(db).rebuild {}
        val sources = db.qqSourceDao()
        sources.addAutoArchive(QqAutoArchiveEntity(conversationKey(), "", "群", now))
        val settings = SettingsRepository(context, scope, PreferenceDataStoreFactory.create(scope = scope) { storeFile })
        val archive = ArchiveManager(db, settings, scope)

        // 这张图的原图与预览图
        assertEquals(2, archive.applyAutoArchive())
        // 新消息里的图读进来后也自动归档；已经归档的不再算
        val second = picture(2)
        images(second, now)
        message("group_msg_table", 2, "u_bob", 1001, now, element(second, picType = 1000))
        import(now, startAfter = mapOf("group_msg_table" to 1L))
        QqSourceStatsBuilder(db).rebuild {}
        assertEquals(2, archive.applyAutoArchive())
        assertEquals(0, scalar("SELECT COUNT(*) FROM images WHERE is_protected = 0"))
    }

    @Test
    fun previewsOfSelfSentPicturesFollowTheirOriginal() = runBlocking {
        val sent = now - 30 * day
        // 自己发的图：消息里记的是原图的 MD5；预览按它自己内容的 MD5 命名，按 MD5 关联不上
        val photo = picture(1)
        val raw = image(QqCachePaths.relativePaths(hex(photo))[1], "chatraw", sent, 1000, 750)
        val previewBytes = ByteArray(4096) { (it * 7).toByte() }
        val previewPath = QqCachePaths.relativePaths(md5Of(previewBytes))[0]
        val preview = image(previewPath, "chatimg", sent + 300, 720, 540)
        // 同一时刻收到的别人的图，和一张文件名与内容对不上的 chatimg：都不能被当成预览
        val received = picture(2)
        image(QqCachePaths.relativePaths(hex(received))[1], "chatraw", sent + 100, 1000, 750)
        val strangerPath = QqCachePaths.relativePaths(hex(picture(3)))[0]
        val stranger = image(strangerPath, "chatimg", sent + 500, 720, 540)
        message("group_msg_table", 1, account.uid, 1001, sent, element(photo, picType = 1000))
        message("group_msg_table", 2, "u_alice", 1001, sent, element(received, picType = 1000))
        val read = now - day
        import(read)

        // 这张图按消息 MD5 命名的预览图（QQ 显示用的）：不算，也不用读
        val shown = image(QqCachePaths.relativePaths(hex(photo))[0], "chatimg", sent + 400, 720, 540)
        val contents = mapOf("$root/$previewPath" to previewBytes, "$root/$strangerPath" to ByteArray(4096) { 1 })
        val opened = mutableListOf<String>()
        val linker = QqSentPreviewLinker(db) { path -> opened += path; contents[path]?.inputStream() }
        assertEquals(1, linker.link(listOf(opaqueKey(account.key, "sender", account.uid))))
        assertTrue("$root/${QqCachePaths.relativePaths(hex(photo))[0]}" !in opened)
        assertEquals(1, scalar("SELECT COUNT(*) FROM image_qq_origins WHERE image_id = $preview"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM image_qq_origins WHERE image_id = $stranger"))
        assertEquals(
            scalar("SELECT COUNT(*) FROM image_qq_origins l JOIN image_qq_origins r ON r.origin_id = l.origin_id WHERE l.image_id = $preview AND r.image_id = $raw"),
            1,
        )
        // 图库里并进原图那一家（不再多出一张），「发送图片缓存」只列它
        assertEquals(scalarLong("SELECT variant_group FROM images WHERE id = $raw"), scalarLong("SELECT variant_group FROM images WHERE id = $preview"))
        assertEquals(1, scalar("SELECT variant_hidden FROM images WHERE id = $preview"))
        assertEquals(setOf(preview), ids(CleanupRule.SentPreviews, 0))
        assertTrue(shown !in ids(CleanupRule.SentPreviews, 0))
        // 消息还在：预览不算「聊天记录已删除」；对不上的那张照算
        db.qqSourceDao().setCompleteReadAt(read)
        val deleted = ids(CleanupRule.DeletedChats, 0)
        assertTrue(preview !in deleted && raw !in deleted)
        assertTrue(stranger in deleted)
        // 再读一次不会重复核对
        opened.clear()
        assertEquals(0, linker.link(listOf(opaqueKey(account.key, "sender", account.uid))))
        assertTrue(opened.isEmpty())
        // 预览被重新入库（家族键按路径重算）后，扫描结束时并回原图那一家
        db.openHelper.writableDatabase.execSQL("UPDATE images SET variant_group = ${QqCachePaths.familyKey("$root/$previewPath")} WHERE id = $preview")
        QqSentPreviewLinker.mergeFamilies(db)
        assertEquals(scalarLong("SELECT variant_group FROM images WHERE id = $raw"), scalarLong("SELECT variant_group FROM images WHERE id = $preview"))
    }

    // ---- 只读副本 ----

    private fun messages(block: (SQLiteDatabase) -> Unit) {
        val key = header.password(account.uid)
        val hook = object : SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) = Unit
            override fun postKey(connection: SQLiteConnection) {
                listOf(
                    "PRAGMA cipher_page_size = 4096",
                    "PRAGMA kdf_iter = 4000",
                    "PRAGMA cipher_hmac_algorithm = ${header.hmac}",
                    "PRAGMA cipher_kdf_algorithm = ${header.kdf}",
                ).forEach { connection.execute(it, emptyArray(), null) }
            }
        }
        SQLiteDatabase.openDatabase(
            File(directory, "nt_msg.db").path, key, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, hook,
        ).use(block)
    }

    private suspend fun import(readStart: Long, startAfter: Map<String, Long> = emptyMap()): QqImportResult {
        val snapshot = QqDatabaseSnapshot(directory, mapOf("nt_msg.db" to QqCopiedDatabase(File(directory, "nt_msg.db"), header)))
        return QqSourceImporter(db).import(snapshot, account, 1, root, readStart, startAfter) { _, _ -> }
    }

    private fun message(table: String, id: Long, sender: String, group: Long, time: Long, vararg elements: ByteArray, peer: String = "") {
        val content = encode { out -> elements.forEach { out.writeByteArray(40800, it) } }
        messages {
            it.execSQL(
                "INSERT INTO $table VALUES (?, ?, ?, 0, ?, 0, ?, '', ?, 2, ?, NULL)",
                arrayOf(id, sender, peer, group, time / 1000, sender.removePrefix("u_"), content),
            )
        }
    }

    // ---- 图片与消息内容 ----

    private fun picture(n: Int) = ByteArray(16) { (n * 31 + it).toByte() }

    private fun hex(md5: ByteArray) = md5.joinToString("") { "%02x".format(it) }

    private fun element(md5: ByteArray, picType: Int, original: Boolean = false) = encode { out ->
        out.writeUInt64(45001, md5[0].toLong() and 0xff)
        out.writeInt32(45002, 2)
        out.writeByteArray(45406, md5)
        out.writeInt32(QqPictureCodec.PIC_TYPE_FIELD, picType)
        if (original) out.writeInt32(QqPictureCodec.ORIGINAL_FIELD, 1)
    }

    private fun encode(block: (CodedOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = CodedOutputStream.newInstance(bytes)
        block(out)
        out.flush()
        return bytes.toByteArray()
    }

    /** 这张图的预览图与原图入库，返回原图（整张图的代表）的 id */
    private suspend fun images(md5: ByteArray, mtime: Long): Long {
        val (img, raw) = QqCachePaths.relativePaths(hex(md5)).map { "$root/$it" }
        val ids = listOf(raw to 2_000_000L, img to 200_000L).map { (path, size) ->
            db.imageDao().insert(
                ImageEntity(
                    path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'),
                    sourceType = if (path == raw) "chatraw" else "chatimg", cacheKey = path.substringAfterLast('/'),
                    variantGroup = QqCachePaths.familyKey(path), size = size, mtime = mtime, firstSeen = mtime, lastSeen = mtime,
                    mime = "image/jpeg", width = 1000, height = 800, isAnimated = false, status = "active",
                ),
            )
        }
        return ids.first()
    }

    /** 单个缓存文件入库，返回 id */
    private suspend fun image(relative: String, type: String, mtime: Long, width: Int, height: Int): Long {
        val path = "$root/$relative"
        return db.imageDao().insert(
            ImageEntity(
                path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'),
                sourceType = type, cacheKey = path.substringAfterLast('/'), variantGroup = QqCachePaths.familyKey(path),
                size = 100_000, mtime = mtime, firstSeen = mtime, lastSeen = mtime,
                mime = "image/jpeg", width = width, height = height, isAnimated = false, status = "active",
            ),
        )
    }

    private fun md5Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun ids(rule: CleanupRule, option: Int): Set<Long> =
        db.cleanupRuleDao().ids(RuleSql.idsAfter(rule.where(option, now), 0, 1_000)).toSet()

    private fun conversationKey() = opaqueKey(account.key, "conversation", "2", "1001")

    private fun scalar(sql: String): Int = db.query(sql, emptyArray()).use { it.moveToFirst(); it.getInt(0) }

    private fun scalarLong(sql: String): Long = db.query(sql, emptyArray()).use { it.moveToFirst(); it.getLong(0) }
}
