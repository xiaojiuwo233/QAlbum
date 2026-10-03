package com.xjw.qalbum.service.ai

import com.xjw.qalbum.data.db.entity.AiAnalysisEntity
import com.xjw.qalbum.data.db.entity.TAG_USER_KEEP
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 语义索引只描述图片内容；QQ 的随机缓存文件名不携带可搜索语义。 */
object SemanticDocument {
    fun build(ocrText: String?, analysis: AiAnalysisEntity?, tags: List<String>): String =
        buildList {
            analysis?.description?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.take(1200)) }
            analysis?.infoType?.trim()?.takeIf { it.isNotEmpty() && it != "其他" }?.let { add(it) }
            tags.map(String::trim).filter { it.isNotEmpty() && it != TAG_USER_KEEP }.distinct().take(24)
                .takeIf { it.isNotEmpty() }?.let { add(it.joinToString("、")) }
            ocrText?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.take(3000)) }
        }.joinToString("\n")

    fun modelKey(endpoint: String, model: String): String {
        val url = endpoint.trim().toHttpUrlOrNull()
        val segments = url?.pathSegments?.filter { it.isNotEmpty() }.orEmpty()
        val base = url?.newBuilder()?.username("")?.password("")?.query(null)?.fragment(null)?.encodedPath("/")?.apply {
            segments.forEach { addPathSegment(it) }
            if (segments.lastOrNull() != "v1") addPathSegment("v1")
        }?.build()?.toString()?.trimEnd('/') ?: endpoint.trim().trimEnd('/')
        return "$base|${model.trim()}"
    }
}
