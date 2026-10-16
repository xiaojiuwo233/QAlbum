package com.xjw.qalbum.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjw.qalbum.MainActivity
import com.xjw.qalbum.R
import com.xjw.qalbum.service.cleanup.rules.ScheduledCleanup
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 定时清理 Worker：每周一次执行 [ScheduledCleanup]，移入了图片就发一条汇总通知（点开进 App）。
 * 设置里选了规则才排程（见 [schedule]），全部取消即撤掉。
 */
class ScheduledCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val cleanup: ScheduledCleanup by inject()

    override suspend fun doWork(): Result = try {
        val summary = cleanup.run()
        Timber.i("ScheduledCleanupWorker: %s", summary.text)
        if (summary.moved > 0) notify(applicationContext, summary.text)
        Result.success()
    } catch (t: Throwable) {
        Timber.e(t, "ScheduledCleanupWorker failed")
        Result.retry()
    }

    companion object {
        const val NAME_UNIQUE = "qalbum_scheduled_cleanup"
        private const val CHANNEL_ID = "scheduled_cleanup"
        private const val NOTIFICATION_ID = 1101
        private const val INTERVAL_DAYS = 7L

        /** 有规则要定时执行就排一个每周任务（已有则保持），没有就撤掉 */
        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(NAME_UNIQUE)
                return
            }
            val request = PeriodicWorkRequestBuilder<ScheduledCleanupWorker>(INTERVAL_DAYS, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            work.enqueueUniquePeriodicWork(NAME_UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun notify(context: Context, text: String) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "定时清理", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "每周自动清理后告诉你移入了多少"
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_qalbum)
                .setContentTitle("定时清理")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                Timber.w(e, "ScheduledCleanupWorker: notification not allowed")
            }
        }
    }
}
