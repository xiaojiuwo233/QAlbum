package com.xjw.qalbum.service.ai

import com.xjw.qalbum.access.ImageBytesReader
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.TagDao
import com.xjw.qalbum.data.db.entity.ImageTagEntity
import com.xjw.qalbum.data.db.entity.TagEntity
import com.xjw.qalbum.data.remote.ChatMessage
import com.xjw.qalbum.data.remote.OpenAiCompatClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * AI 打标签服务
 *
 * 流程：
 * 1. 读取图片 → 缩放到 ≤ 512px → JPEG 60% → Base64（共用 [AiTaggingShared.encodeImageBase64]）
 * 2. 调用 OpenAI 兼容 chat completion，要求按 JSON 格式返回结构化标签
 * 3. 解析 JSON，写入 tags 字典 + image_tags 关联表 + 在 images.ai_done 上置位
 */
class AiTaggingService(
    private val aiClient: OpenAiCompatClient,
    private val imageDao: ImageDao,
    private val tagDao: TagDao,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun tagImage(reader: ImageBytesReader, imageId: Long): Result {
        val image = imageDao.getById(imageId) ?: return Result.Failed("图片不存在")
        val base64 = AiTaggingShared.encodeImageBase64(reader, image.path)
            ?: return Result.Failed("图片编码失败")

        val resp = aiClient.chatCompletion(
            messages = listOf(
                ChatMessage(role = "system", text = SYSTEM_PROMPT),
                ChatMessage(role = "user", text = "请分析这张图片，按要求 JSON 格式返回。", imageBase64 = base64),
            ),
            // maxTokens 留空：由设置里的「最大 token」决定（思考模型需留足额度，否则 content 为空）
            temperature = 0.1f,
        ) ?: return Result.Failed("AI 调用失败或未配置 endpoint")

        val parsed = parseResponse(resp.content) ?: return Result.Failed("解析 AI 响应失败")

        // 写入标签 + 关联（标签在「标签管理页」可见可批量清理）。
        // 不再因 risk 标签自动收藏：自动收藏改由「设置 → 分析 → 自动收藏」显式控制。
        for (tagName in parsed.contentTags + parsed.riskTags) {
            if (tagName == com.xjw.qalbum.data.db.entity.TAG_USER_KEEP) continue // 「用户保留」仅用户手动添加
            val tag = ensureTag(name = tagName, type = if (tagName in parsed.riskTags) "risk" else "ai")
            tagDao.linkTag(ImageTagEntity(imageId = imageId, tagId = tag.id, source = "ai"))
        }

        return Result.Success(parsed)
    }

    private suspend fun ensureTag(name: String, type: String): TagEntity {
        // 先尝试插入；OnConflictStrategy.IGNORE 已存在则忽略，
        // 再用 byName 查询一次拿到稳定的 id
        tagDao.insertTag(TagEntity(name = name, type = type))
        return tagDao.findByName(name) ?: TagEntity(id = 0, name = name, type = type)
    }

    private fun parseResponse(content: String): TagResult? {
        val cleaned = stripCodeFence(content)
        return try {
            val root = json.parseToJsonElement(cleaned).jsonObject
            val content_tags = (root["content_tags"]?.jsonArray ?: emptyList())
                .mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
            val risk_tags = (root["risk_tags"]?.jsonArray ?: emptyList())
                .mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
            val description = root["description"]?.jsonPrimitive?.content ?: ""
            val infoType = root["info_type"]?.jsonPrimitive?.content ?: ""
            val deleteSuggestion = root["delete_suggestion"]?.jsonPrimitive?.content ?: ""
            TagResult(
                contentTags = content_tags,
                riskTags = risk_tags,
                description = description,
                infoType = infoType,
                deleteSuggestion = deleteSuggestion,
            )
        } catch (t: Throwable) {
            Timber.w(t, "parseResponse failed: ${cleaned.take(200)}")
            null
        }
    }

    /**
     * 剥离 Markdown 代码围栏：命中首个 ```(json)? … ``` 时取围栏内正文，否则原样返回。
     * 单一正则一次提取，不做链式二次剥离（链式 substringAfter 会把 ```json 围栏的
     * 闭合围栏误当作起始围栏，丢掉整个 JSON 正文）。
     *
     * 自测推演（三种输入 → 输出）：
     * 1. 裸 JSON：      `{"a":1}`                              → 无匹配 → `{"a":1}`
     * 2. 围栏：         "```json\n{\"a\":1}\n```"              → group(1) → `{"a":1}`
     * 3. 带前后缀：     "好的：```json\n{\"a\":1}\n``` 供参考"  → group(1) → `{"a":1}`
     */
    private fun stripCodeFence(content: String): String {
        val body = FENCE_REGEX.find(content)?.groupValues?.get(1)
        return (body ?: content).trim()
    }

    sealed class Result {
        data class Success(val tags: TagResult) : Result()
        data class Failed(val reason: String) : Result()
    }

    data class TagResult(
        val contentTags: List<String>,
        val riskTags: List<String>,
        val description: String,
        val infoType: String,
        val deleteSuggestion: String,
    )

    companion object {
        /** 惰性匹配首个 ``` 围栏（可带 json 语言标注），DOT_MATCHES_ALL 允许正文跨行。 */
        private val FENCE_REGEX = Regex(
            "```(?:json)?\\s*(.*?)\\s*```",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        const val SYSTEM_PROMPT = """你是一个图片分析引擎，专门分析 QQ 聊天中的图片。请只返回 JSON，不要包含任何额外说明：

{
  "content_tags": ["标签1", "标签2"],
  "risk_tags": ["敏感标签1"],
  "description": "简短描述",
  "info_type": "发票/订单/证件/聊天截图/学习资料/二维码/表情包/广告/其他",
  "delete_suggestion": "safe_to_delete/review_needed/should_keep"
}

content_tags 可选值：截图、聊天截图、表情包、二维码、收款码、订单、发票、证件、课表、文档、风景、人物、宠物、食物、商品、广告、网页截图、代码截图、地图、游戏截图、表格、票据、物流单、名片
risk_tags 可选值：含手机号、含地址、含金额、含账号、含身份证、含银行卡、含验证码、含二维码、含聊天证据、含合同、含票据
"""
    }
}
