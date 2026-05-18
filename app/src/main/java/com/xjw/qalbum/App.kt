package com.xjw.qalbum

import android.app.Application
import androidx.work.Configuration
import com.xjw.qalbum.di.appModule
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

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.INFO else Level.ERROR)
            androidContext(this@App)
            workManagerFactory()
            modules(appModule)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.WARN)
            .build()
}
