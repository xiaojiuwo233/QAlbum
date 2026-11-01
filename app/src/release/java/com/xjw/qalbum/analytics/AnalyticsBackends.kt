package com.xjw.qalbum.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** release：Firebase Analytics + Crashlytics。没有 Firebase 配置时什么也不做 */
fun createAnalyticsBackend(context: Context): AnalyticsBackend =
    if (FirebaseApp.getApps(context).isEmpty()) NoAnalyticsBackend else FirebaseBackend(context)

/**
 * 清单里 Analytics 与 Crashlytics 的收集默认都关着（src/release/AndroidManifest.xml），同意协议后按开关打开。
 * Firebase 自己会记住打开与否，下次启动读出设置之前也是对的。
 */
private class FirebaseBackend(context: Context) : AnalyticsBackend {
    private val analytics = FirebaseAnalytics.getInstance(context)
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun apply(state: AnalyticsState) {
        analytics.setAnalyticsCollectionEnabled(state.consent)
        crashlytics.setCrashlyticsCollectionEnabled(state.crash)
        // 关着时 Crashlytics 照样把崩溃存在本机、等打开后补发
        if (!state.crash) crashlytics.deleteUnsentReports()
    }

    override fun log(name: String, params: Map<String, Any>) {
        val bundle = Bundle(params.size)
        params.forEach { (key, value) ->
            when (value) {
                is Long -> bundle.putLong(key, value)
                is Double -> bundle.putDouble(key, value)
                else -> bundle.putString(key, value.toString())
            }
        }
        analytics.logEvent(name, bundle)
    }

    override fun setUserProperty(name: String, value: String?) = analytics.setUserProperty(name, value)

    override fun setCrashKey(name: String, value: String) = crashlytics.setCustomKey(name, value)

    override fun recordError(error: Throwable, message: String?) {
        message?.let(crashlytics::log)
        crashlytics.recordException(error)
    }

    override fun forCrashReport(error: Throwable): Throwable = CrashSanitizer.sanitize(error)
}
