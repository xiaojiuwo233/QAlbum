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
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.db.dao.QqSourceName
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 按来源范围整体清理前的确认状态 */
data class QqTrashConfirmation(
    val scope: QqSourceScope,
    val counts: QqSourceCounts?,
    val includeShared: Boolean = false,
    val ids: List<Long>? = null,
)

/**
 * 来源浏览页：某个会话（可再按成员筛选）/ 某个发送人（可再按会话筛选）/ 全部已关联图片。
 *
 * 排序（日期 / 大小 × 升降序）持久化；列表按排序插入日期或大小分组标题。
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

    val info: StateFlow<QqSourceName?> = when (target.kind) {
        SourceKind.CONVERSATION -> dao.observeConversationName(target.key)
        SourceKind.SENDER -> dao.observeSenderName(target.key)
        SourceKind.ALL -> flowOf(QqSourceName("全部来源图片", 0))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val baseScope: QqSourceScope = when (target.kind) {
        SourceKind.CONVERSATION -> QqSourceScope(groupKey = target.key)
        SourceKind.SENDER -> QqSourceScope(senderKey = target.key)
        SourceKind.ALL -> QqSourceScope()
    }

    val scope: StateFlow<QqSourceScope> = filter.map { selected ->
        when (target.kind) {
            SourceKind.CONVERSATION -> baseScope.copy(senderKey = selected?.key)
            SourceKind.SENDER -> baseScope.copy(groupKey = selected?.key)
            SourceKind.ALL -> baseScope
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, baseScope)

    /** 不含二级筛选的总量（筛选条「全部」标签用） */
    val totalStats: StateFlow<QqScopeStats?> = dao.observeScopeStats(baseScope.groupKey, baseScope.senderKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前筛选结果的数量与占用空间 */
    val stats: StateFlow<QqScopeStats?> = scope.flatMapLatest { dao.observeScopeStats(it.groupKey, it.senderKey) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 二级筛选候选（成员或会话），按占用空间降序 */
    val filterOptions: StateFlow<List<QqSourceFacet>?> =
        filterSearch.debounce { if (it.isBlank()) 0L else 180L }.map { it.trim() }.distinctUntilChanged()
            .flatMapLatest { query ->
                when (target.kind) {
                    SourceKind.CONVERSATION -> dao.observeConversationMembers(target.key, query)
                    SourceKind.SENDER -> dao.observeSenderConversations(target.key, query)
                    SourceKind.ALL -> flowOf(emptyList())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val photos: Flow<PagingData<GalleryTimelineItem>> =
        combine(scope, sort) { s, order -> s to order }
            .distinctUntilChanged()
            .flatMapLatest { (s, order) ->
                val pager = Pager(
                    PagingConfig(pageSize = 60, prefetchDistance = 120, initialLoadSize = 60, enablePlaceholders = false),
                ) {
                    when (order) {
                        SourceSort.DATE_DESC -> dao.imagesNewest(s.groupKey, s.senderKey)
                        SourceSort.DATE_ASC -> dao.imagesOldest(s.groupKey, s.senderKey)
                        SourceSort.SIZE_DESC -> dao.imagesLargest(s.groupKey, s.senderKey)
                        SourceSort.SIZE_ASC -> dao.imagesSmallest(s.groupKey, s.senderKey)
                    }
                }.flow
                // 分组计数固化为热流：分析 / 扫描期间 PagingSource 频繁失效，每代分页只取缓存值
                if (order.byDate) {
                    pager.withDateHeaders(bucketCounts { dao.observeDateBuckets(s.groupKey, s.senderKey) })
                } else {
                    pager.withSizeHeaders(bucketCounts { dao.observeSizeBuckets(s.groupKey, s.senderKey) })
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

    fun selectFilter(next: QqSourceFacet?) {
        filter.value = next
    }

    fun createSubdir(name: String) = settings.addExportSubdir(name)

    fun consumeMessage() {
        _message.value = null
    }

    // ---- 多选与看图页操作 ----

    suspend fun sizeOf(ids: Collection<Long>): Long = images.sumSizeByIds(ids.toList())

    /** 当前筛选范围内全部图片（全选） */
    suspend fun allIds(): Set<Long> = scope.value.let { dao.scopeIds(it.groupKey, it.senderKey).toSet() }

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
            val result=quarantine.quarantinePictures(ids,options,"按 QQ 来源手动删除",scope.value)
            val moved=result.selectedProcessed
            if (notify) _message.value = result.message()
            withContext(Dispatchers.Main) { onDone(moved) }
        }
    }

    fun deletePermanently(ids: Collection<Long>, notify: Boolean = true, options: PictureDeleteOptions = PictureDeleteOptions(), onDone: (Int) -> Unit = {}) {
        if (ids.isEmpty()) return
        appScope.launch {
            val result=quarantine.deletePicturesPermanently(ids,options,scope.value)
            val deleted=result.selectedProcessed
            if (notify) _message.value = result.message(permanent=true)
            withContext(Dispatchers.Main) { onDone(deleted) }
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

    // ---- 按范围整体清理（含共用图片统计） ----

    val confirmation = MutableStateFlow<QqTrashConfirmation?>(null)
    private var countsGeneration = 0L
    val progress = repository.progress
    val repositoryMessage = repository.message

    val canTrashScope: Boolean get() = target.kind != SourceKind.ALL

    fun requestScopeTrash() {
        if (!canTrashScope || repository.progress.value != null) return
        confirmation.value = QqTrashConfirmation(scope.value, null)
        updateShared(false)
    }

    fun updateShared(include: Boolean) {
        val request = confirmation.value ?: return
        val next = request.copy(counts = null, includeShared = include)
        val generation = ++countsGeneration
        confirmation.value = next
        viewModelScope.launch {
            val counts = dao.counts(next.scope.groupKey, next.scope.senderKey, include)
            val ids = dao.scopeIds(next.scope.groupKey,next.scope.senderKey)
            // 世代号拒绝过期查询；值相等判断弹窗是否仍对应同一请求
            if (generation == countsGeneration && confirmation.value == next) confirmation.value = next.copy(counts = counts,ids=ids)
        }
    }

    fun dismissScopeTrash() {
        confirmation.value = null
    }

    fun confirmScopeTrash(options: PictureDeleteOptions = PictureDeleteOptions()) {
        val request = confirmation.value ?: return
        if (request.counts == null) return
        confirmation.value = null
        repository.trash(request.scope, options.includeShared,options.linkedVersions)
    }

    fun acknowledgeRepositoryMessage() = repository.acknowledgeMessage()
}

/** 删除结果文案：收藏 / 保留的图片会被跳过，文件访问未就绪时一张也删不掉，两种情况都要说清楚 */
internal fun removalMessage(action: String, done: Int, total: Int): String = when {
    done == 0 -> "${action.removePrefix("已")}失败：图片已收藏或保留，或文件访问未就绪"
    done < total -> "$action $done 张；${total - done} 张未处理（已收藏或保留，或文件访问失败）"
    else -> "$action $done 张"
}
