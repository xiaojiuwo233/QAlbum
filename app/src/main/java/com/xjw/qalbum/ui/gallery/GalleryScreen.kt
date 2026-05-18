package com.xjw.qalbum.ui.gallery

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.runtime.Composable
import com.xjw.qalbum.ui.common.PlaceholderScaffold

/**
 * 图库（时间线）页 —— Phase 1 实装。
 */
@Composable
fun GalleryScreen(contentPadding: PaddingValues) {
    PlaceholderScaffold(
        title = "图库",
        icon = Icons.Outlined.PhotoLibrary,
        headline = "时间线即将到来",
        description = "Phase 1 将在这里展示已索引的所有图片，按拍摄/接收时间线呈现，支持秒滑、分组与变体合并。",
        contentPadding = contentPadding,
    )
}
