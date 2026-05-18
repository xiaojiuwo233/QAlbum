package com.xjw.qalbum.ui.cleanup

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.runtime.Composable
import com.xjw.qalbum.ui.common.PlaceholderScaffold

/**
 * 清理页 —— Phase 2 起逐步实装：
 * - Phase 2：重复检测 + 基础清理（变体副本、低质图）
 * - Phase 4：AI 推荐删除列表
 */
@Composable
fun CleanupScreen(contentPadding: PaddingValues) {
    PlaceholderScaffold(
        title = "清理",
        icon = Icons.Outlined.CleaningServices,
        headline = "安全清理即将到来",
        description = "Phase 2 会上线重复图、低质图、变体副本的智能清理；删除前会有风险报告、隔离区双保险。",
        contentPadding = contentPadding,
    )
}
