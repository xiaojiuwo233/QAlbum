package com.xjw.qalbum.di

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.RootFileAccess
import com.xjw.qalbum.access.ShizukuFileAccess
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.settings.SettingsViewModel
import com.xjw.qalbum.worker.AnalysisWorker
import com.xjw.qalbum.worker.IncrementalScanWorker
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * Koin 主模块
 *
 * 组织方式：按职责分组 —— 数据 / 访问 / 网络 / ViewModel / Worker
 * 后续 Phase 引入新模块时可以拆分成多个 module，再 .modules(a, b, c)
 */
val appModule = module {

    // ---- 数据 ----

    single { AppDatabase.build(get()) }
    single { get<AppDatabase>().imageDao() }
    single { get<AppDatabase>().ocrDao() }
    single { get<AppDatabase>().tagDao() }
    single { get<AppDatabase>().cleanupDao() }
    single { get<AppDatabase>().matchedMessageDao() }
    single { get<AppDatabase>().userActionDao() }

    single { SettingsRepository(get()) }

    // ---- 文件访问 ----

    single { ShizukuFileAccess() }
    single { RootFileAccess() }
    single { AccessManager(get(), get(), get()) }

    // ---- 网络（Phase 4 AI 使用，先初始化好） ----

    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    // ---- ViewModel ----

    viewModel { SettingsViewModel(settings = get(), accessManager = get()) }

    // ---- Worker ----

    worker { IncrementalScanWorker(get(), get()) }
    worker { AnalysisWorker(get(), get()) }
}
