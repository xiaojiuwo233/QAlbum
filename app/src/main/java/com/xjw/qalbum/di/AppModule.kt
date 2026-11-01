package com.xjw.qalbum.di

import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.access.RootFileAccess
import com.xjw.qalbum.access.ShizukuFileAccess
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.DatabaseOpener
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.service.cleanup.CompressDemo
import com.xjw.qalbum.service.cleanup.CompressionCoordinator
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.OriginalCompressor
import com.xjw.qalbum.service.cleanup.rules.CleanupRunner
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.service.indexer.ImageSizeBackfill
import com.xjw.qalbum.service.indexer.ScanController
import com.xjw.qalbum.service.indexer.ScanPipeline
import com.xjw.qalbum.service.indexer.transport.ScanTransportFactory
import com.xjw.qalbum.service.work.BackgroundTasks
import com.xjw.qalbum.ui.dashboard.DashboardViewModel
import com.xjw.qalbum.ui.gallery.GalleryViewModel
import com.xjw.qalbum.qq.QqSourceRepository
import com.xjw.qalbum.ui.sources.QqSourcesViewModel
import com.xjw.qalbum.service.demo.DemoMode
import com.xjw.qalbum.service.update.AppUpdates
import com.xjw.qalbum.service.update.UpdateChecker
import com.xjw.qalbum.ui.imageloading.FdFastPath
import com.xjw.qalbum.ui.imageloading.ImageTileSources
import com.xjw.qalbum.ui.imageloading.buildImageLoader
import com.xjw.qalbum.ui.logs.LogViewModel
import com.xjw.qalbum.ui.settings.SettingsViewModel
import com.xjw.qalbum.worker.IncrementalScanWorker
import com.xjw.qalbum.worker.QuarantinePurgeWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import timber.log.Timber

/**
 * Koin 主模块
 *
 * 组织方式：按职责分组 —— 数据 / 访问 / 索引 / 清理 / ViewModel / Worker
 */
