package com.xjw.qalbum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xjw.qalbum.data.db.dao.CleanupDao
import com.xjw.qalbum.data.db.dao.EmbeddingDao
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.MatchedMessageDao
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.dao.UserActionDao
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.CleanupCandidateEntity
import com.xjw.qalbum.data.db.entity.ImageEmbeddingEntity
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
        ImageEmbeddingEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageDao(): ImageDao
    abstract fun ocrDao(): OcrDao
    abstract fun tagDao(): TagDao
    abstract fun cleanupDao(): CleanupDao
    abstract fun matchedMessageDao(): MatchedMessageDao
    abstract fun userActionDao(): UserActionDao
    abstract fun embeddingDao(): EmbeddingDao

    companion object {
        const val DB_NAME = "qalbum.db"

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

        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(MIGRATION_4_5)
                // 已注册的版本走 Migration 保数据；只有遇到未覆盖的版本跳变才回退重建兜底
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
