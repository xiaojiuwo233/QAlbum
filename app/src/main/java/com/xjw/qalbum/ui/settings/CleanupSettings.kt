package com.xjw.qalbum.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xjw.qalbum.data.db.dao.RuleStats
import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.ui.cleanup.ruleIcon
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedSwitchItem
import org.koin.androidx.compose.koinViewModel

/** 清理与回收站：回收站保留几天、每周自动清理哪些规则 */
@Composable
fun CleanupSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val retentionDays by viewModel.quarantineRetentionDays.collectAsState()
    val scheduled by viewModel.scheduledCleanup.collectAsState()
    val scheduledRules by viewModel.scheduledRules.collectAsState()
    val scheduledLast by viewModel.scheduledCleanupLast.collectAsState()
    // 开启前先确认：告诉用户现在符合的有多少张，第一次执行就会全部移入回收站
    var confirming by remember { mutableStateOf<Pair<CleanupRule, String>?>(null) }

    SettingsPageScaffold(SettingsCategory.CLEANUP, onBack, sharedScope, animatedScope) { notify ->
        SegmentedGroup(header = "回收站") {
            item("retention") { position ->
                ChoiceCard(
                    position = position,
                    title = "保留天数",
                    description = "到期自动删除",
                    icon = Icons.Outlined.AutoDelete,
                    options = RETENTION_OPTIONS,
                    selected = retentionDays.takeIf { it in RETENTION_OPTIONS },
                    label = { "$it 天" },
                    onSelect = { days ->
                        viewModel.setQuarantineRetentionDays(days)
                        notify("回收站保留 $days 天")
                    },
                )
            }
        }
        SettingsNote("只对之后移入的图生效。压缩时保留的原图也按这个天数删除。")

        SegmentedGroup(header = "每周自动清理") {
            scheduledRules.forEach { (rule, option) ->
                item("scheduled-${rule.id}") { position ->
                    SegmentedSwitchItem(
                        position = position,
                        icon = ruleIcon(rule),
                        title = rule.title,
                        subtitle = option.ifBlank { null },
                        checked = rule.id in scheduled,
                        onCheckedChange = { on -> if (on) confirming = rule to option else viewModel.setScheduledCleanup(rule.id, false) },
                    )
                }
            }
        }
        SettingsNote(
            "每周把选中规则的图移入回收站，条件用规则页上选的。" + (scheduledLast?.let { "\n上次：$it" } ?: ""),
        )

        confirming?.let { (rule, option) ->
            val stats by produceState<RuleStats?>(null, rule) { value = viewModel.scheduledRulePreview(rule) }
            val name = "「${rule.title}${option.ifBlank { null }?.let { " · $it" } ?: ""}」"
            ConfirmDialog(
                icon = ruleIcon(rule),
                title = "开启每周自动清理？",
                text = stats.let { found ->
                    when {
                        found == null -> "正在统计${name}现在有多少张…"
                        found.count == 0 -> "${name}现在没有图，开启后每周检查一次。"
                        else -> "${name}现在有 ${formatCountExact(found.count)} 张（${formatBytes(found.bytes)}），" +
                            "开启后一天内会全部移入回收站，之后每周一次。"
                    }
                },
                confirmLabel = "开启",
                onConfirm = {
                    confirming = null
                    viewModel.setScheduledCleanup(rule.id, true)
                    notify("已开启每周自动清理「${rule.title}」")
                },
                onDismiss = { confirming = null },
            )
        }
    }
}

private val RETENTION_OPTIONS = listOf(7, 15, 30, 60)
