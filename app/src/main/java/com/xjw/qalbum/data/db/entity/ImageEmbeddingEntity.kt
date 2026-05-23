package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 图片语义 embedding（Phase 4）
 *
 * 原计划用 sqlite-vec 扩展存储 [FLOAT[N]] 向量，但 sqlite-vec 需要预编译 .so 集成成本较高。
 * 这里采用更简洁的方案：
 * - 把向量序列化为 FloatArray → ByteArray（小端序）存到 BLOB
 * - 启动 / 首次搜索时一次性加载到内存（[com.xjw.qalbum.service.ai.SemanticIndex]）
 * - 暴力 cosine 相似度搜索；10w 条 × 1536 维向量约 600MB 内存，可控
 *
 * 这种方案对一般用户够用；超大规模再切到 sqlite-vec 是后续优化。
 */
@Entity(
    tableName = "image_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["image_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ImageEmbeddingEntity(
    @PrimaryKey
    @ColumnInfo(name = "image_id") val imageId: Long,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "dim") val dim: Int,
    /** Float32 小端序序列化的向量数据 */
    @ColumnInfo(name = "vector", typeAffinity = androidx.room.ColumnInfo.BLOB) val vector: ByteArray,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageEmbeddingEntity) return false
        return imageId == other.imageId && model == other.model && dim == other.dim
    }
    override fun hashCode(): Int {
        var result = imageId.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + dim
        return result
    }
}
