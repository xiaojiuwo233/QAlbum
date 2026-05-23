package com.xjw.qalbum.service.ai

import com.xjw.qalbum.data.db.dao.EmbeddingDao
import com.xjw.qalbum.data.db.entity.ImageEmbeddingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 内存中的图片向量索引
 *
 * - 启动后首次访问时从 DB 加载所有向量到 [vectors] 列表
 * - 写入新向量同时更新内存与 DB
 * - 查询用暴力 cosine 相似度（10 万张 × 1536 维约 100ms，可接受）
 *
 * 不持有 Bitmap / FileStream，可以长期常驻 App 进程。
 */
class SemanticIndex(private val dao: EmbeddingDao) {

    private data class Item(val imageId: Long, val vec: FloatArray, val norm: Float)

    private val vectors = ArrayList<Item>()
    private val mutex = Mutex()
    @Volatile private var loaded = false

    suspend fun ensureLoaded() = mutex.withLock {
        if (loaded) return@withLock
        val rows = withContext(Dispatchers.IO) { dao.listAll() }
        vectors.clear()
        for (row in rows) {
            val vec = bytesToFloats(row.vector, row.dim) ?: continue
            vectors.add(Item(row.imageId, vec, l2norm(vec)))
        }
        loaded = true
        Timber.i("SemanticIndex: loaded ${vectors.size} vectors")
    }

    suspend fun upsert(imageId: Long, model: String, vec: FloatArray) {
        mutex.withLock {
            // 替换或追加
            val idx = vectors.indexOfFirst { it.imageId == imageId }
            val norm = l2norm(vec)
            if (idx >= 0) vectors[idx] = Item(imageId, vec, norm)
            else vectors.add(Item(imageId, vec, norm))
        }
        withContext(Dispatchers.IO) {
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
    }

    /** Top-K 相似搜索；返回 (imageId, 余弦相似度) 列表 */
    suspend fun searchTopK(query: FloatArray, k: Int = 50): List<Pair<Long, Float>> {
        ensureLoaded()
        if (query.isEmpty()) return emptyList()
        val qNorm = l2norm(query)
        if (qNorm == 0f) return emptyList()

        return mutex.withLock {
            // 用 IntArray + 同步数组遍历，避免每次创建 Pair 对象
            val n = vectors.size
            val scored = ArrayList<Pair<Long, Float>>(n)
            for (item in vectors) {
                if (item.vec.size != query.size) continue
                val dot = dotProduct(query, item.vec)
                val sim = if (item.norm == 0f) 0f else dot / (qNorm * item.norm)
                scored.add(item.imageId to sim)
            }
            scored.sortByDescending { it.second }
            if (scored.size > k) scored.subList(0, k) else scored
        }
    }

    suspend fun size(): Int = mutex.withLock { vectors.size }

    suspend fun hasEmbedding(imageId: Long): Boolean = mutex.withLock {
        vectors.any { it.imageId == imageId }
    }

    // ---- 工具：FloatArray ↔ ByteArray ----

    private fun floatsToBytes(vec: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in vec) buf.putFloat(f)
        return buf.array()
    }

    private fun bytesToFloats(bytes: ByteArray, dim: Int): FloatArray? {
        if (bytes.size != dim * 4) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(dim)
        for (i in 0 until dim) out[i] = buf.float
        return out
    }

    private fun l2norm(vec: FloatArray): Float {
        var s = 0.0
        for (v in vec) s += v * v
        return sqrt(s).toFloat()
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s.toFloat()
    }
}
