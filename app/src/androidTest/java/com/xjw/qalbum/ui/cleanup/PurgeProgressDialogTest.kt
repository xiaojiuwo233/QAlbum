package com.xjw.qalbum.ui.cleanup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.ui.theme.QAlbumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 进度对话框：不到半秒就做完的操作不弹出，超过半秒才显示进度 */
@RunWith(AndroidJUnit4::class)
class PurgeProgressDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun showsOnlyAfterHalfASecond() {
        rule.mainClock.autoAdvance = false
        rule.setContent { QAlbumTheme { PurgeProgressDialog(PurgeProgress(300, 1000, "正在移入回收站…")) } }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(0, rule.onAllNodesWithText("正在移入回收站…").fetchSemanticsNodes().size)
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("正在移入回收站…").assertIsDisplayed()
        rule.onNodeWithText("已处理 300 / 1000 项").assertIsDisplayed()
    }
}
