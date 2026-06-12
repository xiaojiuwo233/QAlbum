package com.xjw.qalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xjw.qalbum.data.db.entity.DirSnapshotEntity

/** 目录快照 DAO（增量扫描的目录剪枝） */
@Dao
interface DirSnapshotDao {

    /** 全量装载：目录数量级 ~1.6w，常驻内存做 diff 没有压力 */
    @Query("SELECT * FROM dir_snapshots")
    suspend fun listAll(): List<DirSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DirSnapshotEntity>)

    /** 目录从磁盘上消失时删除对应快照（调用方按 SQLite 变量上限分批） */
    @Query("DELETE FROM dir_snapshots WHERE dir_path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)
}
