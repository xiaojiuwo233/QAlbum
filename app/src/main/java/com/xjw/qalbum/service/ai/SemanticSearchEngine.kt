package com.xjw.qalbum.service.ai

import com.xjw.qalbum.access.AccessException
import com.xjw.qalbum.access.ImageBytesReader
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.remote.ChatMessage
import com.xjw.qalbum.data.remote.OpenAiCompatClient
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * AI 语义搜索引擎
 *
 * 流程：
 * 1. 将用户查询文本通过 Embedding API 转 1536 维向量
 * 2. 通过 [SemanticIndex] 流式 cosine 检索 Top-K
 * 3. 通过 ImageDao 拉对应 ImageEntity 列表返回
 *
 * 对应"以图搜图"：先用 vision 模型给图片生成描述，再做 text embedding（降级方案）。
 */
class SemanticSearchEngine(
    private val aiClient: OpenAiCompatClient,
    private val semanticIndex: SemanticIndex,
    private val imageDao: ImageDao,
    private val settings: SettingsRepository,
) {

    /** 按文本搜索 */
    suspend fun searchByText(query: String, topK: Int = 50): List<SemanticHit> {
        if (query.isBlank()) return emptyList()
        val cfg = settings.aiConnection.first()
        check(cfg.enabled && cfg.endpoint.isNotBlank()) { "AI 服务未配置" }
        val emb = aiClient.embedding(query, cfg.embeddingModel, cfg.endpoint)
            ?: error("语义服务调用失败，请检查模型、连接及公网授权")
        val pairs = semanticIndex.searchTopK(emb, topK, SemanticDocument.modelKey(cfg.endpoint, cfg.embeddingModel))
        return resolveHits(pairs, topK)
    }

    /** 以图搜图：用 vision 描述 + text embedding 降级方案 */
    suspend fun searchByImage(reader: ImageBytesReader, imageId: Long, topK: Int = 50): List<SemanticHit> {
        val image = imageDao.getById(imageId) ?: return emptyList()
        val cfg = settings.aiConnection.first()
        val described = describeImage(reader, image) ?: return emptyList()
        val emb = aiClient.embedding(described, cfg.embeddingModel, cfg.endpoint) ?: return emptyList()
        val pairs = semanticIndex.searchTopK(emb, topK + 1, SemanticDocument.modelKey(cfg.endpoint, cfg.embeddingModel))
            .filter { it.first != imageId }   // 排除自己
        return resolveHits(pairs, topK)
    }

    /**
     * 命中 id 回表并按可见性过滤后凑满 topK。
     *
     * 超采样 [OVERSAMPLE] 倍是因为向量表可能残留已删除/回收图片的死 id：
     * getByIds 只回 active 主图，被过滤掉的名额由多取的候选按相似度顺位回补，
     * 避免大规模清理后 Top-K 结果被死 id 静默削减甚至清零。
     */
    private suspend fun resolveHits(pairs: List<Pair<Long, Float>>, topK: Int): List<SemanticHit> {
        if (pairs.isEmpty()) return emptyList()
        val items = imageDao.getByIds(pairs.map { it.first }).associateBy { it.id }
        return pairs.mapNotNull { (id, sim) -> items[id]?.let { SemanticHit(it, sim) } }
            .take(topK)
    }

    private suspend fun describeImage(reader: ImageBytesReader, image: ImageEntity): String? {
        // 用 vision 模型给图片生成 50 词中文描述
        val base64 = try {
            AiTaggingShared.encodeImageBase64(reader, image.path)
        } catch (ae: AccessException) {
            // 读取基础设施失败：本次以图搜图放弃即可，不涉及任何落库
            Timber.w(ae, "describeImage: read infra failed for ${image.path}")
            null
        } ?: return null
        val resp = aiClient.chatCompletion(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    text = "用 30~60 个中文词描述这张图片的内容、场景、物体、文字信息。只输出描述词，用逗号分隔。",
                    imageBase64 = base64,
                ),
            ),
            // maxTokens 留空：由设置里的「最大 token」决定（思考模型推理会先吃掉大量 token，否则描述为空）
            temperature = 0.2f,
        ) ?: return null
        return resp.content
    }

}

data class SemanticHit(val image: ImageEntity, val similarity: Float)
