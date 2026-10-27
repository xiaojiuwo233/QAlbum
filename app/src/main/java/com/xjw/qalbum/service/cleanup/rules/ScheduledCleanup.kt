package com.xjw.qalbum.service.cleanup.rules

import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.RuleStats
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatDateTime
import kotlinx.coroutines.flow.first

/**
 * 定时清理：把设置里选中的规则（各用当前参数）命中的图依次移入回收站，返回汇总。
 * 没有文件访问（Shizuku 没在跑、Root 没授权）就整次跳过；按聊天的规则没读取过来源时命中为 0，关了高级功能时不执行。
 *
 * [accessError] 为文件访问不可用的原因（可用为 null），[trash] 移入回收站并返回处理的张数
 * （实际由 QuarantineManager / CleanupRunner 提供，测试里可以换掉）。
 */
class ScheduledCleanup(
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    private val accessError: suspend () -> String?,
    private val trash: suspend (ids: List<Long>, title: String, linkedVersions: Boolean) -> Int,
) {
    /** [moved] 移入回收站的张数，[bytes] 约占用，[parts] 每条规则的结果；[skipped] 没执行的原因 */
    data class Summary(val moved: Int, val bytes: Long, val parts: List<String>, val skipped: String? = null) {
        val text: String
            get() = when {
                skipped != null -> "没执行：$skipped"
                moved == 0 -> "没有要清理的图"
                else -> "移入回收站 $moved 张（约 ${formatBytes(bytes)}）：" + parts.joinToString("、")
            }
    }

    suspend fun run(now: Long = System.currentTimeMillis()): Summary {
        val enabled = settings.scheduledCleanupRules.first()
        val chat = settings.advancedFeatures.first()
        val rules = eligible.filter { it.id in enabled && (chat || it !in CleanupRule.chat) }
        if (rules.isEmpty()) return Summary(0, 0, emptyList())
        val summary = accessError()?.let { Summary(0, 0, emptyList(), skipped = it) } ?: trashAll(rules, now)
        settings.setScheduledCleanupLast("${formatDateTime(now)} · ${summary.text}")
        return summary
    }

    /** [rule] 现在执行的话会移入多少（开启前给用户确认） */
    suspend fun preview(rule: CleanupRule, now: Long = System.currentTimeMillis()): RuleStats =
        db.cleanupRuleDao().stats(RuleSql.stats(rule.where(optionOf(rule), now), rule.scope))

    /** 规则页上选的条件（选项已经没有了就用默认的） */
    private suspend fun optionOf(rule: CleanupRule): Int =
        settings.cleanupRuleParam(rule.id).first()?.takeIf { rule.option(it) != null } ?: rule.defaultOption

    private suspend fun trashAll(rules: List<CleanupRule>, now: Long): Summary {
        val dao = db.cleanupRuleDao()
        var moved = 0
        var bytes = 0L
        val parts = ArrayList<String>()
        for (rule in rules) {
            val part = rule.where(optionOf(rule), now)
            val stats = dao.stats(RuleSql.stats(part, rule.scope))
            if (stats.count == 0) continue
            val done = trash(dao.ids(RuleSql.ids(part)), rule.title, rule.scope == RuleScope.PICTURE)
            if (done == 0) continue
            moved += done
            bytes += stats.bytes * done / stats.count
            parts += "${rule.title} $done 张"
        }
        return Summary(moved, bytes, parts)
    }

    companion object {
        /** 能定时执行的规则：移入回收站的规则（压缩要先看效果，不自动做） */
        val eligible: List<CleanupRule> get() = (CleanupRule.listed + CleanupRule.chat).filter { it.action == RuleAction.TRASH }
    }
}
