package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.MatchedMessageDao
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.dao.UserActionDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.ImageTagEntity
import com.xjw.qalbum.data.db.entity.MatchedMessageEntity
import com.xjw.qalbum.data.db.entity.OcrFtsEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.data.db.entity.TagEntity
import com.xjw.qalbum.data.db.entity.UserActionEntity

/**
 * QAlbum 主数据库（Phase 0 版本）
 *
 * - 版本管理：每个 Phase 引入新表/字段时递增 version，并在 Migrations 中实现 SQL 升级
 * - sqlite-vec 向量表（Phase 4）会通过 SQLite 扩展加载，不在 Room schema 中声明
 * - FTS5 OCR 索引（Phase 3）会在升级时通过 `CREATE VIRTUAL TABLE` 创建
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
        MatchedMessageEntity::class,
        UserActionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageDao(): ImageDao
    abstract fun ocrDao(): OcrDao
    abstract fun tagDao(): TagDao
    abstract fun cleanupDao(): CleanupDao
    abstract fun matchedMessageDao(): MatchedMessageDao
    abstract fun userActionDao(): UserActionDao

    companion object {
        const val DB_NAME = "qalbum.db"

        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                // Phase 0 还没有正式数据，重建即可；后续 Phase 会用 Migration 替代
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
