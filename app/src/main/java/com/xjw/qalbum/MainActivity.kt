package com.xjw.qalbum

import android.Manifest
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.ThemeMode
import com.xjw.qalbum.service.work.BackgroundTasks
import com.xjw.qalbum.ui.components.DatabaseUpgradeDialog
import com.xjw.qalbum.ui.compress.CompressionHost
import com.xjw.qalbum.ui.navigation.AppNavigation
import com.xjw.qalbum.ui.theme.QAlbumTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    // 设置仓库通过 Koin 注入，避免 Compose 端重复注入
    private val settings: SettingsRepository by inject()
    private val tasks: BackgroundTasks by inject()

    // 结果不用处理：拒绝时任务照常在后台跑，只是看不到进度通知
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // 沉浸式：让内容延伸到状态栏 / 导航栏后面，由 Compose 自行处理 padding
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
        )
        super.onCreate(savedInstanceState)

        setContent {
            // 外观设置读到之前不画（一两帧，显示窗口背景），免得固定深色的用户先闪一下浅色
            val theme by settings.themeSettings.collectAsState(initial = null)
            QAlbumTheme(theme ?: return@setContent) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
                // 数据库升级进度；任何页面发起的压缩对比与清理结果提示
                DatabaseUpgradeDialog()
                CompressionHost()
            }
        }
        askNotificationPermissionOnFirstTask()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) followThemeMode()
    }

    /**
     * 固定深色 / 浅色时告诉系统（会记住）：下次启动的启动屏和窗口背景直接是对的颜色，不会先闪一下。
     * 清单里声明了 uiMode 配置变化自己处理，切换时页面不重建。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun followThemeMode() {
        val uiMode = getSystemService(UiModeManager::class.java) ?: return
        lifecycleScope.launch {
            settings.themeSettings.map { it.mode }.distinctUntilChanged().collect { mode ->
                uiMode.setApplicationNightMode(
                    when (mode) {
                        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                    },
                )
            }
        }
    }

    /** Android 13+ 发通知要授权：第一次有后台任务（扫描、读取来源、清理）时问一次，进度显示在通知栏 */
    private fun askNotificationPermissionOnFirstTask() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (granted() || settings.notificationPermissionAsked.first()) return@repeatOnLifecycle
                tasks.tasks.first { it.isNotEmpty() }
                if (granted() || settings.notificationPermissionAsked.first()) return@repeatOnLifecycle
                settings.setNotificationPermissionAsked()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun granted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
