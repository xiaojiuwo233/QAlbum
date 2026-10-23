package com.xjw.qalbum.domain.model

/** 深浅色：跟随系统或固定一种 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 用主题色生成整套配色时的风格（Material 配色方案的几种变体） */
enum class PaletteStyle { VIBRANT, TONAL_SPOT, EXPRESSIVE, FIDELITY }

/**
 * 外观设置。[dynamicColor] 在 Android 12+ 跟随壁纸配色；关闭（或系统不支持）时用 [seedColor] 按 [style] 生成整套配色。
 * [pureBlack] 让深色模式的背景纯黑。
 */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val seedColor: Int = DEFAULT_SEED_COLOR,
    val style: PaletteStyle = PaletteStyle.VIBRANT,
    val pureBlack: Boolean = false,
) {
    companion object {
        /** 品牌蓝：不跟随壁纸时默认的主题色 */
        const val DEFAULT_SEED_COLOR: Int = 0xFF1565C0.toInt()
    }
}
