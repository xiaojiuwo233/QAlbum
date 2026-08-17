package com.xjw.qalbum.service.ai

import com.xjw.qalbum.data.db.dao.EmbeddingDao
import com.xjw.qalbum.data.db.entity.ImageEmbeddingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 图片向量检索：以 DB（image_embeddings）为唯一数据源，不在内存常驻任何向量。
 *
 * - 1536 维 FloatArray 约 6KB/条，全量常驻内存在几万条时就会逼近应用堆上限，
 *   200 万图规模下必炸。检索改为 keyset 分页流式打分：逐批读 BLOB、直接在字节
 *   缓冲上算 cosine（不物化 FloatArray），用 k 容量原生小顶堆收 Top-K，
 *   工作集内存 O(单批)，全程零向量驻留。
 * - 无内存缓存即无失效问题：「重置全部分析」只需清空 image_embeddings 表，
 *   下一次检索/判存自然读到 DB 最新状态；写入仅落 DB，取消/失败不会留下半套状态。
 */
class SemanticIndex(private val dao: EmbeddingDao) {

    /** 写入/替换向量：仅落 DB（REPLACE），无内存副本。 */
    suspend fun upsert(imageId: Long, model: String, vec: FloatArray) {
        dao.upsert(
            ImageEmbeddingEntity(
                imageId = imageId,
                model = model,
                dim = vec.size,
                vector = floatsToBytes(vec),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 是否已有向量：主键 EXISTS 单查，冷启动/「重置全部分析」后立即反映 DB 真实状态。 */
    suspend fun hasEmbedding(imageId: Long): Boolean = dao.exists(imageId)

    /** 删除向量（图片永久删除时级联调用），幂等。 */
    suspend fun remove(imageId: Long) = dao.deleteByImageId(imageId)

    suspend fun size(): Int = dao.count()

    /**
     * Top-K 相似搜索；返回 (imageId, 余弦相似度)，按相似度降序。
     *
     * 打分跑在 Default 线程池，每批之间 ensureActive 响应取消（搜索防抖 cancel 生效）；
     * 点积直接在小端字节缓冲上进行，避免每行 ~6KB 的 FloatArray 分配抖动。
     */
    suspend fun searchTopK(query: FloatArray, k: Int = 50): List<Pair<Long, Float>> =
        withContext(Dispatchers.Default) {
            if (query.isEmpty() || k <= 0) return@withContext emptyList()
            val qNorm = l2norm(query)
            if (qNorm == 0f) return@withContext emptyList()

            val heap = TopKHeap(k)
            var afterId = 0L
            while (true) {
                ensureActive()
                val rows = dao.listPaged(afterId, SEARCH_PAGE)
                if (rows.isEmpty()) break
                afterId = rows.last().imageId
                for (row in rows) {
                    val sim = cosineWithBytes(row.vector, row.dim, query, qNorm)
                    if (!sim.isNaN()) heap.offer(row.imageId, sim)
                }
            }
            heap.drainDescending()
        }

    // ---- 工具 ----

    private fun floatsToBytes(vec: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in vec) buf.putFloat(f)
        return buf.array()
    }

    /** 在 BLOB 字节上单趟算 dot 与行向量自身 norm；维度不符/长度损坏返回 NaN 跳过该行。 */
    private fun cosineWithBytes(bytes: ByteArray, dim: Int, query: FloatArray, qNorm: Float): Float {
        if (dim != query.size || bytes.size != dim * 4) return Float.NaN
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var dot = 0.0
        var norm = 0.0
        for (i in 0 until dim) {
            val v = buf.float
            dot += v * query[i]
            norm += v * v
        }
        if (norm == 0.0) return 0f
        return (dot / (qNorm * sqrt(norm))).toFloat()
    }

    private fun l2norm(vec: FloatArray): Float {
        var s = 0.0
        for (v in vec) s += v * v
        return sqrt(s).toFloat()
    }

    /**
     * 固定容量小顶堆（score/id 平行原生数组，零装箱）：堆顶是当前第 k 名，
     * 新分数只需与堆顶比较，整趟流式扫描 O(n log k)，无全量排序无 Pair 垃圾。
     */
    private class TopKHeap(k: Int) {
        private val scores = FloatArray(k)
        private val ids = LongArray(k)
        private val cap = k
        private var size = 0

        fun offer(id: Long, score: Float) {
            if (size < cap) {
                var i = size++
                scores[i] = score
                ids[i] = id
                while (i > 0) {
                    val p = (i - 1) / 2
                    if (scores[p] <= scores[i]) break
                    swap(i, p)
                    i = p
                }
            } else if (score > scores[0]) {
                scores[0] = score
                ids[0] = id
                siftDown()
            }
        }

        /** 逐次弹出堆顶（升序）再整体反转得到降序结果；仅在收尾时装箱 ≤k 个 Pair。 */
        fun drainDescending(): List<Pair<Long, Float>> {
            val out = ArrayList<Pair<Long, Float>>(size)
            while (size > 0) {
                out.add(ids[0] to scores[0])
                size--
                scores[0] = scores[size]
                ids[0] = ids[size]
                siftDown()
            }
            out.reverse()
            return out
        }

        private fun siftDown() {
            var i = 0
            while (true) {
                val l = i * 2 + 1
                if (l >= size) break
                val r = l + 1
                val m = if (r < size && scores[r] < scores[l]) r else l
                if (scores[i] <= scores[m]) break
                swap(i, m)
                i = m
            }
        }

        private fun swap(a: Int, b: Int) {
            val s = scores[a]; scores[a] = scores[b]; scores[b] = s
            val t = ids[a]; ids[a] = ids[b]; ids[b] = t
        }
    }

    private companion object {
        /**
         * 流式打分每批行数：单行 ~6KB（1536 维）时一批约 30MB 瞬时工作集，打完即可回收；
         * 行内 BLOB 远小于 CursorWindow 2MB 单行上限，多行跨窗口由 Cursor 自动翻页。
         */
        private const val SEARCH_PAGE = 5000
    }
}
