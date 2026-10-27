package com.xjw.qalbum.service.demo

import com.xjw.qalbum.data.db.dao.QqOriginDetails
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 演示模式（截图、宣传用）：图片显示成生成的示意图（大小、时间等数据照旧是真的），聊天来源里的群、人和图片目录换成虚构的
 * （转发的聊天记录除外），删除、归档、压缩、保存、分享、恢复等改动或带出图片的操作都不执行。开关在「关于」里。
 */
class DemoMode(private val settings: SettingsRepository, scope: CoroutineScope) {
    // null = 设置还没读出来
    private val state = MutableStateFlow<Boolean?>(null)

    init {
        scope.launch { settings.demoMode.collect { state.value = it } }
    }

    val enabled: StateFlow<Boolean> = state.map { it == true }.stateIn(scope, SharingStarted.Eagerly, false)

    /** 同 [enabled]，但读出设置后才发值：换名称的列表等它，免得开着演示模式时先闪一下真名 */
    val known: Flow<Boolean> = state.filterNotNull()

    val isOn: Boolean get() = state.value == true

    /** 读图片前判断：设置还没读出来时等一下，免得开着演示模式时先露出真图 */
    suspend fun current(): Boolean = known.first()

    suspend fun set(on: Boolean) = settings.setDemoMode(on)

    private val _blocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 拦下了一次操作（界面根部提示一句） */
    val blocked: SharedFlow<Unit> = _blocked.asSharedFlow()

    /** 改动图片的操作先调这个：演示模式下拦住（并提示），返回 true */
    fun blocks(): Boolean {
        if (!isOn) return false
        _blocked.tryEmit(Unit)
        return true
    }

    /** 演示模式下把分类的名称换成虚构的 */
    fun facets(source: Flow<List<QqSourceFacet>>): Flow<List<QqSourceFacet>> =
        combine(source, known) { list, on -> if (on) list.map(DemoNames::facet) else list }

    fun facet(source: Flow<QqSourceFacet?>): Flow<QqSourceFacet?> =
        combine(source, known) { facet, on -> if (on) facet?.let(DemoNames::facet) else facet }

    fun origins(source: Flow<List<QqOriginDetails>>): Flow<List<QqOriginDetails>> =
        combine(source, known) { list, on -> if (on) list.map(DemoNames::origin) else list }
}
