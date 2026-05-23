package com.xjw.qalbum.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.ai.SemanticHit
import com.xjw.qalbum.service.ai.SemanticSearchEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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

    val aiEnabled: StateFlow<Boolean> = settings.aiEnabled
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
        scheduleSearch()
    }

    fun setMode(mode: SearchMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        scheduleSearch()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val text = _query.value
        if (text.isBlank()) {
            _state.value = SearchUiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            _state.value = SearchUiState.Loading
            try {
                when (_mode.value) {
                    SearchMode.OCR -> runOcrSearch(text)
                    SearchMode.Semantic -> runSemanticSearch(text)
                }
            } catch (t: Throwable) {
                _state.value = SearchUiState.Failed(t.message ?: "查询失败")
            }
        }
    }

    private suspend fun runOcrSearch(text: String) {
        val sanitized = sanitizeForFts(text)
        val hits = imageRepository.search(sanitized, limit = 100)
        _state.value = if (hits.isEmpty()) SearchUiState.Empty else SearchUiState.OcrResult(hits)
    }

    private suspend fun runSemanticSearch(text: String) {
        val hits = semanticEngine.searchByText(text, topK = 50)
        _state.value = if (hits.isEmpty()) SearchUiState.Empty else SearchUiState.SemanticResult(hits)
    }

    /** 把用户输入按空格拆词，每个词加 `*` 做前缀匹配，OR 拼接 */
    private fun sanitizeForFts(input: String): String {
        val cleaned = input.replace('"', ' ').replace('\'', ' ')
        return cleaned.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" OR ") { "$it*" }
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
