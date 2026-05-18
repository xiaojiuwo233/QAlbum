package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 图片索引表 —— QAlbum 的核心实体。
 *
 * 设计要点：
 * - `cache_key`：从 `Cache_{hash}` 文件名中提取的 hash 部分，
 *   同一张图的不同分辨率（chatimg / chatraw / chatthumb）共享该值。
 * - `variant_group` / `is_variant_primary`：基于 cache_key 自动建立的"同源变体组"，
 *   UI 默认以主图代表整组展示。
 * - `duplicate_group_id` / `is_duplicate_best`：基于 xxHash + pHash 检测出的"重复组"，
 *   与变体组是两个独立维度。
 */
@Entity(
    tableName = "images",
    indices = [
        Index("xxhash"),
        Index("phash"),
        Index("cache_key"),
        Index("variant_group"),
        Index("status"),
        Index("mtime"),
        Index("delete_score"),
        Index("info_score"),
        Index("duplicate_group_id"),
    ]
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // --- 文件基础信息 ---
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "filename") val filename: String,
    @ColumnInfo(name = "parent_dir") val parentDir: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "variant_group") val variantGroup: Long? = null,
    @ColumnInfo(name = "is_variant_primary") val isVariantPrimary: Boolean = false,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "mtime") val mtime: Long,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,

    // --- 图片元信息 ---
    @ColumnInfo(name = "mime") val mime: String,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    @ColumnInfo(name = "is_animated") val isAnimated: Boolean = false,
    @ColumnInfo(name = "is_corrupt") val isCorrupt: Boolean = false,

    // --- Hash ---
    @ColumnInfo(name = "xxhash") val xxhash: String? = null,
    @ColumnInfo(name = "phash") val phash: Long? = null,

    // --- 质量评估 ---
    @ColumnInfo(name = "blur_score") val blurScore: Float? = null,
    @ColumnInfo(name = "is_blank") val isBlank: Boolean = false,

    // --- 分析状态 ---
    @ColumnInfo(name = "hash_done") val hashDone: Boolean = false,
    @ColumnInfo(name = "ocr_done") val ocrDone: Boolean = false,
    @ColumnInfo(name = "ai_done") val aiDone: Boolean = false,
    @ColumnInfo(name = "embedding_done") val embeddingDone: Boolean = false,

    // --- 评分 ---
    @ColumnInfo(name = "info_score") val infoScore: Float = 0f,
    @ColumnInfo(name = "delete_score") val deleteScore: Float = 0f,

    // --- 重复组 ---
    @ColumnInfo(name = "duplicate_group_id") val duplicateGroupId: Long? = null,
    @ColumnInfo(name = "is_duplicate_best") val isDuplicateBest: Boolean = false,

    // --- 用户操作状态 ---
    @ColumnInfo(name = "status") val status: String = "active",
    @ColumnInfo(name = "is_protected") val isProtected: Boolean = false,
    @ColumnInfo(name = "user_keep") val userKeep: Boolean? = null,
)
