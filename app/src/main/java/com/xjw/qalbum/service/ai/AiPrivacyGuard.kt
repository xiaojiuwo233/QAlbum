package com.xjw.qalbum.service.ai

import android.net.Uri

/**
 * 判断 AI endpoint 的安全等级，给用户提示
 *
 * - LOCALHOST：本机部署（localhost / 127.0.0.0/8 / ::1，如 http://127.0.0.1:11434 Ollama）
 * - LOCAL_NETWORK：内网部署（10/8、172.16/12、192.168/16、169.254/16、IPv6 ULA/链路本地）
 * - PUBLIC_INTERNET：公网服务，**需要弹隐私警告**
 * - INVALID：地址格式有误
 *
 * 私网判定只对 IP 字面量生效：形如 "10.foo.com" 的公网域名不能因前缀伪装成内网
 * 而绕过隐私警告，非 IP 域名一律按公网处理（不做 DNS 解析，DNS 在主线程不安全）。
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
        val rawHost = runCatching { Uri.parse(endpoint).host }.getOrNull()
            ?: return Safety.INVALID
        if (rawHost.isEmpty()) return Safety.INVALID
        // Uri.getHost 对 IPv6 字面量返回带方括号形式（如 "[::1]"），统一去括号并小写
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (host.isEmpty()) return Safety.INVALID

        if (host == "localhost") return Safety.LOCALHOST

        val ipv4 = parseIpv4(host)
        if (ipv4 != null) {
            val (a, b) = ipv4
            return when {
                a == 127 -> Safety.LOCALHOST                     // 127.0.0.0/8 环回
                a == 10 -> Safety.LOCAL_NETWORK                  // 10.0.0.0/8
                a == 172 && b in 16..31 -> Safety.LOCAL_NETWORK  // 172.16.0.0/12
                a == 192 && b == 168 -> Safety.LOCAL_NETWORK     // 192.168.0.0/16
                a == 169 && b == 254 -> Safety.LOCAL_NETWORK     // 169.254.0.0/16 链路本地
                else -> Safety.PUBLIC_INTERNET
            }
        }

        if (host.contains(':')) {
            // IPv6 字面量：去 zone id（如 fe80::1%wlan0）后按前缀分类
            val v6 = host.substringBefore('%')
            return when {
                v6 == "::1" -> Safety.LOCALHOST
                v6.startsWith("fc") || v6.startsWith("fd") -> Safety.LOCAL_NETWORK    // ULA fc00::/7
                v6.startsWith("fe8") || v6.startsWith("fe9") ||
                    v6.startsWith("fea") || v6.startsWith("feb") -> Safety.LOCAL_NETWORK  // 链路本地 fe80::/10
                else -> Safety.PUBLIC_INTERNET
            }
        }

        // 非 IP 字面量的域名（含 "10.foo.com" 这类伪装前缀）一律按公网处理
        return Safety.PUBLIC_INTERNET
    }

    /** 仅当 host 是合法 IPv4 字面量（4 段十进制 0-255）时返回前两段，否则 null。 */
    private fun parseIpv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = IntArray(4)
        for (i in parts.indices) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            nums[i] = v
        }
        return nums[0] to nums[1]
    }

    /** 公网 endpoint 的隐私警告文本（在设置页/首次启用时弹出） */
    const val PRIVACY_WARNING: String = """
⚠️ 隐私提示

你正在配置一个公网 AI 服务地址。这意味着：
• 图片、OCR 文字、图片描述和搜索词可能通过互联网发送到该服务器
• 图片可能包含个人隐私信息（聊天内容、联系方式、证件等）
• 建议不要对含身份证、银行卡、聊天隐私的图片使用 AI 分析

如果你在本机或局域网部署了 Ollama / vLLM 等本地推理服务，
请填写内网地址（如 http://192.168.1.100:11434），数据不会离开你的网络。

确认继续使用公网服务？
"""
}
