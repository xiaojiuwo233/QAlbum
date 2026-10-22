package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.service.cleanup.rules.RuleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class CleanupRuleTest {

    private val now = 1_760_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `规则 id 能还原成同一条规则`() {
        CleanupRule.listed.forEach { assertSame(it, CleanupRule.byId(it.id)) }
        assertNull(CleanupRule.byId("unknown"))
    }

    @Test
    fun `时间段规则的 id 带上起止与标题 标题里有冒号也能还原`() {
        val range = CleanupRule.TimeRange(1_000, 2_000, "2024 年 3 月 12:00")
        assertEquals(range, CleanupRule.byId(range.id))
        assertEquals("这段时间", (CleanupRule.byId("range:1:2:") as CleanupRule.TimeRange).label)
        assertNull(CleanupRule.byId("range:x:2:标题"))
        assertNull(CleanupRule.byId("range:1"))
    }

    @Test
    fun `默认参数在可选项里`() {
        CleanupRule.listed.filter { it.options.isNotEmpty() }.forEach { rule ->
            assertTrue(rule.id, rule.option(rule.defaultOption) != null)
        }
    }

    @Test
    fun `所有规则都排除归档的图 整张图的规则只算图库里看得到的代表图`() {
        val rules = CleanupRule.listed + CleanupRule.chat + CleanupRule.TimeRange(0, now, "全部")
        rules.forEach { rule ->
            (rule.options.map { it.value }.ifEmpty { listOf(rule.defaultOption) }).forEach { option ->
                val sql = rule.where(option, now).sql
                assertTrue("${rule.id}:$option", sql.contains("i.is_protected = 0"))
                if (rule.scope == RuleScope.PICTURE) assertTrue("${rule.id}:$option", sql.contains(CleanupRule.PICTURE))
            }
        }
    }

    @Test
    fun `按天数的截止时间 选全部时不限时间`() {
        assertEquals(listOf<Any>(now - 30 * day), CleanupRule.Thumbnails.where(30, now).args)
        assertTrue(CleanupRule.Thumbnails.where(0, now).args.isEmpty())
        assertEquals(listOf<Any>(now - 90 * day), CleanupRule.SlimOriginals.where(90, now).args)
        assertEquals(listOf<Any>(1536 * 1024L), CleanupRule.CompressOriginals.where(1536, now).args)
    }

    @Test
    fun `旧图片按自然日截止到 N 年前的零点`() {
        val zone = ZoneId.systemDefault()
        val cutoff = CleanupRule.OldPictures.where(2, now).args.single() as Long
        val at = Instant.ofEpochMilli(cutoff).atZone(zone)
        assertEquals(LocalTime.MIDNIGHT, at.toLocalTime())
        assertEquals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusYears(2), at.toLocalDate())
    }
}
