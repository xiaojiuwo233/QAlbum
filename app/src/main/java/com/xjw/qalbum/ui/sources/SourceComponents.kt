package com.xjw.qalbum.ui.sources

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.db.dao.QqContent
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.domain.model.SourceSort
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.common.formatRelativeDay
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedListItem

/** 名称缺失时的占位文案 */
internal fun facetDisplayName(facet: QqSourceFacet, kind: SourceKind): String = facet.name.ifBlank {
    when {
        kind == SourceKind.SENDER -> "发送人名称未缓存"
        facet.chatType == 3 -> "转发的聊天记录"
        facet.chatType == 2 -> "群名称未缓存"
        facet.chatType == 1 -> "会话名称未缓存"
        else -> "名称未缓存"
    }
}

/**
 * 首字头像：群聊用 Expressive 曲奇形、个人用圆形；颜色按 key 在三组色调容器中稳定分配。
 */
@Composable
internal fun SourceAvatar(name: String, key: String, group: Boolean, size: Dp = 40.dp) {
    val scheme = MaterialTheme.colorScheme
    val palettes = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
    )
    val (container, content) = palettes[(key.hashCode() and Int.MAX_VALUE) % palettes.size]
    val shape = if (group) MaterialShapes.Cookie6Sided.toShape() else CircleShape
    Surface(shape = shape, color = container, contentColor = content, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.trim().firstOrNull()?.toString() ?: "?",
                // 筛选条上的小头像只有 18dp，字号随尺寸缩小，避免文字被裁切
                style = when {
                    size <= 24.dp -> MaterialTheme.typography.labelSmall
                    size <= 32.dp -> MaterialTheme.typography.labelLarge
                    else -> MaterialTheme.typography.titleMediumEmphasized
                },
                maxLines = 1,
            )
        }
    }
}

/** 占用比例条：首次出现时从 0 平滑增长到目标比例 */
@Composable
internal fun ShareBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(fraction) { progress.animateTo(fraction.coerceIn(0f, 1f), spec) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.value.coerceIn(0.012f, 1f))
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** 分类在某类内容下的数量与占用：null = 图片与缩略图合计 */
internal fun QqSourceFacet.countOf(content: QqContent?): Int = when (content) {
    QqContent.IMAGES -> count
    QqContent.THUMBNAILS -> thumbCount
    null -> totalCount
}

internal fun QqSourceFacet.bytesOf(content: QqContent?): Long = when (content) {
    QqContent.IMAGES -> bytes
    QqContent.THUMBNAILS -> thumbBytes
    null -> totalBytes
}

/**
 * 分类行：头像 + 名称 + 「N 张 · 缩略图 M · 最近」+ 占用比例条，尾部是占用空间。
 * [content] 为 null 时显示图片与缩略图合计（来源首页），否则只看当前浏览的内容。
 */
@Composable
internal fun SourceFacetRow(
    facet: QqSourceFacet,
    kind: SourceKind,
    position: SegmentPosition,
    maxBytes: Long,
    showAccount: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: QqContent? = null,
) {
    val name = facetDisplayName(facet, kind)
    SegmentedListItem(
        position = position,
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        leading = { SourceAvatar(name, facet.key, group = kind != SourceKind.SENDER && facet.chatType == 2) },
        headline = {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supporting = {
            Column {
                val parts = buildList {
                    if (facet.alias.isNotBlank()) add(facet.alias)
                    when (content) {
                        null -> {
                            if (facet.count > 0 || facet.thumbCount == 0) add("${formatCountExact(facet.count)} 张")
                            if (facet.thumbCount > 0) add("缩略图 ${formatCountExact(facet.thumbCount)}")
                        }
                        QqContent.IMAGES -> add("${formatCountExact(facet.count)} 张")
                        QqContent.THUMBNAILS -> add("缩略图 ${formatCountExact(facet.thumbCount)}")
                    }
                    if (facet.latestTime > 0) add("最近 ${formatRelativeDay(facet.latestTime)}")
                    if (showAccount) add(facet.accountName)
                }
                Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                ShareBar(if (maxBytes > 0) facet.bytesOf(content).toFloat() / maxBytes else 0f)
            }
        },
        trailing = {
            Text(
                formatBytes(facet.bytesOf(content)),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
            )
        },
    )
}

/** 排序菜单：日期 / 大小 × 新旧 / 大小 */
@Composable
internal fun SourceSortMenuButton(sort: SourceSort, onSort: (SourceSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "排序：${sort.label}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SourceSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label)
                            Text(
                                if (option.byDate) "按日期" else "按文件大小",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(if (option.byDate) Icons.Outlined.Event else Icons.Outlined.SdStorage, contentDescription = null)
                    },
                    trailingIcon = if (option == sort) {
                        { Icon(Icons.Outlined.Check, contentDescription = "当前排序") }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                )
            }
        }
    }
}

/** 两段式统计：数量 + 占用空间 */
internal fun statsText(count: Int?, bytes: Long?): String =
    if (count == null || bytes == null) "正在统计…" else "${formatCountExact(count)} 张 · ${formatBytes(bytes)}"

@Composable
internal fun InfoLine(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
