package com.xjw.qalbum.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.repository.ImageRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索 ViewModel
 *
 * - 当用户在搜索框输入时，做 250ms 的防抖
 * - 用 FTS4 的 MATCH 语法做全文搜索
 * - 返回带 snippet 高亮的命中
 */
class SearchViewModel(
    private val imageRepository: ImageRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(text: String) {
        _query.value = text
        searchJob?.cancel()
        if (text.isBlank()) {
            _state.value = SearchUiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            delay(250) // debounce
            _state.value = SearchUiState.Loading
            try {
                val sanitized = sanitize(text)
                val hits = imageRepository.search(sanitized, limit = 100)
                _state.value = if (hits.isEmpty()) SearchUiState.Empty else SearchUiState.Result(hits)
            } catch (t: Throwable) {
                _state.value = SearchUiState.Failed(t.message ?: "查询失败")
            }
        }
    }

    /**
     * 把用户输入的"中文 + 空格"转成 FTS MATCH 语法，
     * 例如 "发票 报销" → "发票* 报销*"（前缀匹配），并自动加 OR。
     * 引号 / 特殊字符做基础转义，避免 FTS 语法错误。
     */
    private fun sanitize(input: String): String {
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
    data class Result(val hits: List<OcrDao.SearchHit>) : SearchUiState()
    data class Failed(val reason: String) : SearchUiState()
}
