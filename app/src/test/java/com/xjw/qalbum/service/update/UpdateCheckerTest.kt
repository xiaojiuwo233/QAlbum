package com.xjw.qalbum.service.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 检查更新：从 GitHub 发布的标签读出版本代码 */
class UpdateCheckerTest {
    @Test
    fun `发布标签是「版本代码-版本号」`() {
        assertEquals(29881114L to "0.4.1", UpdateChecker.parseTag("29881114-0.4.1"))
        assertEquals(29881114L to "0.4.1-beta", UpdateChecker.parseTag("v29881114-0.4.1-beta"))
        assertEquals(29881114L to "29881114", UpdateChecker.parseTag("29881114"))
        assertNull(UpdateChecker.parseTag("v0.4.1"))
        assertNull(UpdateChecker.parseTag(""))
    }
}
