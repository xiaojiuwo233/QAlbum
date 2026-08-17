package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 图片语义 embedding（Phase 4）
 *
 * 原计划用 sqlite-vec 扩展存储 [FLOAT[N]] 向量，但 sqlite-vec 需要预编译 .so 集成成本较高。
 * 这里采用更简洁的方案：把向量序列化为 FloatArray → ByteArray（小端序）存到 BLOB，
 * 搜索时由 [com.xjw.qalbum.service.ai.SemanticIndex] 装载做暴力 cosine。
 *
 * 内存红线：1536 维 × 4B ≈ 6KB/条，Android 应用堆普遍 256MB（largeHeap 512MB），
 * **全量常驻内存的上限约 3-4w 条**——5w 条起就有必崩风险，绝不能按「全表进堆」设计。
 * 索引侧必须设按可用堆折算的硬上限，超限降级为 [EmbeddingDao.listPaged] 分页扫 DB 的
 * 流式 top-K；判「是否已嵌入」走 [EmbeddingDao.exists] 主键单查，不为判存在触发全量装载。
 * 大规模的长期方案仍是 sqlite-vec / 量化（float16/int8 省 50-75%）。
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
