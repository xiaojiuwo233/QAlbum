package com.xjw.qalbum.analytics

import android.util.Log
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.onboarding.AGREEMENT_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 匿名统计与崩溃日志（release 接 Firebase，debug 只写日志，见 [AnalyticsBackend]）。
 *
 * - 同意当前版本的用户协议之前什么都不收集；
 * - 「匿名统计」开着才记功能事件（AnalyticsEvents.kt）、页面与设置修改，并设几项用户属性；
 * - 「自动上传崩溃日志」开着才上传崩溃与错误（先经 [CrashSanitizer] 脱敏）。
 *
 * 参数只有次数、耗时、大小和固定的选项值；图片、路径、文件名、聊天内容、QQ 号、群和联系人不上传。
 * 设置还没读出来时先把事件攒着（最多 [MAX_PENDING] 条），读出来后按开关决定发不发。
 */
class Analytics(
    private val backend: AnalyticsBackend,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** 用户属性 library_size 用（图库张数分档） */
    private val images: ImageDao? = null,
) {
    private val lock = Any()
    private var state: AnalyticsState? = null
    private val pending = ArrayList<Pair<String, Map<String, Any>>>()
    private val properties = LinkedHashMap<String, String>()
    private var lastScreen: String? = null
    private var lastAccess: String? = null

    /** 关于页的两个开关（启动时就开始读，打开关于页时已经是实际的值） */
    val usageEnabled: StateFlow<Boolean> = settings.usageAnalytics.stateIn(scope, SharingStarted.Eagerly, true)
    val crashEnabled: StateFlow<Boolean> = settings.crashReports.stateIn(scope, SharingStarted.Eagerly, true)

    fun setUsageEnabled(enabled: Boolean) {
        scope.launch { settings.setUsageAnalytics(enabled) }
    }

    fun setCrashEnabled(enabled: Boolean) {
        scope.launch { settings.setCrashReports(enabled) }
    }

    /** App 启动时调一次 */
    fun start() {
        scope.launch {
            combine(settings.agreementVersion, settings.usageAnalytics, settings.crashReports) { version, usage, crash ->
                AnalyticsState.of(consent = version >= AGREEMENT_VERSION, usage = usage, crash = crash)
            }.distinctUntilChanged().collect(::onState)
        }
        scope.launch { settings.userChanges.collect { event("setting_change", "setting" to it.key, "setting_value" to it.value) } }
        scope.launch {
            combine(settings.accessMode, settings.advancedFeatures, settings.autoScan, settings.scheduledCleanupRules) { mode, advanced, autoScan, weekly ->
                listOf(
                    "access_mode" to mode.name.lowercase(),
                    "advanced" to onOff(advanced),
                    "auto_scan" to onOff(autoScan),
                    "weekly_cleanup" to weekly.size.toString(),
                )
            }.collect { it.forEach { (name, value) -> userProperty(name, value) } }
        }
        refreshLibrarySize()
    }

    /** 重新数一下图库张数（启动时、每次扫描后） */
    fun refreshLibrarySize() {
        val dao = images ?: return
        scope.launch { runCatching { dao.countActiveTimeline(stacked = false) }.onSuccess(::librarySize) }
    }

    private fun onState(new: AnalyticsState) {
        val old: AnalyticsState?
        val flush: List<Pair<String, Map<String, Any>>>
        val props: Map<String, String>
        synchronized(lock) {
            old = state
            state = new
            flush = if (new.usage) pending.toList() else emptyList()
            pending.clear()
            props = properties.toMap()
            if (!new.usage) lastScreen = null
        }
        backend.apply(new)
        // 关着时把用户属性清掉（Firebase 会一直留着上次设的，跟在自动事件上）
        if (!new.usage) USER_PROPERTIES.forEach { backend.setUserProperty(it, null) }
        else if (old?.usage != true) props.forEach { (name, value) -> backend.setUserProperty(name, value) }
        if (new.crash && old?.crash != true) props.forEach { (name, value) -> backend.setCrashKey(name, value) }
        if (old != null && !old.consent && new.consent) event("agreement_accept", "version" to AGREEMENT_VERSION)
        flush.forEach { (name, params) -> backend.log(name, params) }
    }

    /** 记一个事件；值为 null 的参数不带 */
    fun event(name: String, vararg params: Pair<String, Any?>) {
        val values = LinkedHashMap<String, Any>()
        for ((key, value) in params) {
            values[key] = when (value) {
                null -> continue
                is Boolean -> if (value) "yes" else "no"
                is Int -> value.toLong()
                is Float -> value.toDouble()
                is Long, is Double, is String -> value
                else -> value.toString()
            }
        }
        synchronized(lock) {
            val current = state
            if (current == null) {
                if (pending.size < MAX_PENDING) pending += name to values
                return
            }
            if (!current.usage) return
        }
        backend.log(name, values)
    }

    /** 打开了一个页面（连续同一页只记一次） */
    fun screen(name: String) {
        synchronized(lock) {
            if (name == lastScreen) return
            lastScreen = name
        }
        event("screen_view", "screen_name" to name)
    }

    /** 用户属性（只有 [USER_PROPERTIES] 里的这几项），也作为崩溃报告的键值 */
    fun userProperty(name: String, value: String) {
        val current: AnalyticsState?
        synchronized(lock) {
            if (properties[name] == value) return
            properties[name] = value
            current = state
        }
        if (current?.usage == true) backend.setUserProperty(name, value)
        if (current?.crash == true) backend.setCrashKey(name, value)
    }

    /** 文件访问检测的结果：变了才记一次（[result] 见 SettingsViewModel） */
    fun accessChecked(mode: String, result: String) {
        userProperty("access_ok", if (result == "ok") "yes" else "no")
        synchronized(lock) {
            val key = "$mode:$result"
            if (key == lastAccess) return
            lastAccess = key
        }
        event("access_check", "mode" to mode, "result" to result)
    }

    /** 图库里的图片数（分档） */
    fun librarySize(count: Int) = userProperty("library_size", countBucket(count))

    /** 非致命错误（开着「自动上传崩溃日志」时） */
    fun recordError(error: Throwable, message: String?) {
        if (synchronized(lock) { state }?.crash != true) return
        backend.recordError(CrashSanitizer.sanitize(error), message?.let(CrashSanitizer::sanitizeMessage))
    }

    /** 带异常的 error 日志作为非致命错误上传（只取消息第一行，Timber 会把栈接在后面） */
    fun errorTree(): Timber.Tree = object : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int) = priority >= Log.ERROR

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (t != null && t !is CancellationException) recordError(t, message.substringBefore('\n'))
        }
    }

    companion object {
        const val MAX_PENDING = 50

        val USER_PROPERTIES = listOf("access_mode", "access_ok", "advanced", "auto_scan", "weekly_cleanup", "library_size")
    }
}
