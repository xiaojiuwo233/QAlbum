package com.xjw.qalbum

import android.app.Application
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.xjw.qalbum.di.appModule
import com.xjw.qalbum.log.AppLog
import com.xjw.qalbum.log.CrashHandler
import com.xjw.qalbum.log.FileLoggingTree
import com.xjw.qalbum.log.LogcatCapture
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

        // 把 Koin 注入的 ImageLoader 安装为 Coil 全局单例，
        // 这样 Compose 内的 AsyncImage 不需要显式传 ImageLoader 也能用到自定义 Fetcher
        SingletonImageLoader.setSafe { get<ImageLoader>() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.WARN)
            .build()
}
