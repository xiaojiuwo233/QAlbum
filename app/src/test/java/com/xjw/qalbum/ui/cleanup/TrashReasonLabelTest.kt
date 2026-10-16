package com.xjw.qalbum.ui.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

/** 回收站按删除原因筛选：手动删除、聊天来源、按时间清理各合成一类，规则名照用 */
class TrashReasonLabelTest {

    @Test
    fun similarReasonsShareOneFilter() {
        assertEquals("手动删除", trashReasonLabel("用户批量删除"))
        assertEquals("手动删除", trashReasonLabel("用户手动删除"))
        assertEquals("保存后删除", trashReasonLabel("保存后移入回收站"))
        assertEquals("聊天来源", trashReasonLabel("按 QQ 来源手动删除"))
        assertEquals("聊天来源", trashReasonLabel("按 QQ 图片来源清理"))
        assertEquals("按时间清理", trashReasonLabel("2024 年"))
        assertEquals("按时间清理", trashReasonLabel("2024 年 3 月"))
        assertEquals("QQ 缩略图", trashReasonLabel("QQ 缩略图"))
        assertEquals("其他", trashReasonLabel(""))
    }
}
