package com.xjw.qalbum.di

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.RootFileAccess
import com.xjw.qalbum.access.ShizukuFileAccess
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.analysis.AnalysisPipeline
import com.xjw.qalbum.service.analysis.DuplicateDetector
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.service.cleanup.CleanupRecommender
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.cleanup.VaultManager
import com.xjw.qalbum.service.indexer.ImageIndexer
import com.xjw.qalbum.service.indexer.IncrementalScanner
import com.xjw.qalbum.service.indexer.ScanController
import com.xjw.qalbum.service.ocr.LocalOcrEngine
import com.xjw.qalbum.ui.dashboard.DashboardViewModel
import com.xjw.qalbum.ui.gallery.GalleryViewModel
import com.xjw.qalbum.ui.imageloading.buildImageLoader
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
    single { ImageRepository(imageDao = get(), ocrDao = get()) }

    // ---- 文件访问 ----

    single { ShizukuFileAccess() }
    single { RootFileAccess() }
    single { AccessManager(get(), get(), get()) }

    // ---- 索引服务 ----

    single { ImageIndexer(accessManager = get(), imageDao = get()) }
    single { IncrementalScanner(accessManager = get(), imageDao = get(), indexer = get()) }
    single { ScanController(settings = get(), indexer = get(), incrementalScanner = get()) }

    // ---- 分析 / 清理 / OCR 服务 ----

    single { DuplicateDetector(imageDao = get()) }
    single { LocalOcrEngine() }
    single {
        AnalysisPipeline(
            accessManager = get(),
            imageDao = get(),
            ocrDao = get(),
            duplicateDetector = get(),
            ocrEngine = get(),
        )
    }
    single { CleanupRecommender(imageDao = get(), cleanupDao = get()) }
    single {
        QuarantineManager(
            context = get(),
            accessManager = get(),
            imageDao = get(),
            cleanupDao = get(),
            settings = get(),
        )
    }
    single { VaultManager(context = get(), accessManager = get(), imageDao = get()) }
    single { CleanupController(recommender = get(), quarantineManager = get(), cleanupDao = get()) }

    // ---- Coil ImageLoader（全局单例，通过 Koin 暴露，App.onCreate 安装为 SingletonImageLoader） ----

    single { buildImageLoader(context = get(), accessManager = get()) }

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

    viewModel { SettingsViewModel(settings = get(), accessManager = get(), scanController = get()) }
    viewModel { DashboardViewModel(imageRepository = get(), scanController = get()) }
    viewModel { GalleryViewModel(imageRepository = get()) }
    viewModel {
        com.xjw.qalbum.ui.detail.ImageDetailViewModel(
            imageRepository = get(),
            vaultManager = get(),
        )
    }
    viewModel {
        com.xjw.qalbum.ui.cleanup.CleanupViewModel(
            cleanupController = get(),
            analysisPipeline = get(),
        )
    }
    viewModel { com.xjw.qalbum.ui.search.SearchViewModel(imageRepository = get()) }

    // ---- Worker ----

    worker { IncrementalScanWorker(get(), get()) }
    worker { AnalysisWorker(get(), get()) }
}
