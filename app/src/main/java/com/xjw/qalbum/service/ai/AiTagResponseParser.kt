package com.xjw.qalbum.service.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal object AiTagResponseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val fence = Regex("```(?:json)?\\s*(.*?)\\s*```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun parse(content: String): AiTaggingService.TagResult? = runCatching {
        val cleaned = (fence.find(content)?.groupValues?.get(1) ?: content).trim()
        val root = json.parseToJsonElement(cleaned).jsonObject
        fun tags(key: String): List<String> {
            val raw = root[key] ?: return emptyList()
            require(raw is JsonArray)
            return raw.mapNotNull { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= 32 && it != "用户保留" }
            }.distinct().take(16)
        }
        fun text(key: String, maxLength: Int): String =
            (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(maxLength).orEmpty()
        val result = AiTaggingService.TagResult(
            contentTags = tags("content_tags"),
            riskTags = tags("risk_tags"),
            description = text("description", 1200),
            infoType = text("info_type", 64),
            deleteSuggestion = text("delete_suggestion", 32),
        )
        result.takeIf { it.contentTags.isNotEmpty() || it.riskTags.isNotEmpty() || it.description.isNotEmpty() }
    }.getOrNull()
}
