package com.xjw.qalbum.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive 形状方案（material3 1.5.0-alpha 起）
 *
 * Expressive 在标准的 extraSmall ~ extraLarge 五档之外，
 * 新增了 `largeIncreased` / `extraLargeIncreased` / `extraExtraLarge` 三档，
 * 用于强调状态（如悬浮 FAB 展开、Card hover、Dialog 入场）。
 *
 * 这里整体偏圆润，符合 QAlbum 作为图库类应用"轻松、温暖"的基调：
 * - extraSmall（8.dp）：标签、Chip
 * - small（12.dp）：按钮、紧凑卡片
 * - medium（20.dp）：标准卡片、面板
 * - large（28.dp）：FAB、对话框
 * - extraLarge（32.dp）：BottomSheet、大型容器
 * - largeIncreased / extraLargeIncreased：强调态
 * - extraExtraLarge：最大场景（如全屏弹层圆角）
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
    largeIncreased = RoundedCornerShape(36.dp),
    extraLargeIncreased = RoundedCornerShape(40.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)
