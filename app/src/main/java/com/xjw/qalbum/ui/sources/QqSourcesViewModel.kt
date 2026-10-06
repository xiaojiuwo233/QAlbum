package com.xjw.qalbum.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xjw.qalbum.data.db.dao.QqSourceCounts
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.qq.QqSourceRepository
import com.xjw.qalbum.qq.QqSourceScope
import com.xjw.qalbum.service.cleanup.ImageFileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class QqSourceSelection(val group: QqSourceFacet? = null, val sender: QqSourceFacet? = null) {
    val scope get() = QqSourceScope(group?.key, sender?.key)
}
data class QqTrashConfirmation(val selection: QqSourceSelection, val counts: QqSourceCounts?, val includeShared: Boolean = false)

class QqSourcesViewModel(
    val repository: QqSourceRepository,
    settings: SettingsRepository,
    private val images: ImageRepository,
    private val imageFiles: ImageFileManager,
) : ViewModel() {
    init { repository.startFacetCache() }
    val selection = MutableStateFlow(QqSourceSelection())
    val search = MutableStateFlow("")
    val tab = MutableStateFlow(0)
    val confirmation = MutableStateFlow<QqTrashConfirmation?>(null)
    private var countsGeneration = 0L
    val linkedCount = repository.dao.observeLinkedCount().map { it ?: 0 }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val accounts = repository.dao.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val columns = settings.galleryColumns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_GALLERY_COLUMNS)
    val groups = combine(selection, search.debounce { if (it.isBlank()) 0L else 150L }, tab) { selected, query, active -> Triple(selected.sender?.key, query.trim(), active) }
        .distinctUntilChanged().flatMapLatest { (sender, query, active) ->
            when { active != 0 -> flowOf(emptyList())
                sender == null -> repository.dao.observeCachedFacets(0, query)
                else -> repository.dao.observeGroups(sender, query) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val senders = combine(selection, search.debounce { if (it.isBlank()) 0L else 150L }, tab) { selected, query, active -> Triple(selected.group?.key, query.trim(), active) }
        .distinctUntilChanged().flatMapLatest { (group, query, active) ->
            when { active != 1 -> flowOf(emptyList())
                group == null -> repository.dao.observeCachedFacets(1, query)
                else -> repository.dao.observeSenders(group, query) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val photos = combine(selection, tab) { selected, active -> selected to active }.distinctUntilChanged().flatMapLatest { (selected, active) ->
        if (active != 2) return@flatMapLatest flowOf(PagingData.empty<ImageEntity>())
        Pager(PagingConfig(pageSize = 60, prefetchDistance = 60, maxSize = 360, enablePlaceholders = false)) {
            repository.dao.images(selected.group?.key, selected.sender?.key)
        }.flow
    }.cachedIn(viewModelScope)

    fun selectGroup(facet: QqSourceFacet) { selection.value = selection.value.copy(group = facet); openTab(2) }
    fun selectSender(facet: QqSourceFacet) { selection.value = selection.value.copy(sender = facet); openTab(2) }
    fun openTab(index: Int) { search.value = ""; tab.value = index }

    fun requestTrash(selected: QqSourceSelection) {
        if (repository.progress.value != null) return
        confirmation.value = QqTrashConfirmation(selected, null)
        updateShared(false)
    }

    fun updateShared(include: Boolean) {
        val request = confirmation.value ?: return
        val next = request.copy(counts = null, includeShared = include)
        val generation = ++countsGeneration
        confirmation.value = next
        viewModelScope.launch {
            val counts = repository.dao.counts(next.selection.group?.key, next.selection.sender?.key, include)
            // StateFlow 不会替换 equals 相同的对象：首次 include=false 时 next 与 request
            // 等值，不能用引用相等判断。世代号拒绝过期查询，值相等判断弹窗是否仍有效。
            if (generation == countsGeneration && confirmation.value == next) confirmation.value = next.copy(counts = counts)
        }
    }

    fun confirmTrash() {
        val request = confirmation.value ?: return
        if (request.counts == null) return
        confirmation.value = null
        repository.trash(request.selection.scope, request.includeShared)
    }

    suspend fun ocr(id: Long) = images.getOcr(id)
    fun share(id: Long, onReady: (com.xjw.qalbum.service.cleanup.ExternalViewTarget) -> Unit) {
        viewModelScope.launch { imageFiles.prepareShare(id)?.let(onReady) }
    }
    fun favorite(image: ImageEntity) { viewModelScope.launch { images.setFavorite(image.id, !image.isFavorite) } }
}
