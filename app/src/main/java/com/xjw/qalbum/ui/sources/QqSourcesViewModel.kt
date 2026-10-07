package com.xjw.qalbum.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.dao.QqFacetKindCount
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.db.dao.QqSourceSummary
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.SourceFacetOrder
import com.xjw.qalbum.qq.QqSourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 来源首页的三个分类页签 */
enum class SourceTab(val label: String, val kind: Int, val chatType: Int) {
    GROUPS("群聊", kind = 0, chatType = 2),
    PRIVATE("私聊", kind = 0, chatType = 1),
    SENDERS("发送人", kind = 1, chatType = -1),
}

/** 浏览页的目标：某个会话 / 某个发送人 / 全部已关联图片 */
enum class SourceKind(val arg: String) {
    CONVERSATION("conversation"),
    SENDER("sender"),
    ALL("all"),
}

data class SourceBrowseTarget(val kind: SourceKind, val key: String) {
    companion object {
        val All = SourceBrowseTarget(SourceKind.ALL, "all")

        fun fromArgs(kind: String?, key: String?): SourceBrowseTarget {
            val resolved = SourceKind.entries.firstOrNull { it.arg == kind } ?: SourceKind.ALL
            return if (resolved == SourceKind.ALL || key.isNullOrBlank()) All else SourceBrowseTarget(resolved, key)
        }
    }
}

/**
 * QQ 来源首页：读取 / 刷新来源索引，按群聊、私聊、发送人列出分类及其占用空间。
 */
class QqSourcesViewModel(
    val repository: QqSourceRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    init {
        repository.startFacetCache()
    }

    val tab = MutableStateFlow(SourceTab.GROUPS)
    val search = MutableStateFlow("")

    val order: StateFlow<SourceFacetOrder> = settings.qqSourceFacetOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceFacetOrder.BYTES)

    val summary: StateFlow<QqSourceSummary?> = repository.dao.observeSummary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accounts = repository.dao.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 每个页签的分类数（页签标签上显示） */
    val tabCounts: StateFlow<Map<SourceTab, Int>> = repository.dao.observeFacetKindCounts()
        .map { rows -> SourceTab.entries.associateWith { tab -> rows.countFor(tab) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val facets: StateFlow<List<QqSourceFacet>?> =
        combine(tab, search.debounce { if (it.isBlank()) 0L else 180L }, order) { t, q, o -> Triple(t, q.trim(), o) }
            .distinctUntilChanged()
            .flatMapLatest { (t, q, o) -> repository.dao.observeFacets(t.kind, t.chatType, q, o.ordinal) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val progress = repository.progress
    val message = repository.message
    val report = repository.report
    val sourcesComplete = repository.sourcesComplete

    fun selectTab(next: SourceTab) {
        if (tab.value == next) return
        search.value = ""
        tab.value = next
    }

    fun setOrder(next: SourceFacetOrder) {
        viewModelScope.launch { settings.setQqSourceFacetOrder(next) }
    }

    fun refresh() = repository.refresh()
    fun cancel() = repository.cancel()
    fun clearIndex() = repository.clear()
    fun acknowledgeMessage() = repository.acknowledgeMessage()
}

private fun List<QqFacetKindCount>.countFor(tab: SourceTab): Int =
    filter { it.kind == tab.kind && (tab.chatType < 0 || it.chatType == tab.chatType || (tab == SourceTab.GROUPS && it.chatType == 3)) }.sumOf { it.count }
