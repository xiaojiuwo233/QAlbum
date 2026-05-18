package com.xjw.qalbum.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xjw.qalbum.domain.model.AccessMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 顶层扩展：每个 Context 共享同一个 DataStore 实例
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "qalbum_settings")

/**
 * 设置仓库：通过 DataStore Preferences 持久化用户配置
 *
 * 这里只放轻量级、跨页面共享的配置；耗时/大型配置应放数据库。
 */
class SettingsRepository(private val context: Context) {

    private val ds = context.dataStore

    // ---- 权限模式 ----

    val accessMode: Flow<AccessMode> = ds.data.map { prefs ->
        val raw = prefs[Keys.ACCESS_MODE] ?: AccessMode.SHIZUKU.name
        runCatching { AccessMode.valueOf(raw) }.getOrDefault(AccessMode.SHIZUKU)
    }

    suspend fun setAccessMode(mode: AccessMode) {
        ds.edit { it[Keys.ACCESS_MODE] = mode.name }
    }

    // ---- 扫描路径 ----

    val chatpicPath: Flow<String> = ds.data.map { it[Keys.CHATPIC_PATH] ?: DEFAULT_CHATPIC_PATH }

    suspend fun setChatpicPath(path: String) {
        ds.edit { it[Keys.CHATPIC_PATH] = path }
    }

    // ---- AI 配置 ----

    val aiEnabled: Flow<Boolean> = ds.data.map { it[Keys.AI_ENABLED] ?: false }

    suspend fun setAiEnabled(enabled: Boolean) {
        ds.edit { it[Keys.AI_ENABLED] = enabled }
    }

    val aiEndpoint: Flow<String> = ds.data.map { it[Keys.AI_ENDPOINT] ?: "" }

    suspend fun setAiEndpoint(endpoint: String) {
        ds.edit { it[Keys.AI_ENDPOINT] = endpoint }
    }

    val aiApiKey: Flow<String> = ds.data.map { it[Keys.AI_API_KEY] ?: "" }

    suspend fun setAiApiKey(key: String) {
        ds.edit { it[Keys.AI_API_KEY] = key }
    }

    val aiModel: Flow<String> = ds.data.map { it[Keys.AI_MODEL] ?: "gpt-4o-mini" }

    suspend fun setAiModel(model: String) {
        ds.edit { it[Keys.AI_MODEL] = model }
    }

    val aiEmbeddingModel: Flow<String> = ds.data.map { it[Keys.AI_EMBEDDING_MODEL] ?: "text-embedding-3-small" }

    suspend fun setAiEmbeddingModel(model: String) {
        ds.edit { it[Keys.AI_EMBEDDING_MODEL] = model }
    }

    // ---- 隔离区保留时长 ----

    val quarantineRetentionDays: Flow<Int> = ds.data.map { it[Keys.QUARANTINE_DAYS] ?: 30 }

    suspend fun setQuarantineRetentionDays(days: Int) {
        ds.edit { it[Keys.QUARANTINE_DAYS] = days }
    }

    // ---- 主题动态取色 ----

    val dynamicColorEnabled: Flow<Boolean> = ds.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        ds.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    companion object {
        const val DEFAULT_CHATPIC_PATH =
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
    }

    private object Keys {
        val ACCESS_MODE = stringPreferencesKey("access_mode")
        val CHATPIC_PATH = stringPreferencesKey("chatpic_path")

        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_ENDPOINT = stringPreferencesKey("ai_endpoint")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_EMBEDDING_MODEL = stringPreferencesKey("ai_embedding_model")

        val QUARANTINE_DAYS = intPreferencesKey("quarantine_days")

        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}
