package com.xjw.qalbum.service.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xjw.qalbum.MainActivity
import com.xjw.qalbum.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.text.NumberFormat

/**
 * 后台任务的前台服务：[BackgroundTasks] 里有任务时运行，通知栏显示当前任务与进度（可停止），
 * 让切到后台的进程保持前台优先级；任务全部结束后移除通知并退出。
 */
class TaskForegroundService : Service() {

    private val tasks: BackgroundTasks by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var foreground = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) tasks.cancel(intent.getLongExtra(EXTRA_TASK, -1))
        // startForegroundService 之后必须尽快转为前台，哪怕任务已经结束
        show(tasks.tasks.value)
        if (!observing) {
            observing = true
            scope.launch {
                // StateFlow 本身合并：通知更新期间的中间进度直接跳过，只显示最新的
                tasks.tasks.collect { list ->
                    if (list.isEmpty()) {
                        delay(STOP_DELAY_MS)
                        if (tasks.tasks.value.isEmpty()) stop()
                    } else {
                        show(list)
                        delay(UPDATE_INTERVAL_MS)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 起 dataSync 前台服务每天有时长上限，到时必须退出 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Timber.w("TaskForegroundService: foreground time limit reached")
        stop()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
        // 停下的同时又有新任务登记：重新拉起（拿不到前台权限时任务照常跑，只是没有通知）
        if (tasks.tasks.value.isNotEmpty()) ensureRunning(applicationContext)
    }

    private fun show(list: List<BackgroundTasks.Task>) {
        val notification = build(this, list)
        if (!foreground) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType())
                foreground = true
            } catch (e: Exception) {
                Timber.w(e, "TaskForegroundService: startForeground failed")
                stopSelf()
            }
        } else {
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                Timber.w(e, "TaskForegroundService: notification not allowed")
            }
        }
    }

    private fun stop() {
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelfResult(lastStartId)
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    companion object {
        private const val CHANNEL_ID = "background_tasks"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CANCEL = "com.xjw.qalbum.action.CANCEL_TASK"
        private const val EXTRA_TASK = "task"
        private const val UPDATE_INTERVAL_MS = 700L
        private const val STOP_DELAY_MS = 600L

        @Volatile
        private var running = false

        /** 有任务时确保服务在跑；App 在后台、系统不允许启动前台服务时只记日志，任务照常执行 */
        fun ensureRunning(context: Context) {
            if (running) return
            running = true
            try {
                ContextCompat.startForegroundService(context, Intent(context, TaskForegroundService::class.java))
            } catch (e: Exception) {
                running = false
                Timber.w(e, "TaskForegroundService: start not allowed")
            }
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台任务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "扫描、读取聊天来源、清理与压缩时显示进度"
                    setShowBadge(false)
                },
            )
        }

        private fun build(context: Context, list: List<BackgroundTasks.Task>): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_qalbum)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
            val first = list.firstOrNull()
            if (first == null) {
                return builder.setContentTitle("QAlbum").setContentText("正在收尾…").build()
            }
            builder.setContentTitle(if (list.size == 1) first.title else "${list.size} 个任务进行中")
            builder.setContentText(
                if (list.size == 1) first.detail ?: progressText(first) else list.joinToString("、") { it.title },
            )
            if (first.total > 0) {
                builder.setProgress(100, (first.done * 100 / first.total).toInt().coerceIn(0, 100), false)
                if (list.size > 1) builder.setSubText(progressText(first))
            } else {
                builder.setProgress(0, 0, true)
            }
            list.firstOrNull { it.cancellable }?.let { task ->
                val cancel = PendingIntent.getService(
                    context, task.id.toInt(),
                    Intent(context, TaskForegroundService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_TASK, task.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(0, if (list.size == 1) "停止" else "停止「${task.title}」", cancel)
            }
            return builder.build()
        }

        private fun progressText(task: BackgroundTasks.Task): String {
            if (task.total <= 0) return "处理中…"
            val format = NumberFormat.getIntegerInstance()
            return "${format.format(task.done)} / ${format.format(task.total)}"
        }
    }
}
