package com.xjw.qalbum.service.update

import com.xjw.qalbum.AppInfo
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.updateChecked
import com.xjw.qalbum.analytics.updateDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** GitHub 上发布的一个版本 */
data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    /** 更新说明（Markdown） */
    val notes: String,
    /** 发布页（去下载） */
    val url: String,
)

sealed interface UpdateResult {
    data class Available(val release: AppRelease) : UpdateResult
    data object UpToDate : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * 检查更新：读 GitHub 上 [AppInfo.GITHUB_REPO] 最新的 Release（不含预发布），tag 是「版本代码-版本号」，
 * 版本代码比当前的大就是有新版本。只发一个请求，不带任何设备或图片信息。
 */
class UpdateChecker(private val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()) {

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatest() ?: return@withContext UpdateResult.Failed("还没有发布的版本")
            if (release.versionCode > currentVersionCode) UpdateResult.Available(release) else UpdateResult.UpToDate
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: HttpStatusException) {
            Timber.w(e, "检查更新失败")
            // 不登录的查询每个网络每小时 60 次，用完了 GitHub 回 403
            UpdateResult.Failed(if (e.code == 403 || e.code == 429) "GitHub 限制了查询次数，过一会儿再试" else "GitHub 返回 ${e.code}")
        } catch (t: Throwable) {
            Timber.w(t, "检查更新失败")
            UpdateResult.Failed(if (t is IOException) "连不上 GitHub" else t.message ?: "未知错误")
        }
    }

    private fun fetchLatest(): AppRelease? {
        val connection = URL("https://api.github.com/repos/${AppInfo.GITHUB_REPO}/releases/latest").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "QAlbum/${BuildConfig.VERSION_NAME}")
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return null
                else -> throw HttpStatusException(code)
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val (code, name) = parseTag(json.optString("tag_name")) ?: throw IllegalStateException("版本标签不是「版本代码-版本号」")
            return AppRelease(
                versionCode = code,
                versionName = name,
                notes = json.optString("body"),
                url = json.optString("html_url").ifBlank { "${AppInfo.PROJECT_URL}/releases" },
            )
        } finally {
            connection.disconnect()
        }
    }

    private class HttpStatusException(val code: Int) : IOException("GitHub 返回 $code")

    companion object {
        private const val TIMEOUT_MS = 8_000

        /** tag「版本代码-版本号」（如 29881114-0.4.1，前面可带 v）：版本代码与版本号；版本号缺省时用版本代码 */
        fun parseTag(tag: String): Pair<Long, String>? {
            val trimmed = tag.trim().removePrefix("v").removePrefix("V")
            val code = trimmed.substringBefore('-').toLongOrNull() ?: return null
            val name = trimmed.substringAfter('-', missingDelimiterValue = "").ifBlank { code.toString() }
            return code to name
        }
    }
}

/**
 * 有新版本时要弹窗显示的那个（[pending]）：打开 App 进概览时自动查一次（每次启动只查一次，查不到不提示），
 * 「关于」里手动查的结果也走这里。
 */
class AppUpdates(
    private val checker: UpdateChecker,
    private val scope: CoroutineScope,
    private val analytics: Analytics? = null,
) {
    private val launched = AtomicBoolean(false)
    private val _pending = MutableStateFlow<AppRelease?>(null)
    val pending: StateFlow<AppRelease?> = _pending.asStateFlow()

    fun checkOnLaunch() {
        if (!launched.compareAndSet(false, true)) return
        scope.launch { (check(automatic = true) as? UpdateResult.Available)?.let { _pending.value = it.release } }
    }

    suspend fun checkNow(): UpdateResult = check(automatic = false).also { if (it is UpdateResult.Available) _pending.value = it.release }

    private suspend fun check(automatic: Boolean): UpdateResult = checker.check().also { result ->
        analytics?.updateChecked(
            automatic,
            when (result) {
                is UpdateResult.Available -> "available"
                UpdateResult.UpToDate -> "latest"
                is UpdateResult.Failed -> "failed"
            },
        )
    }

    /** 关掉新版本弹窗；[download] 是点了「去下载」 */
    fun dismiss(download: Boolean = false) {
        if (_pending.value != null) analytics?.updateDialog(if (download) "download" else "later")
        _pending.value = null
    }
}
