package com.xjw.qalbum.ui.detail

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.db.dao.QqOriginDetails
import com.xjw.qalbum.data.db.dao.QqSourceDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatDateTimeSeconds
import com.xjw.qalbum.ui.common.formatTitleDate
import com.xjw.qalbum.ui.common.sourceLabel
import com.xjw.qalbum.ui.components.SectionHeader
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SegmentedDefaults
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 图片详情卡：标题（时间 + 文件名）+ 文件信息 + QQ 来源 + OCR 文字。
 * 图库详情页与各处预览共用，保证「查看详情」完全一致。
 */
@Composable
internal fun PhotoDetailsSheet(
    image: ImageEntity,
    ocr: OcrTextEntity?,
    onClose: () -> Unit,
) {
    val sources = koinInject<QqSourceDao>()
    val sourceFlow = remember(image.id, sources) { sources.observeImageOrigins(image.id) }
    val origins by sourceFlow.collectAsState(initial = emptyList())
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
                            image.filename,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item(key = "meta") { MetadataBlock(image) }
                item(key = "source-header") { SectionHeader("QQ 来源") }
                if (origins.isEmpty()) {
                    item(key = "source-none") {
                        SegmentedCard(SegmentPosition.Single) {
                            Text(
                                "没有关联到本机聊天记录。可在「聊天来源」中读取后再查看。",
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
                ocr?.takeIf { it.text.isNotBlank() }?.let { text ->
                    item(key = "ocr-header") { SectionHeader("识别的文字") }
                    item(key = "ocr") { OcrBlock(text) }
                }
            }
        }
    }
}

@Composable
private fun MetadataBlock(image: ImageEntity) {
    SegmentedCard(SegmentPosition.Single, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InfoRow("大小", formatBytes(image.size))
        InfoRow("尺寸", if (image.width > 0) "${image.width} × ${image.height}" else "未读取")
        InfoRow("类型", "${sourceLabel(image.sourceType)} · ${image.mime}")
        InfoRow("修改时间", formatDateTimeSeconds(image.mtime))
        InfoRow("首次扫描", formatDateTimeSeconds(image.firstSeen))
        image.duplicateGroupId?.let { InfoRow("重复组", "#$it") }
        InfoRow("路径", image.path)
    }
}

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
        if (origin.messageCount > 1) InfoRow("出现次数", "${origin.messageCount} 条消息")
    }
}

@Composable
private fun OcrBlock(ocr: OcrTextEntity) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    SegmentedCard(SegmentPosition.Single) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "置信度 ${(ocr.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("OCR", ocr.text))) } },
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(Icons.Outlined.ContentCopy, contentDescription = "复制文字") }
        }
        val flags = buildList {
            if (ocr.hasIdNumber) add("证件号")
            if (ocr.hasOrderNo) add("订单号")
            if (ocr.hasAmount) add("金额")
            if (ocr.hasPhone) add("手机号")
            if (ocr.hasAddress) add("地址")
        }
        if (flags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                flags.forEach { flag ->
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            flag,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        SelectionContainer {
            Text(ocr.text.trim(), style = MaterialTheme.typography.bodyMedium)
        }
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
