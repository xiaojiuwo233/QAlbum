package com.xjw.qalbum.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import com.xjw.qalbum.ui.common.PlaceholderScaffold

/**
 * 搜索页 —— Phase 3（OCR 全文）+ Phase 4（语义搜索 + 自然语言筛选） 实装。
 */
@Composable
fun SearchScreen(contentPadding: PaddingValues) {
    PlaceholderScaffold(
        title = "搜索",
        icon = Icons.Outlined.Search,
        headline = "智能搜索即将到来",
        description = "Phase 3 起，你可以通过 OCR 识别的文字直接搜索图片，Phase 4 会加入自然语言筛选与以图搜图。",
        contentPadding = contentPadding,
    )
}
