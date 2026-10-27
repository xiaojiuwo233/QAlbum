package com.xjw.qalbum.access

import com.xjw.qalbum.data.db.dao.QqSourceDao
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 高级功能（读取并解密 QQ 数据库：按聊天来源浏览、按聊天清理、发送图片缓存 / 群聊旧图 / 聊天记录已删除）能不能用。
 * 默认关闭，只能在「设置 → 文件访问」选了 Root 时开启（第一次开要看风险提示）。读取要 Root；以前用 Root 读过来源的，
 * 浏览和清理只要文件访问，切到 Shizuku 后照常能用。没开高级功能时这些入口都不显示。
 */
class ChatFeatures(settings: SettingsRepository, sources: QqSourceDao) {
    private val enabled: Flow<Boolean> = settings.advancedFeatures.distinctUntilChanged()

    /** 能读取聊天来源（开了高级功能、Root 模式） */
    val canRead: Flow<Boolean> = combine(enabled, settings.accessMode) { on, mode -> on && mode == AccessMode.ROOT }
        .distinctUntilChanged()

    /** 显示聊天来源相关的入口 */
    val available: Flow<Boolean> = combine(enabled, canRead, sources.observeAccounts().map { it.isNotEmpty() }) { on, root, read ->
        on && (root || read)
    }.distinctUntilChanged()
}
