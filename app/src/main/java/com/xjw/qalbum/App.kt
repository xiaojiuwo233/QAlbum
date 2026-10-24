package com.xjw.qalbum

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.xjw.qalbum.data.db.DatabaseOpener
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.di.appModule
import com.xjw.qalbum.log.AppLog
import com.xjw.qalbum.log.CrashHandler
import com.xjw.qalbum.log.FileLoggingTree
import com.xjw.qalbum.log.LogcatCapture
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.service.cleanup.OriginalCompressor
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.indexer.ImageSizeBackfill
import com.xjw.qalbum.service.indexer.PictureVariantIndexer
import com.xjw.qalbum.worker.IncrementalScanWorker
import com.xjw.qalbum.worker.QqSourceRefreshWorker
import com.xjw.qalbum.worker.QuarantinePurgeWorker
import com.xjw.qalbum.worker.ScheduledCleanupWorker
import com.xjw.qalbum.service.work.TaskForegroundService
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import timber.log.Timber

/**
 * QAlbum Application 入口
 *
 * 职责：
 * 1. 初始化日志框架 [Timber]
 * 2. 启动 [Koin]，注入 [appModule]
 * 3. 通过 [Configuration.Provider] 让 WorkManager 走 Koin 的 WorkerFactory
 */
class App : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()

        // Android 的应用数据挂载隔离会随普通 su 进程继承：即使 uid=0，仍看不到 QQ
        // 私有目录。RootService 从同一 shell 启动，因此必须在首次 Root 调用前配置。
        // KernelSU/Magisk 支持 --mount-master；不支持的管理器由 libsu 回退普通 su。
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))

        // 日志系统：最早期装好，保证之后所有日志（含崩溃）都被持久化捕获。
        // - FileLoggingTree：所有构建都把 Timber 日志写入文件（release 也生效，闪退后仍在）
        // - DebugTree（仅 debug）：开发期回显到 Android Studio logcat
        // - LogcatCapture（仅 release）：镜像 logcat，补齐绕过 Timber 的第三方/原生日志，且与上面零重复
        AppLog.init(this)
        CrashHandler.install()
        Timber.plant(FileLoggingTree())
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            LogcatCapture.start()
        }
        Timber.i(
            "===== App 启动 v%s (build %d) %s =====",
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            if (BuildConfig.DEBUG) "debug" else "release",
        )

        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.INFO else Level.ERROR)
            androidContext(this@App)
            workManagerFactory()
            modules(appModule)
        }

        // 需要升级数据库时立即打开（迁移期间界面显示进度弹窗），其余启动工作照常排队等它
        get<DatabaseOpener>().upgradeIfNeeded()

        // 文件操作日志必须在任何新扫描/清理前尽快对账；周期 Worker 仍会再次幂等兜底。
        val quarantineManager = get<QuarantineManager>()
        val compressor = get<OriginalCompressor>()
        get<CoroutineScope>().launch {
            quarantineManager.reconcilePending()
            runCatching { compressor.reconcile() }.onFailure { Timber.w(it, "Compress reconcile failed") }
            runCatching { compressor.repairOwnership() }.onFailure { Timber.w(it, "Compress ownership repair failed") }
        }
        // 后台任务通知的渠道（Android 8+）；没有任务时不显示任何通知
        TaskForegroundService.createChannel(this)
        // 归档与「是否在图库显示」设置保持一致（首次运行把旧版逐张收藏补齐为整张图）
        get<ArchiveManager>().start()
        // 升级后一次性回填图片家族（同一张 QQ 图片的原图 / 预览图 / 缩略图），已完成则立即返回
        val variantIndexer = get<PictureVariantIndexer>()
        get<CoroutineScope>().launch { runCatching { variantIndexer.ensureIndexed() }.onFailure { Timber.w(it, "Picture family indexing failed") } }
        // 以前入库、还没有宽高的图补读一次（只读文件头；没有就什么也不做）
        get<ImageSizeBackfill>().startIfNeeded()

        // 后台任务排程（均 KEEP 幂等，重复调用无副作用）：
        // - 回收站到期清理：每日一次，兑现 UI 承诺的「N 天后自动删除」（含压缩前的原图）
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            QuarantinePurgeWorker.NAME_UNIQUE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<QuarantinePurgeWorker>(
                QuarantinePurgeWorker.INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build(),
        )

        // - 定时清理：设置里选了规则才每周跑一次，全部取消即撤掉排程
        val settings = get<SettingsRepository>()
        // - 自动扫描：每 6 小时增量扫描一次（设置 → 扫描里可关，关掉即撤掉排程）
        get<CoroutineScope>().launch {
            settings.autoScan.distinctUntilChanged().collect { enabled -> IncrementalScanWorker.schedule(this@App, enabled) }
        }
        get<CoroutineScope>().launch {
            settings.scheduledCleanupRules.map { it.isNotEmpty() }.distinctUntilChanged().collect { enabled ->
                ScheduledCleanupWorker.schedule(this@App, enabled)
            }
        }
        // - 聊天来源：打开「每天自动读取」后每天充电时读一次新消息
        get<CoroutineScope>().launch {
            settings.qqAutoRefresh.distinctUntilChanged().collect { enabled -> QqSourceRefreshWorker.schedule(this@App, enabled) }
        }

        // 把 Koin 注入的 ImageLoader 安装为 Coil 全局单例，
        // 这样 Compose 内的 AsyncImage 不需要显式传 ImageLoader 也能用到自定义 Fetcher
        SingletonImageLoader.setSafe { get<ImageLoader>() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.WARN)
            .build()
}
