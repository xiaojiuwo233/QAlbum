package com.xjw.qalbum.analytics

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 上传前给错误脱敏：异常消息里的文件路径、content:// 地址、QQ 的 uid、长十六进制串和长数字（QQ 号、群号）换成占位符，
 * 栈帧照旧。消息都不用改的原样返回；要改的按原类型重建一份，重建不了的包一层，原类型名写进消息。
 */
object CrashSanitizer {

    private val rules = listOf(
        Regex("""(?:content|file)://\S+""") to "<uri>",
        // 两段以上的绝对路径（/storage/…、/data/user/0/…）
        Regex("""(?<![\w.])/(?:[^\s:'"(),;]+/)+[^\s:'"(),;]*""") to "<path>",
        Regex("""u_[A-Za-z0-9_-]{6,}""") to "<uid>",
        Regex("""\b[0-9a-fA-F]{16,}\b""") to "<hex>",
        Regex("""\d{5,}""") to "<n>",
    )

    fun sanitizeMessage(message: String): String = rules.fold(message) { text, (regex, replacement) -> regex.replace(text, replacement) }

    fun sanitize(error: Throwable): Throwable =
        if (needsWork(error, Collections.newSetFromMap(IdentityHashMap()))) rebuild(error, IdentityHashMap()) else error

    private fun needsWork(error: Throwable, seen: MutableSet<Throwable>): Boolean {
        if (!seen.add(error)) return false
        val message = error.message
        if (message != null && sanitizeMessage(message) != message) return true
        return error.cause?.let { needsWork(it, seen) } == true || error.suppressed.any { needsWork(it, seen) }
    }

    /** [done]：已重建的（值为 null 的正在重建，原因链绕回来时用占位的代替） */
    private fun rebuild(error: Throwable, done: IdentityHashMap<Throwable, Throwable?>): Throwable {
        if (done.containsKey(error)) return done[error] ?: SanitizedException(error.javaClass.name, null)
        done[error] = null
        val message = error.message?.let(::sanitizeMessage)
        val cause = error.cause?.takeIf { it !== error }?.let { rebuild(it, done) }
        val copy = sameType(error, message, cause) ?: SanitizedException("${error.javaClass.name}: $message", cause)
        copy.stackTrace = error.stackTrace
        done[error] = copy
        error.suppressed.forEach { copy.addSuppressed(rebuild(it, done)) }
        return copy
    }

    /** 用原类型的 (String) 构造方法重建；接不上 cause 时放弃 */
    private fun sameType(error: Throwable, message: String?, cause: Throwable?): Throwable? = runCatching {
        val copy = error.javaClass.getConstructor(String::class.java).newInstance(message) as Throwable
        if (cause != null) copy.initCause(cause)
        copy
    }.getOrNull()
}

/** 重建不了原类型时代替它（消息开头是原类型名） */
class SanitizedException(message: String, cause: Throwable?) : Exception(message, cause)
