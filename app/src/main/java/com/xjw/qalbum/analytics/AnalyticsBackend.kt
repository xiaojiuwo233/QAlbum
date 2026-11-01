package com.xjw.qalbum.analytics

/**
 * 统计的落地方式：release 接 Firebase（Analytics + Crashlytics），debug 只写日志
 * （各自源码集里的 createAnalyticsBackend）。只由 [Analytics] 调用，同意协议与开关的判断都在那边。
 */
interface AnalyticsBackend {
    /** 同意协议、两个开关变了 */
    fun apply(state: AnalyticsState)

    /** 参数值只有 String、Long、Double */
    fun log(name: String, params: Map<String, Any>)

    /** null 清除 */
    fun setUserProperty(name: String, value: String?)

    /** 附在崩溃报告上的键值（访问方式等） */
    fun setCrashKey(name: String, value: String)

    /** 非致命错误，已经脱敏 */
    fun recordError(error: Throwable, message: String?)

    /** 崩溃交给 Crashlytics 和系统之前的处理：release 脱敏，debug 原样 */
    fun forCrashReport(error: Throwable): Throwable
}

/**
 * [consent] 同意了当前版本的用户协议：统计活跃用户（Firebase 的打开、使用时长等自动事件）；
 * [usage] 匿名统计（功能事件、页面、设置修改）；[crash] 上传崩溃与错误。后两个都以 [consent] 为前提。
 */
data class AnalyticsState(val consent: Boolean, val usage: Boolean, val crash: Boolean) {
    companion object {
        fun of(consent: Boolean, usage: Boolean, crash: Boolean) = AnalyticsState(consent, consent && usage, consent && crash)
    }
}

/** 什么也不做（没有 Firebase 配置的 release、测试） */
object NoAnalyticsBackend : AnalyticsBackend {
    override fun apply(state: AnalyticsState) = Unit
    override fun log(name: String, params: Map<String, Any>) = Unit
    override fun setUserProperty(name: String, value: String?) = Unit
    override fun setCrashKey(name: String, value: String) = Unit
    override fun recordError(error: Throwable, message: String?) = Unit
    override fun forCrashReport(error: Throwable): Throwable = error
}
