package com.xjw.qalbum.access

import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import kotlinx.coroutines.flow.first

/**
 * 根据当前模式（设置项）切换并返回对应的 [FileAccessProvider]
 *
 * 在 Koin 中以单例形式注入；Repository 层通过 [current] 拿到当前实现。
 */
class AccessManager(
    private val settingsRepository: SettingsRepository,
    private val shizukuFileAccess: ShizukuFileAccess,
    private val rootFileAccess: RootFileAccess,
) {

    /** 根据用户设置返回当前模式对应的实现 */
    suspend fun current(): FileAccessProvider {
        val mode = settingsRepository.accessMode.first()
        return providerOf(mode)
    }

    fun providerOf(mode: AccessMode): FileAccessProvider = when (mode) {
        AccessMode.SHIZUKU -> shizukuFileAccess
        AccessMode.ROOT -> rootFileAccess
    }
}
