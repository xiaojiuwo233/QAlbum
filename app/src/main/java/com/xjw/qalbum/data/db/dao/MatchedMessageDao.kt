package com.xjw.qalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xjw.qalbum.data.db.entity.MatchedMessageEntity

@Dao
interface MatchedMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MatchedMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MatchedMessageEntity>): List<Long>

    @Query("SELECT * FROM matched_messages WHERE image_id = :imageId ORDER BY confidence DESC")
    suspend fun getByImageId(imageId: Long): List<MatchedMessageEntity>

    @Query("SELECT * FROM matched_messages WHERE peer_uid = :peerUid ORDER BY msg_time DESC")
    suspend fun getByPeer(peerUid: String): List<MatchedMessageEntity>
}
