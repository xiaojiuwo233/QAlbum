package com.xjw.qalbum.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.service.storage.StorageMaintenance
import kotlinx.coroutines.launch

/** 设置 → 存储空间（统计与操作都在 [StorageMaintenance]，精简离开页面也不中断） */
class StorageViewModel(private val maintenance: StorageMaintenance) : ViewModel() {
    val usage = maintenance.usage
    val progress = maintenance.progress
    val message = maintenance.message

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { runCatching { maintenance.refresh() } }
    }

    fun slim() = maintenance.slim()
    fun clearCache() = maintenance.clearCache()
    fun clearLogs() = maintenance.clearLogs()
    fun acknowledgeMessage() = maintenance.acknowledgeMessage()
}
