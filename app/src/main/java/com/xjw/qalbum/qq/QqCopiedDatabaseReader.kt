package com.xjw.qalbum.qq

import android.database.Cursor
import android.os.Process
import android.system.Os
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import net.zetetic.database.DatabaseErrorHandler
import timber.log.Timber

/** 构造器只接受内部快照类型，防止把 QQ 原库路径误传给 SQLCipher。 */
internal class QqCopiedDatabaseReader(snapshot: QqDatabaseSnapshot, account: QqAccount) : AutoCloseable {
    private val opened = mutableMapOf<String, SQLiteDatabase>()
    private val snapshotDirectory = snapshot.directory.canonicalFile
    private val databases = snapshot.databases
    private val uid = account.uid

    init {
        require(Process.myUid() != 0 && Os.stat(snapshotDirectory.path).st_uid == Process.myUid()) {
            "SQLCipher 只能读取 QAlbum 自己创建的副本"
        }
        require(snapshotDirectory.parentFile?.name == "qq-source-snapshots")
        loadLibrary()
    }

    fun get(name: String): SQLiteDatabase? {
        if (name in opened) return opened[name]
        val copy = databases[name] ?: return null
        require(copy.file.canonicalFile.parentFile == snapshotDirectory && copy.file.isFile)
        require(Os.stat(copy.file.path).st_uid == Process.myUid()) { "数据库副本不属于 QAlbum" }
        val key = copy.header.password(uid)
        val hook = object : SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) = Unit
            override fun postKey(connection: SQLiteConnection) {
                listOf(
                    "PRAGMA cipher_page_size = 4096",
                    "PRAGMA kdf_iter = 4000",
                    "PRAGMA cipher_hmac_algorithm = ${copy.header.hmac}",
                    "PRAGMA cipher_kdf_algorithm = ${copy.header.kdf}",
                    "PRAGMA query_only = ON",
                    "PRAGMA temp_store = MEMORY",
                ).forEach { sql -> connection.execute(sql, emptyArray(), null) }
            }
        }
        val db = try {
            // 默认错误处理器会删除损坏库。副本仍可能含可读记录，交给批次读取器处理，
            // 所有副本统一在 snapshot.close() 清理；绝不触及 QQ 原库。
            val errorHandler = DatabaseErrorHandler { _, error ->
                Timber.w("QQ copied database read error: database=%s type=%s", name, error.javaClass.simpleName)
            }
            SQLiteDatabase.openDatabase(copy.file.path, key, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS, errorHandler, hook)
        } finally {
            key.fill(0)
        }
        try {
            db.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>()).use { check(it.moveToFirst()) }
        } catch (t: Throwable) {
            db.close()
            error("QQ 数据库副本无法解密，当前版本可能尚不兼容")
        }
        opened[name] = db
        Timber.i("QQ snapshot opened: database=%s hmac=%s kdf=%s wal=%s", name,
            copy.header.hmac, copy.header.kdf, java.io.File(copy.file.path + "-wal").exists())
        return db
    }

    override fun close() {
        opened.values.forEach { it.close() }
        opened.clear()
    }

    companion object {
        @Synchronized private fun loadLibrary() {
            System.loadLibrary("sqlcipher")
            // SQLCipher 默认会记录路径/SQL；来源读取不把账号或消息信息写入日志。
            Logger.setTarget(NoopTarget())
        }
    }
}

internal fun SQLiteDatabase.columns(table: String): Set<String> = rawQuery("PRAGMA table_info(\"$table\")", emptyArray<String>()).use { cursor ->
    buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
}

internal fun Cursor.text(index: Int): String = if (isNull(index)) "" else getString(index).orEmpty()
