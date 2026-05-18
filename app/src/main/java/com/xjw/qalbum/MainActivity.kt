package com.xjw.qalbum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.navigation.AppNavigation
import com.xjw.qalbum.ui.theme.QAlbumTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    // 设置仓库通过 Koin 注入，避免 Compose 端重复注入
    private val settings: SettingsRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 沉浸式：让内容延伸到状态栏 / 导航栏后面，由 Compose 自行处理 padding
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
        )
        super.onCreate(savedInstanceState)

        setContent {
            val dynamicColor by settings.dynamicColorEnabled.collectAsState(initial = true)
            QAlbumTheme(dynamicColor = dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }
}
