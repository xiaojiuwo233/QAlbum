package com.xjw.qalbum.analytics

import android.content.Context
import timber.log.Timber

/** debug：不接 Firebase，要上传的内容只写进日志（logcat 标签 Analytics），方便核对 */
@Suppress("UNUSED_PARAMETER")
fun createAnalyticsBackend(context: Context): AnalyticsBackend = LogBackend

private object LogBackend : AnalyticsBackend {
    override fun apply(state: AnalyticsState) = print("state $state")
    override fun log(name: String, params: Map<String, Any>) = print("$name $params")
    override fun setUserProperty(name: String, value: String?) = print("user property $name=$value")
    override fun setCrashKey(name: String, value: String) = print("crash key $name=$value")
    override fun recordError(error: Throwable, message: String?) = print("error ${message.orEmpty()}: $error")
    override fun forCrashReport(error: Throwable): Throwable = error

    private fun print(text: String) = Timber.tag("Analytics").i(text)
}
