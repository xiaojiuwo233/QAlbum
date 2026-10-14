package com.xjw.qalbum.ui.cleanup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.service.cleanup.rules.CleanupRule
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.LoadingState
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedNavigationItem
import java.time.LocalDate
import java.time.ZoneId

/** 按时间清理：整张图按年、按月列出张数与占用（归档的不算），点进去预览后整批清理 */
@Composable
fun TimeCleanupScreen(
    viewModel: CleanupViewModel,
    listState: LazyListState,
    barState: TopAppBarState,
    onBack: () -> Unit,
    onOpenRange: (CleanupRule.TimeRange) -> Unit,
) {
    val months by viewModel.months.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("按时间清理") },
                subtitle = { Text("按月统计，不含归档的图") },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val list = months
        when {
            list == null -> LoadingState(Modifier.padding(top = padding.calculateTopPadding()))
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                EmptyState(icon = Icons.Outlined.CheckCircle, title = "没有可清理的图片", message = "扫描到的图片会按月份显示在这里")
            }
            else -> {
                val years = list.groupBy { it.year }.toList()
                val zone = ZoneId.systemDefault()
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(years, key = { it.first }) { (year, monthsOfYear) ->
                        val yearStart = LocalDate.of(year, 1, 1)
                        SegmentedGroup(
                            header = "$year 年 · ${formatCountExact(monthsOfYear.sumOf { it.count })} 张 · ${formatBytes(monthsOfYear.sumOf { it.bytes })}",
                        ) {
                            item("year-$year") { position ->
                                SegmentedNavigationItem(
                                    position = position,
                                    icon = Icons.Outlined.CalendarMonth,
                                    title = "整年",
                                    subtitle = "预览 $year 年的全部图片",
                                    onClick = {
                                        onOpenRange(
                                            CleanupRule.TimeRange(
                                                from = yearStart.atStartOfDay(zone).toInstant().toEpochMilli(),
                                                until = yearStart.plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                                                label = "$year 年",
                                            ),
                                        )
                                    },
                                    iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                            monthsOfYear.forEach { month ->
                                item("${month.year}-${month.month}") { position ->
                                    SegmentedNavigationItem(
                                        position = position,
                                        title = "${month.month} 月",
                                        subtitle = "${formatCountExact(month.count)} 张 · ${formatBytes(month.bytes)}",
                                        onClick = { onOpenRange(CleanupRule.TimeRange(month.from, month.until, month.label)) },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "footer") { Box(Modifier.fillMaxWidth().height(8.dp)) }
                }
            }
        }
    }
}
