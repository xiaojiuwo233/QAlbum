package com.xjw.qalbum.service.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun tagIsVersionCodeThenVersionName() {
        assertEquals(29881114L to "0.4.1", UpdateChecker.parseTag("29881114-0.4.1"))
        assertEquals(29881114L to "0.4.1-beta", UpdateChecker.parseTag("v29881114-0.4.1-beta"))
        assertEquals(29881114L to "29881114", UpdateChecker.parseTag("29881114"))
        assertNull(UpdateChecker.parseTag("v0.4.1"))
        assertNull(UpdateChecker.parseTag(""))
    }
}
