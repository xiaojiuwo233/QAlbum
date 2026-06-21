package com.xjw.qalbum.log

import android.util.Log

/** 一条日志记录（message 可能含换行，如堆栈）。 */
data class LogEntry(
    val timeMillis: Long,
    val pid: Int,
    val tid: Int,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/** 日志级别，char 与 logcat / [Log] 优先级对齐。 */
enum class LogLevel(val char: Char, val priority: Int, val label: String) {
    V('V', Log.VERBOSE, "详细"),
    D('D', Log.DEBUG, "调试"),
    I('I', Log.INFO, "信息"),
    W('W', Log.WARN, "警告"),
    E('E', Log.ERROR, "错误"),
    A('A', Log.ASSERT, "崩溃"),
    ;

    companion object {
        fun fromPriority(p: Int): LogLevel = entries.firstOrNull { it.priority == p } ?: I
        fun fromChar(c: Char): LogLevel = when (c) {
            'F' -> A // logcat 的 Fatal
            else -> entries.firstOrNull { it.char == c } ?: I
        }
    }
}
