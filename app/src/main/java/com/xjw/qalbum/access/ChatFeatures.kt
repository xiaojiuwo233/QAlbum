package com.xjw.qalbum.access

import com.xjw.qalbum.data.db.dao.QqSourceDao
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 聊天来源相关的功能（按聊天来源浏览、按聊天清理、发送图片缓存 / 群聊旧图 / 聊天记录已删除）能不能用。
 * 读取 QQ 数据库要 Root；以前用 Root 读过来源的，浏览和清理只要文件访问，切到 Shizuku 后照常能用。
 * Shizuku 模式下没读过来源就不显示这些入口，也不显示「需要 Root」之类的提示。
 */
class ChatFeatures(settings: SettingsRepository, sources: QqSourceDao) {
    /** 能读取聊天来源（Root 模式） */
    val canRead: Flow<Boolean> = settings.accessMode.map { it == AccessMode.ROOT }.distinctUntilChanged()

    /** 显示聊天来源相关的入口 */
    val available: Flow<Boolean> = combine(canRead, sources.observeAccounts().map { it.isNotEmpty() }) { root, read -> root || read }
        .distinctUntilChanged()
}