val appModule = module {

    // ---- 数据 ----

    single { AppDatabase.build(get()) }
    // 需要升级数据库时启动即打开（升级弹窗显示进度）
    single { DatabaseOpener(context = get(), db = get(), tasks = get(), appScope = get()) }
    single { get<AppDatabase>().imageDao() }
    single { get<AppDatabase>().cleanupDao() }
    single { get<AppDatabase>().dirSnapshotDao() }
    single { get<AppDatabase>().qqSourceDao() }

    single { SettingsRepository(get(), appScope = get()) }
    single { ImageRepository(imageDao = get()) }

    // 应用级协程作用域：用于必须脱离 UI / 页面生命周期完成的写入与批量操作，随进程存活。
    // 落在此 scope 的写入均为 fire-and-forget（无调用方 try/catch）；磁盘满等 IO 失败
    // 必须在这里兜住记日志，否则未捕获异常经默认 handler 直接杀进程。
    single {
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, t -> Timber.e(t, "appScope 后台任务失败") }
        )
    }

    // 后台长任务登记：有任务时以前台服务运行（通知栏显示进度），切到后台不降速
    single { BackgroundTasks(context = get()) }

    // 匿名统计与崩溃日志（后端由 App 按构建类型提供：release 为 Firebase）
    single { Analytics(backend = get(), settings = get(), scope = get(), images = get()) }

    // ---- 文件访问 ----

    single { ShizukuFileAccess() }
    single { RootFileAccess() }
    single { AccessManager(get(), get(), get()) }
    // 聊天来源相关的入口在 Shizuku 模式下（没读过来源时）不显示
    single { com.xjw.qalbum.access.ChatFeatures(settings = get(), sources = get()) }

    // ---- 索引服务 ----

    single { FileOpsClient(context = get()) }
    single { ScanTransportFactory(accessManager = get(), fileOpsClient = get()) }
    single {
        val archive = get<ArchiveManager>()
        val database = get<AppDatabase>()
        ScanPipeline(
            db = get(),
            imageDao = get(),
            dirSnapshotDao = get(),
            transportFactory = get(),
            // 扫描结束：归档跟上、自动归档名单、发送图片缓存并回原图那一家
            afterScan = { archive.sync(); archive.applyAutoArchive(); com.xjw.qalbum.qq.QqSentPreviewLinker.mergeFamilies(database) },
        )
    }
    single {
        val transports = get<ScanTransportFactory>()
        ImageSizeBackfill(
            imageDao = get(), backups = get<AppDatabase>().originalBackupDao(), settings = get(),
            openTransport = transports::create, tasks = get(), appScope = get(),
        )
    }
    single { ScanController(settings = get(), pipeline = get(), tasks = get(), sizeBackfill = get(), analytics = get()) }

    // ---- 清理 ----

    single {
        QuarantineManager(
            context = get(),
            accessManager = get(),
            db = get(),
            imageDao = get(),
            cleanupDao = get(),
            settings = get(),
            fileOpsClient = get(),
            archive = get(),
            statsCache = get(),
            analytics = get(),
        )
    }
    single { ArchiveManager(db = get(), settings = get(), appScope = get(), analytics = get()) }
    // 清理页、规则页的统计缓存（图片变了才重算，清理执行期间暂停）
    single { com.xjw.qalbum.data.db.StatsCache(db = get(), scope = get()) }
    single { com.xjw.qalbum.service.indexer.PictureVariantIndexer(db = get(), settings = get()) }
    single { ImageFileManager(context = get(), accessManager = get(), imageDao = get(), analytics = get()) }
    single { OriginalCompressor(context = get(), db = get(), accessManager = get(), fileOpsClient = get(), settings = get()) }
    single { CleanupRunner(quarantine = get(), compressor = get(), tasks = get(), appScope = get(), statsCache = get(), analytics = get()) }
    // 定时清理（每周，设置里选规则）
    single {
        val runner = get<CleanupRunner>()
        val quarantine = get<QuarantineManager>()
        com.xjw.qalbum.service.cleanup.rules.ScheduledCleanup(
            settings = get(), db = get(), accessError = quarantine::fileAccessError, trash = runner::trashNow, analytics = get(),
        )
    }
    // 压缩的统一入口（先对比再替换）与设置页的质量演示
    single {
        CompressionCoordinator(
            db = get(), compressor = get(), runner = get(), settings = get(), tasks = get(), appScope = get(), demo = get(), analytics = get(),
        )
    }
    single { CompressDemo(context = get()) }
    // QQ 缓存页（复刻 QQ 的清理缓存）与视频页
    single { com.xjw.qalbum.service.cleanup.QqCacheCleaner(accessManager = get(), fileOpsClient = get(), settings = get(), analytics = get()) }
    single {
        com.xjw.qalbum.service.video.VideoLibrary(
            context = get(), db = get(), accessManager = get(), fileOpsClient = get(), settings = get(), analytics = get(),
        )
    }
    single {
        QqSourceRepository(
            context = get(), db = get(), settings = get(), client = get(), quarantine = get(), appScope = get(), tasks = get(),
            archive = get(), statsCache = get(), analytics = get(),
        )
    }

    single { com.xjw.qalbum.data.db.DatabaseSlimmer(context = get(), db = get()) }
    single {
        com.xjw.qalbum.service.storage.StorageMaintenance(
            slimmer = get(), sources = get(), statsCache = get(), tasks = get(), imageLoader = get(), appScope = get(), analytics = get(),
        )
    }

    // ---- Coil ImageLoader（全局单例，通过 Koin 暴露，App.onCreate 安装为 SingletonImageLoader） ----

    single { FdFastPath(accessManager = get(), fileOpsClient = get()) }
    single {
        val demo = get<DemoMode>()
        buildImageLoader(context = get(), accessManager = get(), fastPath = get(), demo = demo).also { loader ->
            // 开关演示模式后清掉内存里的图：看图页的占位图按格子的 key 取，别取到另一种
            get<CoroutineScope>().launch { demo.enabled.drop(1).collect { loader.memoryCache?.clear() } }
        }
    }
    // 看图放大后分块解码（读磁盘缓存里的那份或特权进程的 fd）
    single { ImageTileSources(imageLoader = get(), fastPath = get(), demo = get()) }

    // 演示模式（截图用）：图片换成示意图、聊天来源换成虚构的名称，不执行改动图片的操作
    single { DemoMode(settings = get(), scope = get()) }

    // 检查更新（GitHub Releases）：打开 App 进概览时自动查一次，「关于」里可以手动查
    single { UpdateChecker() }
    single { AppUpdates(checker = get(), scope = get(), analytics = get()) }

    // ---- ViewModel ----

    viewModel {
        SettingsViewModel(
            context = get(),
            settings = get(),
            accessManager = get(),
            scanController = get(),
            appScope = get(),
            compressDemo = get(),
            chatFeatures = get(),
            cleanupScheduler = get(),
            analytics = get(),
        )
    }
    viewModel {
        DashboardViewModel(
            imageRepository = get(),
            scanController = get(),
            cleanupDao = get(),
            backups = get<AppDatabase>().originalBackupDao(),
            chatFeatures = get(),
        )
    }
    viewModel {
        GalleryViewModel(
            imageRepository = get(),
            imageFileManager = get(),
            quarantineManager = get(),
            archive = get(),
            settings = get(),
            appScope = get(),
            tasks = get(),
            chatFeatures = get(),
            demo = get(),
        )
    }
    viewModel {
        com.xjw.qalbum.ui.detail.ImageDetailViewModel(
            imageRepository = get(),
            imageFileManager = get(),
            quarantineManager = get(),
            archive = get(),
            settings = get(),
            compressor = get(),
            demo = get(),
        )
    }
    viewModel {
        com.xjw.qalbum.ui.cleanup.CleanupViewModel(
            db = get(),
            runner = get(),
            archive = get(),
            settings = get(),
            statsCache = get(),
            chatFeatures = get(),
        )
    }
    viewModel {
        com.xjw.qalbum.ui.cleanup.RecycleBinViewModel(
            db = get(),
            quarantine = get(),
            compressor = get(),
            archive = get(),
            settings = get(),
            tasks = get(),
            appScope = get(),
            statsCache = get(),
            demo = get(),
            analytics = get(),
        )
    }
    viewModel { params ->
        com.xjw.qalbum.ui.cleanup.RulePreviewViewModel(
            ruleId = params.get(),
            db = get(),
            runner = get(),
            compression = get(),
            compressor = get(),
            archive = get(),
            imageFiles = get(),
            settings = get(),
            appScope = get(),
            statsCache = get(),
            demo = get(),
        )
    }
    viewModel {
        com.xjw.qalbum.ui.cleanup.QqCacheViewModel(cleaner = get(), tasks = get(), appScope = get(), demo = get())
    }
    viewModel {
        com.xjw.qalbum.ui.video.VideoViewModel(db = get(), library = get(), settings = get(), tasks = get(), appScope = get(), demo = get())
    }
    viewModel { com.xjw.qalbum.ui.settings.StorageViewModel(maintenance = get()) }
    viewModel { LogViewModel(settings = get(), appScope = get()) }
    viewModel { QqSourcesViewModel(repository = get(), settings = get(), chatFeatures = get(), demo = get()) }
    viewModel { params ->
        com.xjw.qalbum.ui.sources.QqSourceBrowseViewModel(
            target = params.get(),
            repository = get(),
            settings = get(),
            images = get(),
            imageFiles = get(),
            quarantine = get(),
            archive = get(),
            appScope = get(),
            tasks = get(),
            demo = get(),
        )
    }

    // ---- Worker ----

    worker { IncrementalScanWorker(get(), get()) }
    worker { QuarantinePurgeWorker(get(), get()) }
}
