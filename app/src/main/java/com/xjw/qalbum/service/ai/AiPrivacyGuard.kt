package com.xjw.qalbum.service.ai

import android.net.Uri
import java.net.InetAddress

/**
 * 判断 AI endpoint 的安全等级，给用户提示
 *
 * - LOCALHOST：本机部署（如 http://127.0.0.1:11434 Ollama）
 * - LOCAL_NETWORK：内网部署（10.x / 172.16-31.x / 192.168.x）
 * - PUBLIC_INTERNET：公网服务，**需要弹隐私警告**
 * - INVALID：地址格式有误
 */
object AiPrivacyGuard {

    enum class Safety {
        LOCALHOST,
        LOCAL_NETWORK,
        PUBLIC_INTERNET,
        INVALID,
    }

    fun classify(endpoint: String): Safety {
        if (endpoint.isBlank()) return Safety.INVALID
        val host = runCatching { Uri.parse(endpoint).host }.getOrNull()
            ?: return Safety.INVALID
        if (host.isEmpty()) return Safety.INVALID

        if (host == "localhost") return Safety.LOCALHOST
        if (host == "127.0.0.1" || host == "::1") return Safety.LOCALHOST

        // 简单字符串前缀判定，避免 DNS 查询（DNS 在主线程不安全）
        if (host.startsWith("10.")) return Safety.LOCAL_NETWORK
        if (host.startsWith("192.168.")) return Safety.LOCAL_NETWORK
        if (host.startsWith("169.254.")) return Safety.LOCAL_NETWORK
        if (host.startsWith("172.")) {
            val parts = host.split('.')
            val second = parts.getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return Safety.LOCAL_NETWORK
        }

        return Safety.PUBLIC_INTERNET
    }

    /** 公网 endpoint 的隐私警告文本（在设置页/首次启用时弹出） */
    const val PRIVACY_WARNING: String = """
⚠️ 隐私提示

你正在配置一个公网 AI 服务地址。这意味着：
• 你的 QQ 聊天图片（压缩后）将通过互联网发送到该服务器
• 图片可能包含个人隐私信息（聊天内容、联系方式、证件等）
• 建议不要对含身份证、银行卡、聊天隐私的图片使用 AI 分析

如果你在本机或局域网部署了 Ollama / vLLM 等本地推理服务，
请填写内网地址（如 http://192.168.1.100:11434），数据不会离开你的网络。

确认继续使用公网服务？
"""
}
