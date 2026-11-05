package com.xjw.qalbum.analytics

import kotlin.math.roundToLong

/*
 * 全部统计事件（只在「匿名统计」开着时上传）。另有 Analytics 里的 screen_view（页面）、setting_change（设置修改：
 * setting 键名、setting_value 新值）、access_check（文件访问检测）、agreement_accept 和几项用户属性。
 *
 * 通用参数：from 从哪里发起，mode 方式，trigger manual / auto，result 结果，error 异常类名，
 * count 处理的数量，success / failed / skipped 其中成功、失败、跳过的，duration_ms 耗时，*_mb 大小（MB）。
 */

// ---- 扫描 ----

/** 扫描图片结束；[mode] incremental / full / verify，[result] ok / failed / cancelled，[reason] 失败原因代码 */
fun Analytics.scanFinished(mode: String, automatic: Boolean, result: String, durationMs: Long, updated: Int?, reason: String?) =
    event("scan", "mode" to mode, "trigger" to trigger(automatic), "result" to result, "duration_ms" to durationMs, "count" to updated, "reason" to reason)

/**
 * 读取聊天记录（高级功能）结束：[mode] full / incremental，[result] ok / partial / failed / cancelled，
 * [pictures] 读到的图片来源条数，[stage] 失败在哪一步，耗时分复制数据库、解析、关联三段；
 * [forceStopped] 是 QQ 正写着数据库没读成后，用户确认强制停止 QQ 再读的那一次。
 */
fun Analytics.chatRead(
    automatic: Boolean, mode: String, result: String, durationMs: Long, copyMs: Long, importMs: Long, linkMs: Long,
    accounts: Int, failedAccounts: Int, pictures: Int, stage: String?, error: Throwable?, forceStopped: Boolean,
) = event(
    "chat_read", "trigger" to trigger(automatic), "mode" to mode, "result" to result, "duration_ms" to durationMs,
    "copy_ms" to copyMs, "import_ms" to importMs, "link_ms" to linkMs, "accounts" to accounts, "failed" to failedAccounts,
    "count" to pictures, "stage" to stage, "error" to errorName(error), "force_stop" to forceStopped,
)

// ---- 原图压缩 ----

/** 压缩对比页压好了：[count] 压的张数（多了只压样张），[success] 能压的，[originalBytes] / [savedBytes] 这些张的原大小与能省下的 */
fun Analytics.compressPreview(
    count: Int, success: Int, eligible: Int, quality: Int, maxSide: Int, durationMs: Long, originalBytes: Long, savedBytes: Long,
) = event(
    "compress_preview", "count" to count, "success" to success, "total" to eligible, "quality" to quality, "max_side" to maxSide,
    "duration_ms" to durationMs, "original_mb" to mb(originalBytes), "saved_mb" to mb(savedBytes),
)

/** 压缩并替换了原图：[from] compare（对比页应用）/ direct（不对比）/ rest（应用后其余的在后台压） */
fun Analytics.compressed(
    from: String, count: Int, success: Int, failed: Int, skipped: Int, savedBytes: Long, durationMs: Long,
    quality: Int, maxSide: Int, keepOriginal: Boolean?, result: String = "ok",
) = event(
    "compress", "from" to from, "count" to count, "success" to success, "failed" to failed, "skipped" to skipped,
    "saved_mb" to mb(savedBytes), "duration_ms" to durationMs, "quality" to quality, "max_side" to maxSide,
    "keep_original" to keepOriginal, "result" to result,
)

/** 对比页点了撤销（没有应用） */
fun Analytics.compressDiscarded(count: Int, quality: Int) = event("compress_discard", "count" to count, "quality" to quality)

/** 还原压缩前的原图 */
fun Analytics.originalsRestored(from: String, count: Int, success: Int) =
    event("original_restore", "from" to from, "count" to count, "success" to success)

/** 删除压缩前留下的原图备份：[scope] selected / all / expired */
fun Analytics.originalsDeleted(scope: String, count: Int) = event("original_delete", "scope" to scope, "count" to count)

// ---- 删除、回收站、归档 ----

/**
 * 移入回收站或永久删除图片：[from] gallery / archive / detail / chat / chat_scope / rule / schedule，
 * [rule] 规则（按时间清理记为 range），[count] 选中的张数，[success] 处理了的，[bytes] 移走的文件大小。
 */
fun Analytics.deleted(
    from: String, permanent: Boolean, count: Int, success: Int, skipped: Int, failed: Int, bytes: Long, durationMs: Long,
    rule: String? = null, result: String = "ok",
) = event(
    "delete", "from" to from, "method" to if (permanent) "permanent" else "trash", "count" to count, "success" to success,
    "skipped" to skipped, "failed" to failed, "size_mb" to mb(bytes), "duration_ms" to durationMs, "rule" to rule?.let(::ruleKey),
    "result" to result,
)

/** 从回收站恢复：[conflicts] 原位置已经有文件的，[keepExisting] 是对冲突选了「用现有的」 */
fun Analytics.trashRestored(count: Int, success: Int, conflicts: Int, archive: Boolean, keepExisting: Boolean, durationMs: Long) = event(
    "trash_restore", "count" to count, "success" to success, "conflicts" to conflicts, "archive" to archive,
    "keep_existing" to keepExisting, "duration_ms" to durationMs,
)

