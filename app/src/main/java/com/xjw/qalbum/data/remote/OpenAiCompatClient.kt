package com.xjw.qalbum.data.remote

import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.service.ai.AiPrivacyGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.flow.first
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 与 OpenAI Chat Completions / Embeddings API 兼容的 HTTP Client。
 *
 * 任何遵循 OpenAI 协议的服务都可以接入：OpenAI、Anthropic（via LiteLLM）、Gemini（via proxy）、
 * Ollama、vLLM、各类中转站等。用户在设置页填入 endpoint + API Key + model 即可。
 *
 * 设计：
 * - 用 OkHttp + kotlinx.serialization Json builder（手动 JSON 构造，避免大量 data class）
 * - 所有方法都是 suspend；OkHttp Call 通过 [Call.awaitResponse] 桥接
 */
class OpenAiCompatClient(
    private val settings: SettingsRepository,
    private val okHttpClient: OkHttpClient,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Chat Completion（含 vision，imageBase64 不为空时附带图片）
     *
     * [maxTokens] 为空时读取设置里的「最大 token」（思考模型需较大额度，否则正文为空）。
     */
    suspend fun chatCompletion(
        messages: List<ChatMessage>,
        model: String? = null,
        maxTokens: Int? = null,
        temperature: Float = 0.3f,
        expectedEndpoint: String? = null,
    ): ChatResponse? = withContext(Dispatchers.IO) {
        val cfg = settings.aiConnection.first()
        if (!cfg.enabled) return@withContext null
        val endpoint = cfg.endpoint
        if (expectedEndpoint != null && endpoint != expectedEndpoint) return@withContext null
        val apiKey = cfg.apiKey
        val defaultModel = cfg.model
        // 本地服务（LM Studio/Ollama 等）通常免鉴权，故 Key 可空，仅 endpoint 必填
        if (endpoint.isBlank()) {
            Timber.w("OpenAiCompatClient: AI endpoint is empty")
            return@withContext null
        }
        // C148 上传链路兜底：带图请求发往公网 endpoint 且用户从未在隐私弹窗确认过时拒绝发送。
        // UI 侧「启用时确认 + 改公网地址时确认」是第一道闸，这里兜住所有绕过弹窗的路径。
        if (messages.any { it.imageBase64 != null } &&
            AiPrivacyGuard.classify(endpoint) == AiPrivacyGuard.Safety.PUBLIC_INTERNET &&
            !settings.isAiPublicEndpointAcknowledged(endpoint)
        ) {
            Timber.w("OpenAiCompatClient: 拒绝向未经隐私确认的公网 endpoint 上传图片")
            return@withContext null
        }
        val disableThinking = cfg.disableThinking
        val maxTok = maxTokens ?: cfg.maxTokens
        val url = resolveUrl(endpoint, "chat/completions") ?: return@withContext null

        val body = buildJsonObject {
            put("model", model ?: defaultModel)
            put("max_tokens", maxTok)
            put("temperature", temperature)
            // 关闭思考：尽量兼容多种服务端实现（vLLM / LM Studio / 原生 qwen3 模板）
            if (disableThinking) {
                put("enable_thinking", false)
                putJsonObject("chat_template_kwargs") { put("enable_thinking", false) }
            }
            putJsonArray("messages") {
                messages.forEach { msg -> add(buildMessage(msg)) }
            }
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${apiKey.ifBlank { "not-needed" }}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val resp = okHttpClient.newCall(req).awaitResponse()
            resp.use { r ->
                if (!r.isSuccessful) {
                    Timber.w("ChatCompletion HTTP ${r.code}")
                    return@use null
                }
                parseChatResponse(r.body?.string(), disableThinking)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "ChatCompletion failed")
            null
        }
    }

    /** Embedding（文本/查询语义向量） */
    suspend fun embedding(text: String, model: String? = null, expectedEndpoint: String? = null): FloatArray? = withContext(Dispatchers.IO) {
        val cfg = settings.aiConnection.first()
        if (!cfg.enabled || text.isBlank()) return@withContext null
        val endpoint = cfg.endpoint
        if (expectedEndpoint != null && endpoint != expectedEndpoint) return@withContext null
        val apiKey = cfg.apiKey
        val embModel = model ?: cfg.embeddingModel
        // 本地服务通常免鉴权，Key 可空，仅 endpoint 必填
        if (endpoint.isBlank()) return@withContext null
        if (AiPrivacyGuard.classify(endpoint) == AiPrivacyGuard.Safety.PUBLIC_INTERNET &&
            !settings.isAiPublicEndpointAcknowledged(endpoint)) return@withContext null
        val url = resolveUrl(endpoint, "embeddings") ?: return@withContext null

        val body = buildJsonObject {
            put("model", embModel)
            put("input", text)
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${apiKey.ifBlank { "not-needed" }}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val resp = okHttpClient.newCall(req).awaitResponse()
            resp.use { r ->
                if (!r.isSuccessful) {
                    Timber.w("Embedding HTTP ${r.code}")
                    return@use null
                }
                parseEmbeddingResponse(r.body?.string())
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "Embedding failed")
            null
        }
    }

    // ---- URL 归一化 ----

    /**
     * 拼接最终请求 URL，避免用户在 endpoint 里已带 `/v1` 时重复拼接成 `/v1/v1/...`。
     *
     * - `http://host:1234`        + chat/completions → http://host:1234/v1/chat/completions
     * - `http://host:1234/v1`     + chat/completions → http://host:1234/v1/chat/completions
     * - `http://host:1234/v1/`    + chat/completions → http://host:1234/v1/chat/completions
     */
    private fun resolveUrl(endpoint: String, path: String): HttpUrl? {
        val base = endpoint.trim().toHttpUrlOrNull() ?: return null
        val segments = base.pathSegments.filter { it.isNotEmpty() }
        return base.newBuilder().encodedPath("/").fragment(null).apply {
            segments.forEach { addPathSegment(it) }
            if (segments.lastOrNull() != "v1") addPathSegment("v1")
            path.split('/').forEach { addPathSegment(it) }
        }.build()
    }

    // ---- JSON 构造 / 解析 ----

    private fun buildMessage(msg: ChatMessage): JsonObject = buildJsonObject {
        put("role", msg.role)
        if (msg.imageBase64 != null) {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", msg.text)
                }
                addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        // OpenAI vision 兼容格式：data URL + 可选 detail 控制 token 消耗
                        put("url", "data:image/jpeg;base64,${msg.imageBase64}")
                        put("detail", msg.imageDetail)
                    }
                }
            }
        } else {
            put("content", msg.text)
        }
    }

    private fun parseChatResponse(raw: String?, stripThinking: Boolean): ChatResponse? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val choices = root["choices"]?.jsonArray ?: return null
            val first = choices.firstOrNull()?.jsonObject ?: return null
            val message = first["message"]?.jsonObject ?: return null
            val rawContent = message["content"]?.jsonPrimitive?.content ?: return null
            val content = if (stripThinking) cleanThinking(rawContent) else rawContent
            if (content.isBlank()) {
                // 部分思考模型会把全部 token 用于思考（reasoning_content），导致 content 为空，
                // 此时给出明确日志，提示需要调大 max_tokens 或更换非思考模型
                Timber.w("ChatCompletion content 为空（可能思考占满 max_tokens）")
            }
            val usage = root["usage"]?.jsonObject
            ChatResponse(
                content = content,
                usage = usage?.let {
                    TokenUsage(
                        promptTokens = it["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        completionTokens = it["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    )
                },
            )
        } catch (t: Throwable) {
            Timber.w(t, "parseChatResponse failed")
            null
        }
    }

    /**
     * 剥离响应正文里的思考内容（针对把 think 直接写进 content 的模型）。
     * 处理成对的 `<think>…</think>` / `<thinking>…</thinking>`，
     * 以及思考被截断只剩结束标签等残留情况。
     */
    private fun cleanThinking(text: String): String {
        var s = text
        // 1) 去掉成对思考块
        s = THINK_BLOCK_REGEX.replace(s, "")
        // 2) 残留未配对的结束标签：取最后一个结束标签之后的内容作为正文
        val lastClose = maxOf(s.lastIndexOf("</think>"), s.lastIndexOf("</thinking>"))
        if (lastClose >= 0) {
            s = s.substring(lastClose)
                .removePrefix("</think>")
                .removePrefix("</thinking>")
        }
        return s.trim()
    }

    private fun parseEmbeddingResponse(raw: String?): FloatArray? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val data = root["data"]?.jsonArray ?: return null
            val first = data.firstOrNull()?.jsonObject ?: return null
            val arr = first["embedding"]?.jsonArray ?: return null
            FloatArray(arr.size) { i -> arr[i].jsonPrimitive.float }
                .takeIf { it.isNotEmpty() && it.all(Float::isFinite) && it.any { value -> value != 0f } }
        } catch (t: Throwable) {
            Timber.w(t, "parseEmbeddingResponse failed")
            null
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 匹配 <think>…</think> / <thinking>…</thinking>（忽略大小写、跨行） */
        private val THINK_BLOCK_REGEX =
            Regex("<think(?:ing)?>.*?</think(?:ing)?>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}

// ---- 数据模型 ----

data class ChatMessage(
    val role: String,            // system / user / assistant
    val text: String,
    val imageBase64: String? = null,
    val imageDetail: String = "low",  // low 模式大幅降低 token 消耗
)

data class ChatResponse(
    val content: String,
    val usage: TokenUsage? = null,
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
)

// ---- OkHttp Call 转 suspend ----

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
