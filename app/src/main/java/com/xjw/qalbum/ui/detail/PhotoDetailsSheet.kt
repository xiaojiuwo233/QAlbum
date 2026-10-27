package com.xjw.qalbum.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.access.ChatFeatures
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.QqOriginDetails
import com.xjw.qalbum.data.db.dao.QqSourceDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OriginalBackupEntity
import com.xjw.qalbum.service.cleanup.CompressionCoordinator
import com.xjw.qalbum.service.cleanup.OriginalCompressor
import com.xjw.qalbum.service.demo.DemoMode
import com.xjw.qalbum.service.demo.DemoNames
import com.xjw.qalbum.ui.common.LocalDemoMode
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatDateTime
import com.xjw.qalbum.ui.common.formatDateTimeSeconds
import com.xjw.qalbum.ui.common.formatTitleDate
import com.xjw.qalbum.ui.common.sourceLabel
import com.xjw.qalbum.ui.components.SectionHeader
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SegmentedDefaults
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * 图片详情卡：标题（时间 + 文件名）+ 文件信息 + QQ 来源。
 * 图库详情页与各处预览共用，保证「查看详情」完全一致；[header] 显示在文件信息之前（如回收站里的删除信息）。
 */
@Composable
internal fun PhotoDetailsSheet(
    image: ImageEntity,
    onClose: () -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val sources = koinInject<QqSourceDao>()
    // 演示模式下来源里的群、人换成虚构的
    val demoMode = koinInject<DemoMode>()
    val sourceFlow = remember(image.id, sources, demoMode) { demoMode.origins(sources.observeImageOrigins(image.id)) }
    val origins by sourceFlow.collectAsState(initial = emptyList())
    val demo = LocalDemoMode.current
    val chatFeatures = koinInject<ChatFeatures>()
    val chatAvailable by chatFeatures.available.collectAsState(initial = false)
    val backups = koinInject<AppDatabase>().originalBackupDao()
    val recordFlow = remember(image.id, backups) { backups.observeByImageId(image.id) }
    val record by recordFlow.collectAsState(initial = null)
    val compression = koinInject<CompressionCoordinator>()
    Surface(
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.66f),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
                IconButton(
                    onClick = onClose,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp),
                ) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(SegmentedDefaults.Gap),
            ) {
                item(key = "title") {
                    Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
                        Text(formatTitleDate(image.mtime), style = MaterialTheme.typography.headlineSmallEmphasized)
                        Text(
                            if (demo) DemoNames.filename(image) else image.filename,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (header != null) item(key = "header") { header() }
                item(key = "meta") { MetadataBlock(image, demo) }
                if (image.optimized == OriginalCompressor.COMPRESSED) {
                    item(key = "compress-header") { SectionHeader("原图压缩") }
                    // 在回收站里的图还原不了原图（压缩后的文件不在原位置）
                    item(key = "compress") {
                        CompressionBlock(record, onRestore = { compression.restoreOriginal(image.id) }, canRestore = image.status != "quarantined")
                    }
                }
                // 没开高级功能、或 Shizuku 模式下没读过来源时不显示这一节
                if (chatAvailable) {
                    item(key = "source-header") { SectionHeader("QQ 来源") }
                    if (origins.isEmpty()) {
                        item(key = "source-none") {
                            SegmentedCard(SegmentPosition.Single) {
                                Text(
                                    "未关联聊天记录。读取聊天来源后可看到发送人和会话。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        val showAccount = origins.map { it.accountName }.distinct().size > 1
                        origins.forEachIndexed { index, origin ->
                            item(key = "origin-$index") {
                                OriginCard(origin, SegmentPosition(index, origins.size), showAccount)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataBlock(image: ImageEntity, demo: Boolean) {
    SegmentedCard(SegmentPosition.Single, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InfoRow("大小", formatBytes(image.size))
        InfoRow("尺寸", if (image.width > 0) "${image.width} × ${image.height}" else "未读取")
        InfoRow("类型", "${sourceLabel(image.sourceType)} · ${image.mime}")
        InfoRow("修改时间", formatDateTimeSeconds(image.mtime))
        InfoRow("首次扫描", formatDateTimeSeconds(image.firstSeen))
        InfoRow("路径", if (demo) DemoNames.path(image) else image.path)
    }
}

/** 压缩记录：质量、压缩前后的大小与尺寸、压缩时间、原图备份（可以还原） */
@Composable
private fun CompressionBlock(record: OriginalBackupEntity?, onRestore: () -> Unit, canRestore: Boolean = true) {
    SegmentedCard(SegmentPosition.Single, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (record == null) {
            Text(
                "已压缩，没有压缩记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SegmentedCard
        }
        val saved = record.originalSize - record.compressedSize
        val percent = if (record.originalSize > 0) (saved * 100.0 / record.originalSize).roundToInt() else 0
        InfoRow("压缩质量", if (record.quality > 0) "${record.quality}%" else "未记录")
        if (record.originalMime == "image/png") InfoRow("格式", "PNG → JPEG")
        InfoRow("压缩前", "${formatBytes(record.originalSize)}" + dims(record.originalWidth, record.originalHeight))
        InfoRow("压缩后", "${formatBytes(record.compressedSize)}" + dims(record.compressedWidth, record.compressedHeight))
        InfoRow("省下", "${formatBytes(saved)}（-$percent%）")
        InfoRow("压缩时间", formatDateTime(record.createdAt))
        InfoRow(
            "原图",
            if (record.backupPath != null) "保留到 ${formatDateTime(record.expiresAt)}，可以还原"
            else "没有保留，无法还原",
        )
        if (record.backupPath != null && canRestore) {
            OutlinedButton(onClick = onRestore, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = 8.dp)) {
                Text("还原原图")
            }
        }
    }
}

private fun dims(width: Int, height: Int): String = if (width > 0 && height > 0) " · $width × $height" else ""

@Composable
private fun OriginCard(origin: QqOriginDetails, position: SegmentPosition, showAccount: Boolean) {
    SegmentedCard(position, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Icon(Icons.Outlined.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                origin.conversationName.ifBlank { when (origin.chatType) { 3 -> "转发的聊天记录"; 2 -> "群名称未缓存"; else -> "会话名称未缓存" } },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (origin.chatType) { 3 -> "转发"; 2 -> "群聊"; else -> "私聊" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfoRow("发送人", origin.senderName.ifBlank { "发送人名称未缓存" })
        if (origin.memberName.isNotBlank() && origin.memberName != origin.senderName) InfoRow("群名片", origin.memberName)
        if (showAccount) InfoRow("账号", origin.accountName)
        InfoRow("发送时间", formatDateTimeSeconds(origin.messageTime))
        if (origin.sentOriginal) InfoRow("发送方式", "原图")
        if (origin.messageCount > 1) InfoRow("出现次数", "${origin.messageCount} 条消息")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.7f))
    }
}