/** 回收站里永久删除：[scope] selected / all / expired（到期自动删除，不记大小） */
fun Analytics.trashPurged(scope: String, count: Int, success: Int, bytes: Long?, durationMs: Long) = event(
    "trash_purge", "scope" to scope, "count" to count, "success" to success, "size_mb" to bytes?.let(::mb), "duration_ms" to durationMs,
)

/** 归档 / 取消归档：[from] gallery / detail / rule / chat / chat_scope */
fun Analytics.archived(from: String, add: Boolean, count: Int) =
    event("archive", "from" to from, "action" to if (add) "add" else "remove", "count" to count)

// ---- 保存、分享 ----

/** 保存到系统相册：[kind] image / video，[thenTrash] 保存后移入回收站 */
fun Analytics.savedToAlbum(from: String, kind: String, count: Int, success: Int, thenTrash: Boolean = false) =
    event("save_to_album", "from" to from, "kind" to kind, "count" to count, "success" to success, "then_trash" to thenTrash)

/** 分享 / 用其他应用打开一张图 */
fun Analytics.shared(openWith: Boolean, ok: Boolean) =
    event(if (openWith) "open_with" else "share", "result" to if (ok) "ok" else "failed")

// ---- 清理页的其他功能 ----

/** 清空 QQ 缓存目录 */
fun Analytics.qqCacheCleared(dirs: Int, files: Long, bytes: Long, failed: Long, refused: Int, durationMs: Long) = event(
    "qq_cache_clear", "count" to dirs, "files" to files, "size_mb" to mb(bytes), "failed" to failed, "refused" to refused,
    "duration_ms" to durationMs,
)

/** 视频页扫描 */
fun Analytics.videoScanned(found: Int, includeFiles: Boolean, durationMs: Long, ok: Boolean) = event(
    "video_scan", "count" to found, "include_files" to includeFiles, "duration_ms" to durationMs, "result" to if (ok) "ok" else "no_access",
)

/** 删除视频 */
fun Analytics.videosDeleted(count: Int, success: Int, failed: Int, bytes: Long, durationMs: Long) = event(
    "video_delete", "count" to count, "success" to success, "failed" to failed, "size_mb" to mb(bytes), "duration_ms" to durationMs,
)

/** 每周定时清理：[result] ok / nothing / skipped（没有文件访问） */
fun Analytics.scheduledCleanup(rules: Int, moved: Int, bytes: Long, result: String) =
    event("scheduled_cleanup", "rules" to rules, "count" to moved, "size_mb" to mb(bytes), "result" to result)

// ---- 存储空间、更新、引导 ----

/** 精简数据库：[result] ok / busy / cancelled / failed */
fun Analytics.databaseSlimmed(result: String, savedBytes: Long, durationMs: Long) =
    event("db_slim", "result" to result, "saved_mb" to mb(savedBytes), "duration_ms" to durationMs)

/** 清除图片缓存 / 日志：[kind] images / logs */
fun Analytics.cacheCleared(kind: String, bytes: Long) = event("cache_clear", "kind" to kind, "size_mb" to mb(bytes))

/** 检查更新：[result] available / latest / failed */
fun Analytics.updateChecked(automatic: Boolean, result: String) = event("update_check", "trigger" to trigger(automatic), "result" to result)

/** 新版本弹窗：[action] download / later */
fun Analytics.updateDialog(action: String) = event("update_dialog", "action" to action)

/** 走完首次引导 */
fun Analytics.onboardingDone(scan: Boolean, accessMode: String, accessReady: Boolean) =
    event("onboarding_complete", "scan" to scan, "mode" to accessMode, "ready" to accessReady)

/** 高级功能的风险提示：开启了 / 取消了 */
fun Analytics.advancedRisk(confirmed: Boolean) = event("advanced_risk", "action" to if (confirmed) "confirm" else "cancel")

/** 清除聊天来源索引 */
fun Analytics.chatIndexCleared() = event("chat_index_clear")

// ---- 工具 ----

/** 字节 → MB（两位小数） */
fun mb(bytes: Long): Double = (bytes / 1_048_576.0 * 100).roundToLong() / 100.0

/** 从 [startNanos]（System.nanoTime()）到现在的毫秒数 */
fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

fun onOff(enabled: Boolean): String = if (enabled) "on" else "off"

private fun trigger(automatic: Boolean) = if (automatic) "auto" else "manual"

private fun errorName(error: Throwable?): String? = error?.javaClass?.simpleName

/** 规则 id（按时间清理的 range:起:止:名称 只记 range） */
fun ruleKey(id: String): String = if (id.startsWith("range:")) "range" else id

/** 数量分档（用户属性用，不记确切数） */
fun countBucket(count: Int): String = when {
    count <= 0 -> "0"
    count < 1_000 -> "<1k"
    count < 10_000 -> "1k-10k"
    count < 50_000 -> "10k-50k"
    count < 200_000 -> "50k-200k"
    count < 1_000_000 -> "200k-1m"
    else -> ">1m"
}

/**
 * 路由 → 页面名：只用路由模板（image/{imageId}），不带图片 id、聊天之类的参数。
 * 清理页（子页）和首次引导（每一页）在页面里自己记，这里返回 null。
 */
fun screenName(route: String): String? = when {
    route == "cleanup" || route == "onboarding" -> null
    route.startsWith("archive/image/") -> "archive_image"
    route.startsWith("image/") -> "image"
    route == "qq-sources" -> "chat_sources"
    route.startsWith("qq-sources/") -> "chat_source"
    else -> route.substringBefore('/').replace('-', '_')
}
