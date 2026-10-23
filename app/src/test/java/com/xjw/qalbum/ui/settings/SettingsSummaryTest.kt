package com.xjw.qalbum.ui.settings

import android.annotation.SuppressLint
import com.google.android.material.color.utilities.Hct
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
        assertEquals("可以使用", accessHeadline(shizuku(ShizukuState.Granted).copy(granted = true, folderFound = true)))
    }

    @Test
    fun `Root 模式不看 Shizuku 的状态`() {
        // 切到 Root 后不再检测 Shizuku，shizuku 为 null，只按 Root 授权与目录判断
        val root = AccessState(AccessMode.ROOT, granted = true, folderFound = true)
        assertEquals("可以使用", accessHeadline(root))
        assertTrue(root.ready)
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
            shortPath("/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"),
        )
    }

    @Test
    fun `清理的副标题`() {
        assertEquals("回收站保留 30 天 · 不定时清理", cleanupSummary(30, 0))
        assertEquals("回收站保留 7 天 · 每周清理 2 项", cleanupSummary(7, 2))
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `色相滑条的颜色色相跟着滑条走`() {
        listOf(0f, 60f, 140f, 220f, 300f).forEach { hue ->
            val actual = Hct.fromInt(hueSeed(hue)).hue
            val diff = abs(actual - hue).let { if (it > 180) 360 - it else it }
            assertTrue("hue $hue → $actual", diff < 2.0)
        }
    }

    private fun shizuku(state: ShizukuState) = AccessState(AccessMode.SHIZUKU, shizuku = ShizukuStatus(state))
}
