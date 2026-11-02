package com.xjw.qalbum

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.CodedOutputStream
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QqOriginEntity
import com.xjw.qalbum.data.db.entity.QqSourceAccountEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.qq.QqCachePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.rules.ExternalResource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

// 设备测试共用的数据与工具（单元测试另有一份，见 test 下的同名文件）

/** QQ 默认的图片缓存目录（只进数据库、不读写文件的路径用它） */
const val CHATPIC = SettingsRepository.DEFAULT_CHATPIC_PATH

/** 测试来源都记在这个账号下 */
const val ACCOUNT = "account"

val appContext: Context get() = ApplicationProvider.getApplicationContext()

/** 第 [n] 张测试图的 MD5 */
fun md5(n: Int): String = "%032x".format(n)

/** 一张图在 chatimg、chatraw、chatthumb、chatthumb（_hd）下的缓存路径 */
fun familyPaths(md5: String, root: String = CHATPIC): List<String> = QqCachePaths.relativePaths(md5).map { "$root/$it" }

/** 一行图片记录：文件名、目录与家族键按路径推出，缩略图默认是 thumbnail_trash，首次与最近见到的时间同修改时间 */
fun imageRow(
    path: String,
    sourceType: String,
    size: Long = 100,
    mtime: Long = 1_000,
    status: String = if (sourceType == "chatthumb") "thumbnail_trash" else "active",
    width: Int = 1,
    height: Int = 1,
    mime: String = "image/jpeg",
    animated: Boolean = false,
    archived: Boolean = false,
    optimized: Int = 0,
    variantGroup: Long? = QqCachePaths.familyKey(path),
) = ImageEntity(
    path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'), sourceType = sourceType,
    cacheKey = path.substringAfterLast('/').removePrefix("Cache_"), variantGroup = variantGroup, size = size, mtime = mtime,
    firstSeen = mtime, lastSeen = mtime, mime = mime, width = width, height = height, isAnimated = animated, status = status,
    isArchived = archived, optimized = optimized,
)

/** 一条聊天来源（消息）：[sender] 在会话 [conversation] 里发的 [md5] 那张图，按家族键关联 */
fun qqOrigin(
    id: String,
    md5: String,
    conversation: String = "group",
    sender: String = "sender",
    time: Long = 1_000,
    chatType: Int = 2,
    senderName: String = sender,
    conversationName: String = conversation,
    memberName: String = "",
) = QqOriginEntity(id, ACCOUNT, conversation, conversationName, chatType, sender, senderName, memberName, time, md5, family = QqCachePaths.md5Family(md5))

/** 写入来源（账号不存在时先建） */
suspend fun AppDatabase.addOrigins(vararg origins: QqOriginEntity) {
    qqSourceDao().putAccount(QqSourceAccountEntity(ACCOUNT, "账号", 1_000, 0))
    qqSourceDao().insertOrigins(origins.toList())
}

fun AppDatabase.exec(sql: String, vararg args: Any) = openHelper.writableDatabase.execSQL(sql, args)

/** 查询结果第一行第一列 */
fun AppDatabase.long(sql: String, vararg args: Any): Long = query(sql, args).use { it.moveToFirst(); it.getLong(0) }

fun AppDatabase.int(sql: String, vararg args: Any): Int = long(sql, *args).toInt()

/** 查询结果的第一列 */
fun AppDatabase.longs(sql: String, vararg args: Any): List<Long> = query(sql, args).use { c ->
    buildList { while (c.moveToNext()) add(c.getLong(0)) }
}

/** 每个测试一个空数据库（与正式库同样的表和触发器），测完关掉；给了 [name] 时建在磁盘上，测完删掉 */
class TestDatabase(private val name: String? = null) : ExternalResource() {
    lateinit var db: AppDatabase
        private set

    override fun before() {
        val builder = if (name == null) {
            Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
        } else {
            appContext.deleteDatabase(name)
            Room.databaseBuilder(appContext, AppDatabase::class.java, name)
        }
        db = builder.addCallback(AppDatabase.creationCallback).build()
    }

    override fun after() {
        db.close()
        name?.let(appContext::deleteDatabase)
    }
}

/** 测试用的设置：DataStore 存在临时文件里，测完删掉 */
class TestSettings : ExternalResource() {
    /** DataStore 所在的协程范围，测完取消；测试里需要「应用级」范围的也用它 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(appContext.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
    val repository = SettingsRepository(appContext, scope, PreferenceDataStoreFactory.create(scope = scope) { file })

    override fun after() {
        scope.cancel()
        file.delete()
    }
}

/** 纯色 JPEG */
fun solidJpeg(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().also { bitmap.recycle() }
}

/** protobuf 编码（造 QQ 消息内容用） */
fun protobuf(block: (CodedOutputStream) -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    val out = CodedOutputStream.newInstance(bytes)
    block(out)
    out.flush()
    return bytes.toByteArray()
}
