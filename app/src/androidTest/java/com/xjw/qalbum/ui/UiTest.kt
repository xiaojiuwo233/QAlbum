package com.xjw.qalbum.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.ui.cleanup.PurgeProgress
import com.xjw.qalbum.ui.cleanup.PurgeProgressDialog
import com.xjw.qalbum.ui.components.ZoomableImage
import com.xjw.qalbum.ui.detail.FollowIds
import com.xjw.qalbum.ui.theme.QAlbumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 界面行为：看图的双击、双指与翻页复位共用一套手势状态；看图页换窗口（补齐前后的图、续页、删图）时
 * 停在正在看的那张图上、那张图不重建；进度对话框不到半秒就做完的操作不弹出。
 */
@RunWith(AndroidJUnit4::class)
class UiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun doubleTapPinchAndPageResetShareOneGestureState() {
        val bitmap = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (column in 0 until 8) {
            paint.color = if (column % 2 == 0) android.graphics.Color.CYAN else android.graphics.Color.RED
            canvas.drawRect(column * 100f, 0f, (column + 1) * 100f, 450f, paint)
        }
        val active = mutableStateOf(true)
        val reset = mutableStateOf(0)
        var zoom = 1f
        var taps = 0
        rule.setContent {
            QAlbumTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    ZoomableImage(bitmap, "preview", Modifier.fillMaxSize(), stateKey = 1,
                        isActive = active.value, resetSignal = reset.value, onTap = { taps++ }, onZoomChanged = { zoom = it })
                }
            }
        }
        val image = rule.onNodeWithContentDescription("preview")
        // 中间偏右的一列是红或青；缩放松手后也必须仍然可见
        fun previewIsVisible(): Boolean {
            val pixels = image.captureToImage().toPixelMap()
            val pixel = pixels[pixels.width * 9 / 16, pixels.height / 2]
            return (pixel.red > 0.8f && pixel.green < 0.2f && pixel.blue < 0.2f) ||
                (pixel.red < 0.2f && pixel.green > 0.8f && pixel.blue > 0.8f)
        }
        rule.waitUntil(10_000) { previewIsVisible() }
        image.performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(2.5f, zoom, 0.05f); assertEquals(0, taps) }
        image.performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1f, zoom, 0.05f) }
        image.performTouchInput { pinch(centerLeft + Offset(40f, 0f), centerRight - Offset(40f, 0f), centerLeft, centerRight) }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(zoom > 1f) }
        assertTrue("Pinch release must keep the image visible", previewIsVisible())
        rule.runOnIdle { reset.value++ }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1f, zoom, 0.05f) }
        image.performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.runOnIdle { active.value = false }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1f, zoom, 0.05f) }
    }

    // ---- 看图页换窗口 ----

    private val ids = mutableStateOf(listOf<Long>())
    private val fallback = mutableStateOf(0)
    /** 按顺序记下每页的组合（+）与释放（-），失败时打印出来 */
    private val events = mutableListOf<String>()
    private lateinit var pager: PagerState

    private fun showPager(initial: List<Long>, page: Int) {
        ids.value = initial
        rule.setContent {
            val list = ids.value
            val pagerIds = remember { mutableStateOf(list) }
            pager = rememberPagerState(initialPage = page) { pagerIds.value.size }
            FollowIds(pager, pagerIds, list, fallback.value)
            HorizontalPager(state = pager, key = { pagerIds.value[it] }, modifier = Modifier.fillMaxSize()) { index ->
                val id = pagerIds.value[index]
                DisposableEffect(id) {
                    events += "+$id"
                    onDispose { events += "-$id" }
                }
                Box(Modifier.fillMaxSize())
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun openedImageStaysComposedWhenTheWindowFillsInAroundIt() {
        // 从图库靠后的位置点开：先只有这一张，补齐后它前面多出 500 张（超出 pager 按 key 对位的范围）
        showPager(listOf(9_000L), page = 0)
        rule.runOnIdle {
            fallback.value = 500
            ids.value = (8_500L until 9_500L).toList()
        }
        rule.waitForIdle()
        assertEquals(500, pager.currentPage)
        // 这张图没有重建（共享元素不会再飞一次），也没有先闪一下窗口里的第一张
        assertFalse(events.toString(), "-9000" in events)
        assertFalse(events.toString(), "+8500" in events)
    }

    @Test
    fun prependedPageKeepsTheImageBeingViewed() {
        showPager((1_000L until 1_200L).toList(), page = 50)
        rule.runOnIdle { ids.value = (500L until 1_200L).toList() }
        rule.waitForIdle()
        assertEquals(550, pager.currentPage)
        assertFalse(events.toString(), "-1050" in events)
    }

    @Test
    fun removedImageFallsBackToTheGivenPage() {
        showPager((1L..5L).toList(), page = 4)
        rule.runOnIdle {
            fallback.value = 3
            ids.value = (1L..4L).toList()
        }
        rule.waitForIdle()
        assertEquals(3, pager.currentPage)
    }

    // ---- 进度对话框 ----

    @Test
    fun purgeProgressShowsOnlyAfterHalfASecond() {
        rule.mainClock.autoAdvance = false
        rule.setContent { QAlbumTheme { PurgeProgressDialog(PurgeProgress(300, 1000, "正在移入回收站…")) } }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(0, rule.onAllNodesWithText("正在移入回收站…").fetchSemanticsNodes().size)
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("正在移入回收站…").assertIsDisplayed()
        rule.onNodeWithText("已处理 300 / 1000 项").assertIsDisplayed()
    }
}
