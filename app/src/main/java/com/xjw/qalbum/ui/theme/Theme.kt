package com.xjw.qalbum.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.xjw.qalbum.domain.model.ThemeMode
import com.xjw.qalbum.domain.model.ThemeSettings

/** 系统支持跟随壁纸配色（Android 12+） */
val supportsWallpaperColors: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * QAlbum 主题入口（Material 3 Expressive）。
 *
 * - [MaterialExpressiveTheme] + [MotionScheme.expressive]：组件与转场统一使用带回弹的 spring 规格；
 * - 标准字阶与形状阶梯（见 [AppTypography] / [AppShapes]）；
 * - 配色见「设置 → 外观」：深浅色跟随系统或固定，Android 12+ 默认跟随壁纸，也可以自选主题色和配色风格（[seedColorScheme]），
 *   深色模式可用纯黑背景。
 */
@Composable
fun QAlbumTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val darkTheme = when (settings.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val wallpaper = settings.dynamicColor && supportsWallpaperColors
    val base = if (wallpaper) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        remember(settings.seedColor, settings.style, darkTheme) { seedColorScheme(settings.seedColor, settings.style, darkTheme) }
    }
    val colorScheme = if (darkTheme && settings.pureBlack) base.withPureBlack() else base

    // 系统栏透明由 Activity.enableEdgeToEdge() 处理，这里只同步图标明暗
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
