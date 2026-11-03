package com.xjw.qalbum.ui.settings

import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置首页的状态卡片与各项副标题 */
class SettingsSummaryTest {

    @Test
    fun `状态卡片按所选方式说下一步`() {
        assertEquals("检测中…", accessHeadline(null))
        assertEquals("检测中…", accessHeadline(AccessState(AccessMode.ROOT, checking = true)))
        assertEquals("没有安装 Shizuku", accessHeadline(shizuku(ShizukuState.NotInstalled)))
        assertEquals("Shizuku 没有运行", accessHeadline(shizuku(ShizukuState.NotRunning)))
        assertEquals("还没有授权", accessHeadline(shizuku(ShizukuState.PermissionDenied)))
        assertEquals("连不上 Shizuku", accessHeadline(shizuku(ShizukuState.Granted).copy(granted = false)))
        assertEquals("没有 Root 权限", accessHeadline(AccessState(AccessMode.ROOT, granted = false)))
        assertEquals("找不到图片目录", accessHeadline(AccessState(AccessMode.ROOT, granted = true, folderFound = false)))
        assertEquals("没有目录读取权限", accessHeadline(shizuku(ShizukuState.Granted).copy(granted = true, folderFound = true, folderReadable = false)))
        assertEquals("可以使用", accessHeadline(shizuku(ShizukuState.Granted).copy(granted = true, folderFound = true, folderReadable = true)))
    }

    @Test
    fun `Root 模式不看 Shizuku 的状态`() {
        // 切到 Root 后不再检测 Shizuku，shizuku 为 null，只按 Root 授权与目录判断
        val root = AccessState(AccessMode.ROOT, granted = true, folderFound = true, folderReadable = true)
        assertEquals("可以使用", accessHeadline(root))
        assertTrue(root.ready)
    }

    @Test
    fun `授权成功且路径存在仍需要目录读取权限`() {
        val access = shizuku(ShizukuState.Granted).copy(granted = true, folderFound = true)
        assertFalse(access.ready)
        assertFalse(access.copy(folderReadable = false).ready)
        assertTrue(access.copy(folderReadable = true).ready)
        assertFalse(access.copy(folderFound = false, folderReadable = true).ready)
    }

    @Test
    fun `图片目录的副标题`() {
        assertEquals("QQ 默认目录", scanSummary(SettingsRepository.DEFAULT_CHATPIC_PATH))
        assertEquals("chatpic", scanSummary("/storage/emulated/0/Android/data/com.tencent.tim/Tencent/Tim/chatpic"))
        assertEquals("chatpic", scanSummary("/sdcard/chatpic/"))
    }

    @Test
    fun `长路径只留最后三段`() {
        assertEquals("/sdcard/a/b", shortPath("/sdcard/a/b"))
        assertEquals(
            "…/Tencent/MobileQQ/chatpic",
            shortPath(SettingsRepository.DEFAULT_CHATPIC_PATH),
        )
    }

    @Test
    fun `清理的副标题`() {
        assertEquals("回收站保留 30 天 · 不定时清理", cleanupSummary(30, 0))
        assertEquals("回收站保留 7 天 · 每周清理 2 项", cleanupSummary(7, 2))
    }

    private fun shizuku(state: ShizukuState) = AccessState(AccessMode.SHIZUKU, shizuku = ShizukuStatus(state))
}
