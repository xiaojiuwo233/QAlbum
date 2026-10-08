package com.xjw.qalbum

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.xjw.qalbum.di.appModule
import com.xjw.qalbum.log.AppLog
import com.xjw.qalbum.log.CrashHandler
import com.xjw.qalbum.log.FileLoggingTree
import com.xjw.qalbum.log.LogcatCapture
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.indexer.PictureVariantIndexer
import com.xjw.qalbum.worker.IncrementalScanWorker
import com.xjw.qalbum.worker.QuarantinePurgeWorker
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
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

        // 文件操作日志必须在任何新扫描/清理前尽快对账；周期 Worker 仍会再次幂等兜底。
        val quarantineManager = get<QuarantineManager>()
        get<CoroutineScope>().launch { quarantineManager.reconcilePending() }
        // 升级后一次性回填图片家族（同一张 QQ 图片的原图 / 预览图 / 缩略图），已完成则立即返回
        val variantIndexer = get<PictureVariantIndexer>()
        get<CoroutineScope>().launch { runCatching { variantIndexer.ensureIndexed() }.onFailure { Timber.w(it, "Picture family indexing failed") } }

        // 后台任务排程（均 KEEP 幂等，重复调用无副作用）：
        // - 增量扫描：每 6 小时一次（目录剪枝后常态秒级完成）
        // - 回收站到期清理：每日一次，兑现 UI 承诺的「N 天后自动删除」
        //   （AnalysisWorker 是 UI 按需触发的一次性工作，不在启动时排程）
        IncrementalScanWorker.schedulePeriodic(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            QuarantinePurgeWorker.NAME_UNIQUE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<QuarantinePurgeWorker>(
                QuarantinePurgeWorker.INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build(),
        )

        // 把 Koin 注入的 ImageLoader 安装为 Coil 全局单例，
        // 这样 Compose 内的 AsyncImage 不需要显式传 ImageLoader 也能用到自定义 Fetcher
        SingletonImageLoader.setSafe { get<ImageLoader>() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.WARN)
            .build()
}
