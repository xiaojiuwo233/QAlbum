package com.xjw.qalbum.ui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoDurationTest {
    @Test
    fun durationsRoundToSecondsAndShowHoursOnlyWhenNeeded() {
        assertNull(formatDuration(-1))
        assertNull(formatDuration(0))
        assertEquals("0:01", formatDuration(600))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }
}
