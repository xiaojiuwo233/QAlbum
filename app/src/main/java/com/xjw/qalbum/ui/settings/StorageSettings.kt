package com.xjw.qalbum.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCount
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.WavyProgress
import org.koin.androidx.compose.koinViewModel

/** 存储空间：数据库大小与精简（离开页面也继续），图片缓存与运行日志的大小和清除 */
@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val usage by viewModel.usage.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val message by viewModel.message.collectAsState()

    SettingsPageScaffold(SettingsCategory.STORAGE, onBack, sharedScope, animatedScope) { notify ->
        LaunchedEffect(message) {
            message?.let { notify(it); viewModel.acknowledgeMessage() }
        }
        val database = usage?.database
        SegmentedGroup(header = "数据库") {
            item("database") { position ->
                SegmentedCard(position, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShapeIcon(
                            Icons.Outlined.Storage,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(database?.let { formatBytes(it.totalBytes) } ?: "统计中…", style = MaterialTheme.typography.headlineSmallEmphasized)
                            database?.let {
                                Text(
                                    listOfNotNull(
                                        "${formatCount(it.images)} 张图片",
                                        it.sources.takeIf { n -> n > 0 }?.let { n -> "${formatCount(n)} 条聊天来源" },
                                        it.videos.takeIf { n -> n > 0 }?.let { n -> "${formatCount(n)} 个视频" },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    val running = progress
                    when {
                        running != null -> {
                            Text(running.label, style = MaterialTheme.typography.titleSmall)
                            WavyProgress(if (running.total > 0) running.done.toFloat() / running.total else null)
                        }
                        database == null -> Unit
                        database.reclaimableBytes >= MIN_SLIM_BYTES -> {
                            SettingsBlurb(
                                buildList {
                                    if (database.legacyBytes > 0) add("旧版存下的图片路径约 ${formatBytes(database.legacyBytes)}，现在用不上了")
                                    if (database.freeBytes > 0) add("空闲 ${formatBytes(database.freeBytes)}")
                                }.joinToString("；") + "。",
                            )
                            Button(onClick = viewModel::slim, shapes = ButtonDefaults.shapes()) {
                                Text("精简数据库，约省 ${formatBytes(database.reclaimableBytes)}")
                            }
                        }
                        else -> SettingsBlurb("已经很精简了。")
                    }
                }
            }
        }
        if (progress != null || (database?.reclaimableBytes ?: 0) >= MIN_SLIM_BYTES) {
            SettingsNote(if ((database?.sources ?: 0) > 0) "精简时不能读取聊天来源，离开页面也会继续。" else "精简在后台进行，离开页面也会继续。")
        }

        SegmentedGroup(header = "缓存与日志") {
            item("cache") { position ->
                SegmentedListItem(
                    position = position,
                    leading = { ShapeIcon(Icons.Outlined.Image) },
                    headline = { Text("图片缓存") },
                    supporting = { Text(usage?.let { formatBytes(it.cacheBytes) } ?: "统计中…") },
                    trailing = { TextButton(onClick = viewModel::clearCache, enabled = (usage?.cacheBytes ?: 0) > 0) { Text("清除") } },
                )
            }
            item("logs") { position ->
                SegmentedListItem(
                    position = position,
                    leading = { ShapeIcon(Icons.AutoMirrored.Outlined.Article) },
                    headline = { Text("运行日志") },
                    supporting = { Text(usage?.let { formatBytes(it.logBytes) } ?: "统计中…") },
                    trailing = { TextButton(onClick = viewModel::clearLogs, enabled = (usage?.logBytes ?: 0) > 0) { Text("清除") } },
                )
            }
        }
        SettingsNote("图片缓存是网格小图和视频封面，清除后浏览时重新生成。")
    }
}

/** 能省下这么多才值得精简 */
private const val MIN_SLIM_BYTES = 32L * 1024 * 1024
