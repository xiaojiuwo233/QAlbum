package com.xjw.qalbum.ui.sources

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.db.dao.QqScopeStats
import com.xjw.qalbum.data.db.dao.QqSourceCounts
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqScopeIds
import com.xjw.qalbum.data.db.dao.QqScopeOrder
import com.xjw.qalbum.data.db.dao.QqScopeSql
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.qq.QqSourceRepository
import com.xjw.qalbum.qq.QqSourceScope
import com.xjw.qalbum.service.cleanup.ExternalViewTarget
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import com.xjw.qalbum.ui.gallery.GalleryTimelineItem
import com.xjw.qalbum.ui.gallery.withDateHeaders
import com.xjw.qalbum.ui.gallery.withSizeHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 按来源范围整体清理前的确认状态：[counts] 为 null 表示正在统计 */
data class QqTrashConfirmation(
    val scope: QqSourceScope,
    val ids: QqScopeIds,
    val counts: QqSourceCounts?,
    val includeShared: Boolean = false,
    val contents: Set<QqContent> = setOf(QqContent.IMAGES, QqContent.THUMBNAILS),
    val linkedVersions: Boolean = true,
)

/** 分类查找结果：外层 null = 仍在读取；[facet] 为 null = 没有这个分类（或统计尚未整理出来） */
data class FacetLookup(val facet: QqSourceFacet?)

/**
 * 来源浏览页：某个会话（可再按成员筛选）/ 某个发送人（可再按会话筛选）/ 全部已关联图片。
 *
 * 内容分「图片 / QQ 缩略图」两类；数量与占用直接取增量维护的分类统计，不再逐次聚合。
 * 网格按范围从关联表取图（索引范围 + 主键回表），排序（日期 / 大小 × 升降序）持久化并插入分组标题。
 * 多选与看图页的删除、收藏、保存都在这里执行；批量文件操作跑在 App 级作用域，返回上一页不中断。
 */
