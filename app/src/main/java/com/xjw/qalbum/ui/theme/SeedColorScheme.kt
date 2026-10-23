package com.xjw.qalbum.ui.theme

import android.annotation.SuppressLint
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFidelity
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant
import com.xjw.qalbum.domain.model.PaletteStyle

/**
 * 由一个主题色生成整套 Material 配色（用 Material Components 自带的 Material Color Utilities，与系统壁纸取色同一套算法）。
 * 品牌蓝 #1565C0 + [PaletteStyle.VIBRANT] 就是 QAlbum 原来的默认配色。
 */
@SuppressLint("RestrictedApi")
fun seedColorScheme(seed: Int, style: PaletteStyle, dark: Boolean): ColorScheme {
    val source = Hct.fromInt(seed)
    val scheme: DynamicScheme = when (style) {
        PaletteStyle.VIBRANT -> SchemeVibrant(source, dark, 0.0)
        PaletteStyle.TONAL_SPOT -> SchemeTonalSpot(source, dark, 0.0)
        PaletteStyle.EXPRESSIVE -> SchemeExpressive(source, dark, 0.0)
        PaletteStyle.FIDELITY -> SchemeFidelity(source, dark, 0.0)
    }
    val roles = MaterialDynamicColors()
    fun DynamicColor.color() = Color(getArgb(scheme))
    val base = if (dark) darkColorScheme() else expressiveLightColorScheme()
    return base.copy(
        primary = roles.primary().color(),
        onPrimary = roles.onPrimary().color(),
        primaryContainer = roles.primaryContainer().color(),
        onPrimaryContainer = roles.onPrimaryContainer().color(),
        inversePrimary = roles.inversePrimary().color(),
        secondary = roles.secondary().color(),
        onSecondary = roles.onSecondary().color(),
        secondaryContainer = roles.secondaryContainer().color(),
        onSecondaryContainer = roles.onSecondaryContainer().color(),
        tertiary = roles.tertiary().color(),
        onTertiary = roles.onTertiary().color(),
        tertiaryContainer = roles.tertiaryContainer().color(),
        onTertiaryContainer = roles.onTertiaryContainer().color(),
        background = roles.background().color(),
        onBackground = roles.onBackground().color(),
        surface = roles.surface().color(),
        onSurface = roles.onSurface().color(),
        surfaceVariant = roles.surfaceVariant().color(),
        onSurfaceVariant = roles.onSurfaceVariant().color(),
        surfaceTint = roles.surfaceTint().color(),
        inverseSurface = roles.inverseSurface().color(),
        inverseOnSurface = roles.inverseOnSurface().color(),
        error = roles.error().color(),
        onError = roles.onError().color(),
        errorContainer = roles.errorContainer().color(),
        onErrorContainer = roles.onErrorContainer().color(),
        outline = roles.outline().color(),
        outlineVariant = roles.outlineVariant().color(),
        scrim = roles.scrim().color(),
        surfaceBright = roles.surfaceBright().color(),
        surfaceDim = roles.surfaceDim().color(),
        surfaceContainerLowest = roles.surfaceContainerLowest().color(),
        surfaceContainerLow = roles.surfaceContainerLow().color(),
        surfaceContainer = roles.surfaceContainer().color(),
        surfaceContainerHigh = roles.surfaceContainerHigh().color(),
        surfaceContainerHighest = roles.surfaceContainerHighest().color(),
    )
}

/** 深色模式的纯黑背景：背景与最低层容器换成黑色，卡片等容器保持原来的深灰，层次还在 */
fun ColorScheme.withPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
)
