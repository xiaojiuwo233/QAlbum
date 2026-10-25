package com.xjw.qalbum.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 看图页换窗口（补齐前后的图、续页、删图）时 pager 停在正在看的那张图上，那张图不重建 */
@RunWith(AndroidJUnit4::class)
class FollowIdsTest {
    @get:Rule val rule = createComposeRule()

    private val ids = mutableStateOf(listOf<Long>())
    private val fallback = mutableStateOf(0)
    /** 按顺序记下每页的组合（+）与释放（-），失败时打印出来 */
    private val events = mutableListOf<String>()
    private lateinit var pager: PagerState

    private fun show(initial: List<Long>, page: Int) {
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
        show(listOf(9_000L), page = 0)
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
        show((1_000L until 1_200L).toList(), page = 50)
        rule.runOnIdle { ids.value = (500L until 1_200L).toList() }
        rule.waitForIdle()
        assertEquals(550, pager.currentPage)
        assertFalse(events.toString(), "-1050" in events)
    }

    @Test
    fun removedImageFallsBackToTheGivenPage() {
        show((1L..5L).toList(), page = 4)
        rule.runOnIdle {
            fallback.value = 3
            ids.value = (1L..4L).toList()
        }
        rule.waitForIdle()
        assertEquals(3, pager.currentPage)
    }
}
