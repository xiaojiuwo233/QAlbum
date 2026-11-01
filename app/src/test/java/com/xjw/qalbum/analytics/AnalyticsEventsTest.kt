package com.xjw.qalbum.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 页面名只用路由模板，规则只记 id，数量只记分档 */
class AnalyticsEventsTest {

    @Test
    fun `页面名不带图片 id 和聊天`() {
        assertEquals("image", screenName("image/{imageId}"))
        assertEquals("archive_image", screenName("archive/image/{imageId}"))
        assertEquals("chat_sources", screenName("qq-sources"))
        assertEquals("chat_source", screenName("qq-sources/{kind}/{key}"))
        assertEquals("settings_access", screenName("settings_access"))
        assertEquals("agreement_update", screenName("agreement-update"))
        assertEquals("dashboard", screenName("dashboard"))
    }

    @Test
    fun `清理页和引导在页面里自己记`() {
        assertNull(screenName("cleanup"))
        assertNull(screenName("onboarding"))
    }

    @Test
    fun `按时间清理的时间段只记 range`() {
        assertEquals("range", ruleKey("range:1700000000000:1710000000000:2024 年 3 月"))
        assertEquals("old", ruleKey("old"))
    }

    @Test
    fun `数量分档`() {
        assertEquals("0", countBucket(0))
        assertEquals("<1k", countBucket(999))
        assertEquals("1k-10k", countBucket(1_000))
        assertEquals("10k-50k", countBucket(10_000))
        assertEquals("50k-200k", countBucket(199_999))
        assertEquals("200k-1m", countBucket(200_000))
        assertEquals(">1m", countBucket(1_000_000))
    }

    @Test
    fun `大小换成 MB 保留两位`() {
        assertEquals(0.0, mb(0), 0.0)
        assertEquals(1.0, mb(1_048_576), 0.0)
        assertEquals(1.5, mb(1_572_864), 0.0)
        assertEquals(0.01, mb(10_000), 0.0)
    }
}
