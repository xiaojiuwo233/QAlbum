package com.xjw.qalbum.service.ai

import com.xjw.qalbum.access.FileAccessProvider
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
 * 2. 在内存 [SemanticIndex] 中暴力 cosine 检索 Top-K
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
        if (!settings.aiEnabled.first()) {
            Timber.w("SemanticSearch: AI not enabled")
            return emptyList()
        }
        val emb = aiClient.embedding(query) ?: return emptyList()
        val pairs = semanticIndex.searchTopK(emb, topK)
        if (pairs.isEmpty()) return emptyList()

        val ids = pairs.map { it.first }
        val items = imageDao.getByIds(ids).associateBy { it.id }
        return pairs.mapNotNull { (id, sim) ->
            val item = items[id] ?: return@mapNotNull null
            SemanticHit(item, sim)
        }
    }

    /** 以图搜图：用 vision 描述 + text embedding 降级方案 */
    suspend fun searchByImage(provider: FileAccessProvider, imageId: Long, topK: Int = 50): List<SemanticHit> {
        val image = imageDao.getById(imageId) ?: return emptyList()
        val described = describeImage(provider, image) ?: return emptyList()
        val emb = aiClient.embedding(described) ?: return emptyList()
        val pairs = semanticIndex.searchTopK(emb, topK)
            .filter { it.first != imageId }   // 排除自己
        val items = imageDao.getByIds(pairs.map { it.first }).associateBy { it.id }
        return pairs.mapNotNull { (id, sim) ->
            val item = items[id] ?: return@mapNotNull null
            SemanticHit(item, sim)
        }
    }

    private suspend fun describeImage(provider: FileAccessProvider, image: ImageEntity): String? {
        // 用 vision 模型给图片生成 50 词中文描述
        val base64 = AiTaggingShared.encodeImageBase64(provider, image.path) ?: return null
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
