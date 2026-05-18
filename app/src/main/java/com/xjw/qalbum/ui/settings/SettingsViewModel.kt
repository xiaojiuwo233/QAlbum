package com.xjw.qalbum.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
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
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val accessManager: AccessManager,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.accessMode,
        settings.chatpicPath,
        settings.dynamicColorEnabled,
    ) { mode, path, dynamicColor ->
        SettingsUiState(
            accessMode = mode,
            chatpicPath = path,
            dynamicColorEnabled = dynamicColor,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    private val _accessStatus = MutableStateFlow(AccessStatus.Unknown)
    val accessStatus: StateFlow<AccessStatus> = _accessStatus.asStateFlow()

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
}

data class SettingsUiState(
    val accessMode: AccessMode = AccessMode.SHIZUKU,
    val chatpicPath: String = SettingsRepository.DEFAULT_CHATPIC_PATH,
    val dynamicColorEnabled: Boolean = true,
)

enum class AccessStatus { Unknown, Ready, NotReady }
