package com.xjw.qalbum.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.ai.SemanticHit
import com.xjw.qalbum.service.ai.SemanticSearchEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class SearchMode {
    /** 基于 OCR 文本的 FTS4 全文搜索 */
    OCR,
    /** 基于 embedding 的 AI 语义搜索 */
    Semantic,
}

/**
 * 搜索 ViewModel
 *
 * 两种模式可切：OCR 全文 / AI 语义。
 * - OCR 模式：FTS4 MATCH（带 snippet 高亮）
 * - 语义模式：通过 [SemanticSearchEngine] 把查询文本转 embedding，与库中向量做 cosine
 */
class SearchViewModel(
    private val imageRepository: ImageRepository,
    private val semanticEngine: SemanticSearchEngine,
    settings: SettingsRepository,
) : ViewModel() {

    val aiEnabled: StateFlow<Boolean> = settings.aiConnection.map { it.enabled && it.endpoint.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _mode = MutableStateFlow(SearchMode.OCR)
    val mode: StateFlow<SearchMode> = _mode.asStateFlow()

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(text: String) {
        _query.value = text
        if (_mode.value == SearchMode.OCR) scheduleSearch() else {
            searchJob?.cancel()
            _state.value = SearchUiState.Idle
        }
    }

    fun setMode(mode: SearchMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        scheduleSearch()
    }

    fun submitSearch() = scheduleSearch(immediate = true)

    private fun scheduleSearch(immediate: Boolean = false) {
        searchJob?.cancel()
        // 过短查询（含空）不触发：单字符在 20 万 OCR 行上的 FTS 命中集过大，逐键必卡
        val text = _query.value.trim()
        if (text.length < MIN_QUERY_CHARS) {
            _state.value = SearchUiState.Idle
            return
        }
        val mode = _mode.value
        // 防抖期间保留上一次结果，避免逐键输入时结果区在「空闲 / 加载 / 结果」之间来回闪
        if (_state.value is SearchUiState.Failed || _state.value is SearchUiState.Empty) _state.value = SearchUiState.Idle
        searchJob = viewModelScope.launch {
            if (!immediate) delay(250)
            _state.value = SearchUiState.Loading
            try {
                when (mode) {
                    SearchMode.OCR -> runOcrSearch(text)
                    SearchMode.Semantic -> runSemanticSearch(text)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 已被新输入/清空取消的旧任务不得再写共享状态（否则 Idle 之后残留「查询失败」）
                ensureActive()
                _state.value = SearchUiState.Failed(t.message ?: "查询失败")
            }
        }
    }

    private suspend fun runOcrSearch(text: String) {
        // 原始输入直接下传：MATCH 构造（CJK 逐字短语 / ASCII 前缀）由 OcrDao 内部的 FtsTokenize 完成
        val hits = imageRepository.search(text, limit = 100)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        _state.value = if (hits.isEmpty()) SearchUiState.Empty else SearchUiState.OcrResult(hits)
    }

    private suspend fun runSemanticSearch(text: String) {
        val hits = semanticEngine.searchByText(text, topK = 50)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        _state.value = if (hits.isEmpty()) SearchUiState.Empty else SearchUiState.SemanticResult(hits)
    }

    companion object {
        private const val MIN_QUERY_CHARS = 2
    }
}

sealed class SearchUiState {
    data object Idle : SearchUiState()
    data object Loading : SearchUiState()
    data object Empty : SearchUiState()
    data class OcrResult(val hits: List<OcrDao.SearchHit>) : SearchUiState()
    data class SemanticResult(val hits: List<SemanticHit>) : SearchUiState()
    data class Failed(val reason: String) : SearchUiState()
}
