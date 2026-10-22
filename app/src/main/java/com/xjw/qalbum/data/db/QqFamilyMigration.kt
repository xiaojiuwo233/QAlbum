package com.xjw.qalbum.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xjw.qalbum.qq.QqCachePaths

/**
 * v26 → v27：聊天来源改按家族键关联图片，不再逐条存路径。
 *
 * 以前每条消息的图片都记下 4 条完整路径（chatimg / chatraw / chatthumb / _hd，几十万条消息要两三百万行，连同两个索引
 * 占数据库一半以上），再按路径关联图片。这些路径都由图片的 MD5 推出，同一张图的各个缓存文件（含带后缀的别名、
 * Temp 转码图）共用一个家族键（images.variant_group），所以改为：
 * 1. 消息记下第一个 MD5 的家族键（qq_origins.family），其余 MD5 的家族键记进 qq_origin_families；
 * 2. 关联触发器按家族键关联，来源统计随新增的关联增量更新（见 QqSourceStatsSql.createTriggers）。
 *
 * 升级时只做这两步（写入量约等于来源表本身）。旧路径表里的几百万行由「设置 → 存储空间 → 精简数据库」删除
 * （见 [DatabaseSlimmer]）：一次删掉要在提交时搬动几十万页、临时占用一两 GB，不能放在启动时的升级里。
 * 删除之前它们照常参与关联（按路径），已有的关联与统计不变。
 */
internal object QqFamilyMigration {

    fun migrate(db: SupportSQLiteDatabase) {
        (AppDatabase.LINK_TRIGGERS + QqSourceStatsSql.replacedInV27).forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
        db.execSQL("ALTER TABLE `qq_origins` ADD COLUMN `family` INTEGER")
        db.execSQL(CREATE_FAMILIES)
        db.execSQL(FAMILIES_INDEX)
        fillOriginFamilies(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_qq_origins_family` ON `qq_origins` (`family`)")
        AppDatabase.createQqSourceLinkTriggers(db)
        QqSourceStatsSql.createTriggers(db)
    }

    /** 每条消息按 MD5 算出家族键 */
    private fun fillOriginFamilies(db: SupportSQLiteDatabase) {
        val total = db.query("SELECT COUNT(*) FROM qq_origins").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val update = db.compileStatement("UPDATE qq_origins SET family = ? WHERE rowid = ?")
        var after = 0L
        var done = 0L
        while (true) {
            DatabaseUpgrade.report("整理聊天来源", done, total)
            val rows = db.query(SimpleSQLiteQuery("SELECT rowid, md5 FROM qq_origins WHERE rowid > ? ORDER BY rowid LIMIT $PAGE", arrayOf(after))).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
            }
            if (rows.isEmpty()) break
            for ((rowid, md5) in rows) {
                val family = QqCachePaths.md5Family(md5) ?: continue
                update.bindLong(1, family)
                update.bindLong(2, rowid)
                update.executeUpdateDelete()
                update.clearBindings()
            }
            after = rows.last().first
            done += rows.size
        }
    }

    private const val PAGE = 5_000

    // 与 Room 导出的 schemas/27.json 一致（迁移后 Room 会逐表校验列、索引与外键）
    private const val CREATE_FAMILIES = "CREATE TABLE IF NOT EXISTS `qq_origin_families` (`family` INTEGER NOT NULL, " +
        "`origin_id` TEXT NOT NULL, PRIMARY KEY(`family`, `origin_id`), FOREIGN KEY(`origin_id`) REFERENCES `qq_origins`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE )"
    private const val FAMILIES_INDEX = "CREATE INDEX IF NOT EXISTS `index_qq_origin_families_origin_id` ON `qq_origin_families` (`origin_id`)"

    const val CREATE_PATHS = "CREATE TABLE IF NOT EXISTS `qq_origin_paths` (`path` TEXT NOT NULL, `origin_id` TEXT NOT NULL, " +
        "PRIMARY KEY(`path`, `origin_id`), FOREIGN KEY(`origin_id`) REFERENCES `qq_origins`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    const val PATHS_INDEX = "CREATE INDEX IF NOT EXISTS `index_qq_origin_paths_origin_id` ON `qq_origin_paths` (`origin_id`)"
}
