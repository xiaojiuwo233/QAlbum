package com.xjw.qalbum.service.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QqCacheCleanerTest {

    @Test
    fun childLinesKeepNamesWithSeparators() {
        assertEquals(QqCacheCleaner.Child("compressor", true, 12, 3456), QqCacheCleaner.parseChild("D|12|3456|compressor"))
        assertEquals(QqCacheCleaner.Child("a|b.jpg", false, 1, 9), QqCacheCleaner.parseChild("F|1|9|a|b.jpg"))
        assertNull(QqCacheCleaner.parseChild("D|x|1|name"))
        assertNull(QqCacheCleaner.parseChild("garbage"))
    }

    @Test
    fun statesMapFromThePrivilegedCodes() {
        assertEquals(QqCacheCleaner.State.PRESENT, QqCacheCleaner.stateOf(1))
        assertEquals(QqCacheCleaner.State.MISSING, QqCacheCleaner.stateOf(0))
        assertEquals(QqCacheCleaner.State.DENIED, QqCacheCleaner.stateOf(-1))
        assertEquals(QqCacheCleaner.State.ERROR, QqCacheCleaner.stateOf(-2))
    }
}
