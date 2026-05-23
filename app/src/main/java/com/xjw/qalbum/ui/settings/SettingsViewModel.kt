package com.xjw.qalbum.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.ScanProgress
import com.xjw.qalbum.service.indexer.ScanController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel：
 * - 暴露当前访问模式 / 路径 / 动态取色等设置
 * - 提供"检查权限"动作，把 Shizuku/Root 可用性同步回 UI
 * - 提供"立即扫描"动作（包装 [ScanController]）
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val accessManager: AccessManager,
    private val scanController: ScanController,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.accessMode,
        settings.chatpicPath,
        settings.dynamicColorEnabled,
        settings.aiEnabled,
        settings.aiEndpoint,
    ) { mode, path, dynamicColor, aiEnabled, aiEndpoint ->
        SettingsBase(mode, path, dynamicColor, aiEnabled, aiEndpoint)
    }.combine(
        combine(settings.aiApiKey, settings.aiModel, settings.aiEmbeddingModel) { k, m, em ->
            Triple(k, m, em)
        }
    ) { base, ai ->
        SettingsUiState(
            accessMode = base.accessMode,
            chatpicPath = base.chatpicPath,
            dynamicColorEnabled = base.dynamicColorEnabled,
            aiEnabled = base.aiEnabled,
            aiEndpoint = base.aiEndpoint,
            aiApiKey = ai.first,
            aiModel = ai.second,
            aiEmbeddingModel = ai.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    private data class SettingsBase(
        val accessMode: AccessMode,
        val chatpicPath: String,
        val dynamicColorEnabled: Boolean,
        val aiEnabled: Boolean,
        val aiEndpoint: String,
    )

    private val _accessStatus = MutableStateFlow(AccessStatus.Unknown)
    val accessStatus: StateFlow<AccessStatus> = _accessStatus.asStateFlow()

    val scanRunning: StateFlow<Boolean> = scanController.running
    val scanProgress: StateFlow<ScanProgress?> = scanController.progress

    fun setAccessMode(mode: AccessMode) {
        viewModelScope.launch {
            settings.setAccessMode(mode)
            checkAccess()
        }
    }

    fun setChatpicPath(path: String) {
        viewModelScope.launch { settings.setChatpicPath(path) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColorEnabled(enabled) }
    }

    fun checkAccess() {
        viewModelScope.launch {
            val provider = accessManager.current()
            val ok = runCatching { provider.isAvailable() }.getOrDefault(false)
            _accessStatus.value = if (ok) AccessStatus.Ready else AccessStatus.NotReady
        }
    }

    fun startFullScan() = scanController.startFullScan()
    fun startIncrementalScan() = scanController.startIncrementalScan()
    fun cancelScan() = scanController.cancel()

    // ---- AI 配置 ----

    fun setAiEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setAiEnabled(enabled) }
    }
    fun setAiEndpoint(endpoint: String) {
        viewModelScope.launch { settings.setAiEndpoint(endpoint.trim()) }
    }
    fun setAiApiKey(key: String) {
        viewModelScope.launch { settings.setAiApiKey(key.trim()) }
    }
    fun setAiModel(model: String) {
        viewModelScope.launch { settings.setAiModel(model.trim()) }
    }
    fun setAiEmbeddingModel(model: String) {
        viewModelScope.launch { settings.setAiEmbeddingModel(model.trim()) }
    }
}

data class SettingsUiState(
    val accessMode: AccessMode = AccessMode.SHIZUKU,
    val chatpicPath: String = SettingsRepository.DEFAULT_CHATPIC_PATH,
    val dynamicColorEnabled: Boolean = true,
    val aiEnabled: Boolean = false,
    val aiEndpoint: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "gpt-4o-mini",
    val aiEmbeddingModel: String = "text-embedding-3-small",
)

enum class AccessStatus { Unknown, Ready, NotReady }
