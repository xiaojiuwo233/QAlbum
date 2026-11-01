package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.archived
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.QqArchiveCounts
import com.xjw.qalbum.data.db.dao.QqScopeIds
import com.xjw.qalbum.data.db.dao.QqScopeSql
import com.xjw.qalbum.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 归档（原「收藏」）：归档的图片任何清理都不会删除，默认只在归档页出现。
 *
 * - 总是按整张图归档：同一张 QQ 图片的原图 / 预览图 / 缩略图一起；之后才入库或重新出现的版本
 *   （如后来下载的原图）在扫描结束时补上（[sync]）。
 * - 「归档的图片也在图库中显示」关闭时，归档的行 `status = archived`：图库、来源、标签、搜索、缩略图页
 *   都只认 active / thumbnail_trash，自然看不到它们，来源统计也不再计入；打开后恢复原状态，像收藏一样照常显示。
 * - 删除前的核查把归档当作保护（is_protected），整组跳过；archived 本身也不在可删除的状态里。
 */
class ArchiveManager(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
    private val analytics: Analytics? = null,
) {
    private val images = db.imageDao()

    /** 归档页查询用的状态，随设置变化 */
    val pageStatuses: Flow<List<String>> = settings.archiveVisible.map(::statusesOf)

    /** 启动时与设置切换后让数据库与设置一致（首次运行还会把旧版逐张收藏补齐为整张图） */
    fun start() {
        appScope.launch {
            settings.archiveVisible.collect { visible ->
                runCatching { sync(visible) }.onFailure { Timber.w(it, "Archive sync failed") }
            }
        }
    }

    /** 归档这些图（连同同一张图的其他版本）；返回处理的张数。[from] 是用户从哪里归档的（统计用，自动的不传） */
    suspend fun archive(ids: Collection<Long>, from: String? = null): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        images.archive(ids.distinct(), hidden = !settings.archiveVisible.first())
        from?.let { analytics?.archived(it, add = true, count = ids.size) }
        ids.size
    }

    suspend fun unarchive(ids: Collection<Long>, from: String? = null): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        images.unarchive(ids.distinct())
        from?.let { analytics?.archived(it, add = false, count = ids.size) }
        ids.size
    }

    /** 补齐家族并按设置统一隐藏或显示；扫描结束、设置切换时调用，可重复执行 */
    suspend fun sync(visible: Boolean? = null) = withContext(Dispatchers.IO) {
        val changed = images.syncArchive(hidden = !(visible ?: settings.archiveVisible.first()))
        if (changed > 0) Timber.i("Archive sync: $changed rows updated")
    }

    /** 按来源范围归档前的统计 */
    suspend fun scopeCounts(scope: QqScopeIds): QqArchiveCounts = withContext(Dispatchers.IO) {
        db.qqSourceDao().queryArchiveCounts(QqScopeSql.archiveCounts(scope))
    }

    /**
     * 归档一个来源范围（某个群 / 某人，可再按成员或会话筛选）里的全部图片与 QQ 缩略图：共用的照样归档，
     * 同一张图的其他版本一起。分块进行（每块一个事务），[onProgress]（已处理, 总数）。返回归档的张数。
     */
    suspend fun archiveScope(scope: QqScopeIds, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val counts = scopeCounts(scope)
            val total = counts.images + counts.thumbs
            val hidden = !settings.archiveVisible.first()
            val sources = db.qqSourceDao()
            var done = 0
            var afterId = 0L
            onProgress(0, total)
            while (true) {
                val page = sources.queryIds(QqScopeSql.archiveIds(scope, afterId, SCOPE_PAGE))
                if (page.isEmpty()) break
                images.archive(page, hidden)
                done += page.size
                afterId = page.last()
                onProgress(done.coerceAtMost(total), total)
            }
            if (done > 0) analytics?.archived("chat_scope", add = true, count = done)
            done
        }

    /**
     * 自动归档名单：名单里每个范围（会话 / 发送人，或会话里的某个成员）还没归档的图片与缩略图一并归档。
     * 读取来源（统计整理完）和每次扫描结束后执行；分类还没整理出来的范围先跳过，下次再补。返回归档的张数。
     */
    suspend fun applyAutoArchive(): Int = withContext(Dispatchers.IO) {
        val sources = db.qqSourceDao()
        var total = 0
        for (entry in sources.autoArchiveEntries()) {
            val conversation = if (entry.conversationKey.isEmpty()) null else sources.facetId(0, entry.conversationKey) ?: continue
            val sender = if (entry.senderKey.isEmpty()) null else sources.facetId(1, entry.senderKey) ?: continue
            if (conversation == null && sender == null) continue
            total += archiveScope(QqScopeIds(conversation, sender))
        }
        if (total > 0) Timber.i("Auto archive: %d rows", total)
        total
    }

    companion object {
        private const val SCOPE_PAGE = 400

        fun statusesOf(visible: Boolean): List<String> = if (visible) listOf("active", "thumbnail_trash") else listOf("archived")
    }
}
