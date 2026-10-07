package com.xjw.qalbum.di

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.access.RootFileAccess
import com.xjw.qalbum.access.ShizukuFileAccess
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.data.remote.OpenAiCompatClient
import com.xjw.qalbum.service.ai.AiTaggingService
import com.xjw.qalbum.service.ai.SemanticIndex
import com.xjw.qalbum.service.ai.SemanticSearchEngine
import com.xjw.qalbum.service.analysis.AnalysisPipeline
import com.xjw.qalbum.service.analysis.DuplicateDetector
import com.xjw.qalbum.service.cleanup.CleanupController
import com.xjw.qalbum.service.cleanup.CleanupRecommender
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.cleanup.ThumbnailTrashManager
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.service.indexer.ScanController
import com.xjw.qalbum.service.indexer.ScanPipeline
import com.xjw.qalbum.service.indexer.transport.ScanTransportFactory
import com.xjw.qalbum.service.ocr.FileOcrModelStore
import com.xjw.qalbum.service.ocr.OcrModelDownloadService
import com.xjw.qalbum.service.ocr.OcrModelStore
import com.xjw.qalbum.service.ocr.AndroidPaddleOcrFactory
import com.xjw.qalbum.service.ocr.PaddleOcrEngine
import com.xjw.qalbum.service.ocr.PaddleOnnxOcrRunner
import com.xjw.qalbum.service.ocr.PaddleOcrRunner
import com.xjw.qalbum.ui.dashboard.DashboardViewModel
import com.xjw.qalbum.ui.gallery.GalleryViewModel
import com.xjw.qalbum.qq.QqSourceRepository
import com.xjw.qalbum.ui.sources.QqSourcesViewModel
import com.xjw.qalbum.ui.imageloading.buildImageLoader
import com.xjw.qalbum.ui.logs.LogViewModel
import com.xjw.qalbum.ui.settings.SettingsViewModel
import com.xjw.qalbum.worker.AnalysisWorker
import com.xjw.qalbum.worker.IncrementalScanWorker
import com.xjw.qalbum.worker.QuarantinePurgeWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import timber.log.Timber
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
    single { get<AppDatabase>().embeddingDao() }
    single { get<AppDatabase>().dirSnapshotDao() }
    single { get<AppDatabase>().dedupExclusionDao() }
    single { get<AppDatabase>().aiAnalysisDao() }
    single { get<AppDatabase>().qqSourceDao() }

    single { SettingsRepository(get(), appScope = get()) }
    single {
        ImageRepository(
            db = get(),
            imageDao = get(),
            ocrDao = get(),
            dedupExclusionDao = get(),
            tagDao = get(),
            embeddingDao = get(),
        )
    }

    // 应用级协程作用域：用于必须脱离 UI / 页面生命周期完成的写入（如设置落盘）。
    // 与 AnalysisPipeline / ScanController 各自持有的 IO 作用域同范式，随进程存活。
    // 落在此 scope 的写入均为 fire-and-forget（无调用方 try/catch）；磁盘满等 IO 失败
    // 必须在这里兜住记日志，否则未捕获异常经默认 handler 直接杀进程。
    single {
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, t -> Timber.e(t, "appScope 后台任务失败") }
        )
    }

    // ---- 文件访问 ----

    single { ShizukuFileAccess() }
    single { RootFileAccess() }
    single { AccessManager(get(), get(), get()) }

    // ---- 索引服务 ----

    single { FileOpsClient(context = get()) }
    single { ScanTransportFactory(accessManager = get(), fileOpsClient = get()) }
    single {
        ScanPipeline(
            db = get(),
            imageDao = get(),
            dirSnapshotDao = get(),
            transportFactory = get(),
        )
    }
    single { ScanController(settings = get(), pipeline = get()) }

    // ---- 分析 / 清理 / OCR / AI 服务 ----

    single { DuplicateDetector(db = get(), imageDao = get(), dedupExclusionDao = get()) }
    single { FileOcrModelStore(context = get()) }
    single<OcrModelStore> { get<FileOcrModelStore>() }
    single { OcrModelDownloadService(modelStore = get(), okHttpClient = get(), appScope = get(), runner = get()) }
    single { AndroidPaddleOcrFactory(context = get()) }
    single<PaddleOcrRunner> { PaddleOnnxOcrRunner(factory = get<AndroidPaddleOcrFactory>()) }
    single { PaddleOcrEngine(modelStore = get(), runner = get()) }
    single { OpenAiCompatClient(settings = get(), okHttpClient = get()) }
    single { SemanticIndex(dao = get()) }
    single { AiTaggingService(aiClient = get(), db = get(), imageDao = get(), tagDao = get(), settings = get()) }
    single {
        SemanticSearchEngine(
            aiClient = get(),
            semanticIndex = get(),
            imageDao = get(),
            settings = get(),
        )
    }
    single {
        AnalysisPipeline(
            accessManager = get(),
            fileOpsClient = get(),
            db = get(),
            imageDao = get(),
            ocrDao = get(),
            duplicateDetector = get(),
            ocrModelStore = get(),
            ocrEngine = get(),
            settings = get(),
            aiClient = get(),
            aiTagging = get(),
            semanticIndex = get(),
        )
    }
    single { CleanupRecommender(imageDao = get(), cleanupDao = get()) }
    single {
        QuarantineManager(
            context = get(),
            accessManager = get(),
            db = get(),
            imageDao = get(),
            cleanupDao = get(),
            settings = get(),
            fileOpsClient = get(),
            imageRepository = get(),
        )
    }
    single { ThumbnailTrashManager(imageDao = get()) }
    single { ImageFileManager(context = get(), accessManager = get(), imageDao = get()) }
    single { CleanupController(recommender = get(), quarantineManager = get(), cleanupDao = get()) }
    single { QqSourceRepository(context = get(), db = get(), settings = get(), client = get(), quarantine = get(), appScope = get()) }

    // ---- Coil ImageLoader（全局单例，通过 Koin 暴露，App.onCreate 安装为 SingletonImageLoader） ----

    single { buildImageLoader(context = get(), accessManager = get(), fileOpsClient = get()) }

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

    viewModel {
        SettingsViewModel(
            settings = get(),
            accessManager = get(),
            scanController = get(),
            imageDao = get(),
            ocrDao = get(),
            embeddingDao = get(),
            db = get(),
            ocrModelDownload = get(),
            appScope = get(),
        )
    }
    viewModel {
        DashboardViewModel(
            imageRepository = get(),
            scanController = get(),
            cleanupDao = get(),
            analysisPipeline = get(),
        )
    }
    viewModel {
        GalleryViewModel(
            imageRepository = get(),
            imageFileManager = get(),
            quarantineManager = get(),
            settings = get(),
            appScope = get(),
        )
    }
    viewModel {
        com.xjw.qalbum.ui.detail.ImageDetailViewModel(
            imageRepository = get(),
            imageFileManager = get(),
            quarantineManager = get(),
            settings = get(),
        )
    }
    viewModel {
        com.xjw.qalbum.ui.cleanup.CleanupViewModel(
            app = get(),
            cleanupController = get(),
            analysisPipeline = get(),
            thumbnailTrashManager = get(),
            imageRepository = get(),
            settings = get(),
            tagDao = get(),
            imageFileManager = get(),
            appScope = get(),
        )
    }
    viewModel {
        com.xjw.qalbum.ui.search.SearchViewModel(
            imageRepository = get(),
            semanticEngine = get(),
            settings = get(),
        )
    }
    viewModel {
        com.xjw.qalbum.ui.tags.TagsViewModel(
            tagDao = get(),
            imageRepository = get(),
            cleanupController = get(),
            imageFileManager = get(),
            settings = get(),
            appScope = get(),
        )
    }
    viewModel { LogViewModel() }
    viewModel { QqSourcesViewModel(repository = get(), settings = get()) }
    viewModel { params ->
        com.xjw.qalbum.ui.sources.QqSourceBrowseViewModel(
            target = params.get(),
            repository = get(),
            settings = get(),
            images = get(),
            imageFiles = get(),
            quarantine = get(),
            appScope = get(),
        )
    }

    // ---- Worker ----

    worker { IncrementalScanWorker(get(), get()) }
    worker { AnalysisWorker(get(), get()) }
    worker { QuarantinePurgeWorker(get(), get()) }
}