class QqSourceBrowseViewModel(
    val target: SourceBrowseTarget,
    private val repository: QqSourceRepository,
    private val settings: SettingsRepository,
    private val images: ImageRepository,
    private val imageFiles: ImageFileManager,
    private val quarantine: QuarantineManager,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val dao = repository.dao

    val content = MutableStateFlow(QqContent.IMAGES)

    /** 二级筛选：会话页为成员，发送人页为会话；null = 不筛选 */
    val filter = MutableStateFlow<QqSourceFacet?>(null)
    val filterSearch = MutableStateFlow("")

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val sort: StateFlow<SourceSort> = settings.qqSourceSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceSort.DATE_DESC)

    val columns: StateFlow<Int> = settings.galleryColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_GALLERY_COLUMNS)

    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前分类；「全部」用汇总合成一个 */
    val lookup: StateFlow<FacetLookup?> = when (target.kind) {
        SourceKind.CONVERSATION -> dao.observeFacet(0, target.key)
        SourceKind.SENDER -> dao.observeFacet(1, target.key)
        SourceKind.ALL -> dao.observeSummary().map { summary ->
            QqSourceFacet(
                id = 0, key = "all", name = "全部来源图片", alias = "",
                count = summary?.imageCount ?: 0, bytes = summary?.imageBytes ?: 0,
                thumbCount = summary?.thumbCount ?: 0, thumbBytes = summary?.thumbBytes ?: 0,
                chatType = 0, latestTime = 0, accountName = "",
            )
        }
    }.map { FacetLookup(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val baseId: Flow<Long?> = lookup.map { it?.facet?.id }.distinctUntilChanged()

    /** 浏览范围（分类 id）；分类还没找到时为 null */
    val scopeIds: StateFlow<QqScopeIds?> = combine(baseId, filter) { id, selected ->
        when (target.kind) {
            SourceKind.ALL -> QqScopeIds()
            SourceKind.CONVERSATION -> id?.let { QqScopeIds(conversationId = it, senderId = selected?.id) }
            SourceKind.SENDER -> id?.let { QqScopeIds(senderId = it, conversationId = selected?.id) }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 删除核查用的来源键（同一张图的其他消息是否在范围外） */
    val scope: StateFlow<QqSourceScope> = filter.map { selected ->
        when (target.kind) {
            SourceKind.CONVERSATION -> QqSourceScope(groupKey = target.key, senderKey = selected?.key)
            SourceKind.SENDER -> QqSourceScope(senderKey = target.key, groupKey = selected?.key)
            SourceKind.ALL -> QqSourceScope()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, QqSourceScope())

    /** 二级筛选候选（成员或会话）的全部统计 */
    private val options: StateFlow<List<QqSourceFacet>?> = baseId.flatMapLatest { id ->
        when {
            target.kind == SourceKind.ALL -> flowOf(emptyList())
            id == null -> flowOf(null)
            target.kind == SourceKind.CONVERSATION -> dao.observeConversationMembers(id)
            else -> dao.observeSenderConversations(id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前内容下的筛选候选：按占用降序，可按名称 / 群名片 / 昵称搜索 */
    val filterOptions: StateFlow<List<QqSourceFacet>?> = combine(options, content, filterSearch) { list, kind, query ->
        val q = query.trim()
        list?.filter { option ->
            option.countOf(kind) > 0 &&
                (q.isEmpty() || option.name.contains(q, ignoreCase = true) || option.alias.contains(q, ignoreCase = true))
        }?.sortedByDescending { it.bytesOf(kind) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 不含二级筛选的总量（筛选条「全部」与内容切换上显示） */
    val baseFacet: StateFlow<QqSourceFacet?> = lookup.map { it?.facet }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前范围（含二级筛选）的分类统计：筛选时取该成员 / 会话的最新聚合，否则为整个分类 */
    val currentFacet: StateFlow<QqSourceFacet?> = combine(baseFacet, options, filter) { base, list, selected ->
        if (selected == null) base else list?.firstOrNull { it.id == selected.id } ?: selected
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前范围在当前内容下的数量与占用 */
    val stats: StateFlow<QqScopeStats?> = combine(currentFacet, content) { facet, kind ->
        facet?.let { QqScopeStats(it.countOf(kind), it.bytesOf(kind)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val photos: Flow<PagingData<GalleryTimelineItem>> =
        combine(scopeIds.filterNotNull(), content, sort) { ids, kind, order -> Triple(ids, kind, order) }
            .distinctUntilChanged()
            .flatMapLatest { (ids, kind, order) ->
                val pager = Pager(
                    PagingConfig(pageSize = 60, prefetchDistance = 120, initialLoadSize = 60, enablePlaceholders = false),
                ) { dao.pagingScope(QqScopeSql.page(ids, kind, QqScopeOrder.valueOf(order.name))) }.flow
                // 分组计数固化为热流：分析 / 扫描期间 PagingSource 频繁失效，每代分页只取缓存值
                if (order.byDate) {
                    pager.withDateHeaders(bucketCounts { dao.observeBuckets(QqScopeSql.dateBuckets(ids, kind)) })
                } else {
                    pager.withSizeHeaders(bucketCounts { dao.observeBuckets(QqScopeSql.sizeBuckets(ids, kind)) })
                }
            }
            .cachedIn(viewModelScope)

    private fun bucketCounts(query: () -> Flow<List<ImageDao.BucketCountRow>>): StateFlow<Map<Long, Int>?> =
        query().map { rows -> rows.associate { it.bucketKey to it.count } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- 偏好 ----

    fun setSort(next: SourceSort) {
        viewModelScope.launch { settings.setQqSourceSort(next) }
    }

    fun setColumns(next: Int) {
        viewModelScope.launch { settings.setGalleryColumns(next) }
    }

    fun setContent(next: QqContent) {
        content.value = next
    }

    fun selectFilter(next: QqSourceFacet?) {
        filter.value = next
    }

    fun createSubdir(name: String) = settings.addExportSubdir(name)

    fun consumeMessage() {
        _message.value = null
    }

    // ---- 多选与看图页操作 ----

    suspend fun sizeOf(ids: Collection<Long>): Long = images.sumSizeByIds(ids.toList())

    /** 当前筛选范围、当前内容下的全部图片（全选） */
    suspend fun allIds(): Set<Long> {
        val ids = scopeIds.value ?: return emptySet()
        return dao.queryIds(QqScopeSql.ids(ids, content.value)).toSet()
    }

    suspend fun ocr(id: Long): OcrTextEntity? = images.getOcr(id)

    fun share(id: Long, onReady: (ExternalViewTarget) -> Unit) {
        viewModelScope.launch { imageFiles.prepareShare(id)?.let(onReady) }
    }

    /** 收藏 / 取消收藏；已处于目标状态的图片不重复写入 */
    fun setFavorite(ids: Collection<Long>, favorite: Boolean, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val changed = images.getByIds(ids.toList()).filter { it.isFavorite != favorite }.map { it.id }
            images.setFavoriteBatch(changed, favorite)
            onDone(changed.size)
        }
    }

    /** [notify] = false 时不发页面消息（看图页自己用 Toast 反馈，避免被预览窗口挡住） */
    fun moveToTrash(ids: Collection<Long>, notify: Boolean = true, options: PictureDeleteOptions = PictureDeleteOptions(), onDone: (Int) -> Unit = {}) {
        if (ids.isEmpty()) return
        appScope.launch {
            val result = quarantine.quarantinePictures(ids, options, "按 QQ 来源手动删除", scope.value)
            if (notify) _message.value = result.message()
            withContext(Dispatchers.Main) { onDone(result.selectedProcessed) }
        }
    }

    fun deletePermanently(ids: Collection<Long>, notify: Boolean = true, options: PictureDeleteOptions = PictureDeleteOptions(), onDone: (Int) -> Unit = {}) {
        if (ids.isEmpty()) return
        appScope.launch {
            val result = quarantine.deletePicturesPermanently(ids, options, scope.value)
            if (notify) _message.value = result.message(permanent = true)
            withContext(Dispatchers.Main) { onDone(result.selectedProcessed) }
        }
    }

    /** 保存到 Pictures/QAlbum[/子目录]；[alsoTrash] 只对保存成功的原图移入回收站 */
    fun save(ids: Collection<Long>, subdir: String?, alsoTrash: Boolean, onDone: (saved: Int) -> Unit = {}) {
        if (ids.isEmpty()) return
        appScope.launch {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !imageFiles.canExportPreQ()) {
                _message.value = "保存失败：需要存储权限，请在系统设置中授予后重试"
                return@launch
            }
            val saved = ids.filter { imageFiles.exportToGallery(it, subdir) }
            val trashed = if (alsoTrash && saved.isNotEmpty()) {
                quarantine.quarantineBatch(saved.map { it to "保存后移入回收站" })
            } else {
                0
            }
            val failed = ids.size - saved.size
            _message.value = buildString {
                append("已保存 ${saved.size} 张")
                if (alsoTrash) append("，移入回收站 $trashed 张")
                if (failed > 0) append("；$failed 张保存失败（未移入回收站）")
            }
            withContext(Dispatchers.Main) { onDone(saved.size) }
        }
    }

    // ---- 按范围整体清理（统计直接查库，文件逐组核查放到实际执行时） ----

    val confirmation = MutableStateFlow<QqTrashConfirmation?>(null)
    private var countsGeneration = 0L
    val progress = repository.progress
    val repositoryMessage = repository.message

    val canTrashScope: Boolean get() = target.kind != SourceKind.ALL

    fun requestScopeTrash() {
        if (!canTrashScope || repository.progress.value != null) return
        val ids = scopeIds.value ?: return
        confirmation.value = QqTrashConfirmation(scope.value, ids, counts = null)
        refreshCounts()
    }

    /** 调整清理选项（内容、共用、关联版本）后重新统计 */
    fun updateScopeTrash(transform: (QqTrashConfirmation) -> QqTrashConfirmation) {
        val current = confirmation.value ?: return
        confirmation.value = transform(current).copy(counts = null)
        refreshCounts()
    }

    private fun refreshCounts() {
        val request = confirmation.value ?: return
        val generation = ++countsGeneration
        viewModelScope.launch {
            val counts = if (request.contents.isEmpty()) {
                QqSourceCounts(0, 0, 0, 0, 0, 0)
            } else {
                dao.queryCounts(QqScopeSql.counts(request.ids, request.contents, request.includeShared))
            }
            // 世代号拒绝过期查询；值相等判断弹窗是否仍对应同一请求
            if (generation == countsGeneration && confirmation.value == request) confirmation.value = request.copy(counts = counts)
        }
    }

    fun dismissScopeTrash() {
        confirmation.value = null
    }

    fun confirmScopeTrash() {
        val request = confirmation.value ?: return
        if (request.counts == null) return
        confirmation.value = null
        repository.trash(request.scope, request.includeShared, request.linkedVersions, request.contents)
    }

    fun acknowledgeRepositoryMessage() = repository.acknowledgeMessage()
}

/** 删除结果文案：收藏 / 保留的图片会被跳过，文件访问未就绪时一张也删不掉，两种情况都要说清楚 */
internal fun removalMessage(action: String, done: Int, total: Int): String = when {
    done == 0 -> "${action.removePrefix("已")}失败：图片已收藏或保留，或文件访问未就绪"
    done < total -> "$action $done 张；${total - done} 张未处理（已收藏或保留，或文件访问失败）"
    else -> "$action $done 张"
}
