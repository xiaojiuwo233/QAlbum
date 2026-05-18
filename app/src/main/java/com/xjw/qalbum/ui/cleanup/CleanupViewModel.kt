package com.xjw.qalbum.ui.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.data.db.entity.CleanupBatchEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.service.analysis.AnalysisPipeline
import com.xjw.qalbum.service.analysis.AnalysisProgress
import com.xjw.qalbum.service.analysis.AnalysisState
import com.xjw.qalbum.service.cleanup.CleanupBatch
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.service.cleanup.CleanupStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CleanupViewModel(
    private val cleanupController: CleanupController,
    private val analysisPipeline: AnalysisPipeline,
) : ViewModel() {

    val analysisState: StateFlow<AnalysisState> = analysisPipeline.state
    val analysisProgress: StateFlow<AnalysisProgress?> = analysisPipeline.progress

    val recentBatches: StateFlow<List<CleanupBatchEntity>> = cleanupController.observeRecentBatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val quarantine: StateFlow<List<QuarantineEntity>> = cleanupController.observeQuarantine()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lastBatch = MutableStateFlow<CleanupBatch?>(null)
    val lastBatch: StateFlow<CleanupBatch?> = _lastBatch.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _strategy = MutableStateFlow<CleanupStrategy>(CleanupStrategy.Balanced)
    val strategy: StateFlow<CleanupStrategy> = _strategy.asStateFlow()

    fun setStrategy(s: CleanupStrategy) {
        _strategy.value = s
    }

    fun startAnalysis() = analysisPipeline.start()
    fun cancelAnalysis() = analysisPipeline.cancel()

    fun generateBatch() {
        viewModelScope.launch {
            _busy.value = true
            try {
                _lastBatch.value = cleanupController.generateBatch(_strategy.value)
            } finally {
                _busy.value = false
            }
        }
    }

    fun applyBatch(batchId: Long, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val moved = cleanupController.applyBatch(batchId)
                onDone(moved)
            } finally {
                _busy.value = false
            }
        }
    }

    fun restore(quarantineId: Long) {
        viewModelScope.launch { cleanupController.restore(quarantineId) }
    }

    fun purge(quarantineId: Long) {
        viewModelScope.launch { cleanupController.purge(quarantineId) }
    }
}
