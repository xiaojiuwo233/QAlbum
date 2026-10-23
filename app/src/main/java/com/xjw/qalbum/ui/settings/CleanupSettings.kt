package com.xjw.qalbum.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.xjw.qalbum.ui.cleanup.ruleIcon
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
                        onCheckedChange = { on -> viewModel.setScheduledCleanup(rule.id, on) },
                    )
                }
            }
        }
        SettingsNote(
            "每周把选中规则的图移入回收站，条件用规则页上选的。" + (scheduledLast?.let { "\n上次：$it" } ?: ""),
        )
    }
}

private val RETENTION_OPTIONS = listOf(7, 15, 30, 60)
