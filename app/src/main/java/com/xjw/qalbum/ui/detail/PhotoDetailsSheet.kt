package com.xjw.qalbum.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.db.dao.QqSourceDao
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图片详情底部卡：标题（拍摄时间 + 文件名）+ 元数据 + OCR 文本。
 *
 * 图库详情页与审核清理预览页共用，保证两处「查看详情」完全一致。
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.62f)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 42.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    Text(
                        formatTitleDate(image.mtime),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        image.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item { MetadataBlock(image) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("QQ 来源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (origins.isEmpty()) Text("未关联到本机聊天记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    origins.forEach { origin ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val conversation = origin.conversationName.ifBlank { if (origin.chatType == 2) "群名称未缓存" else "会话名称未缓存" }
                            InfoRow(if (origin.chatType == 2) "群聊" else "私聊", conversation)
                            InfoRow("发送人", origin.senderName.ifBlank { "发送人名称未缓存" })
                            if (origin.memberName.isNotBlank() && origin.memberName != origin.senderName) InfoRow("群名片", origin.memberName)
                            if (origins.map { it.accountName }.distinct().size > 1) InfoRow("账号", origin.accountName)
                            InfoRow("发送时间", formatTime(origin.messageTime))
                            if (origin.messageCount > 1) Text("出现在 ${origin.messageCount} 条图片来源中", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                }
            }
            ocr?.takeIf { it.text.isNotBlank() }?.let { text ->
                item { OcrBlock(text) }
            }
        }
    }
}

@Composable
private fun MetadataBlock(image: ImageEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${image.sourceType} · ${image.mime}", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        InfoRow("大小", formatBytes(image.size))
        InfoRow("尺寸", if (image.width > 0) "${image.width} x ${image.height}" else "未读取")
        InfoRow("修改时间", formatTime(image.mtime))
        InfoRow("首次扫描", formatTime(image.firstSeen))
        InfoRow("路径", image.path)
        image.duplicateGroupId?.let {
            InfoRow("重复组", "#$it")
        }
    }
}

@Composable
private fun OcrBlock(ocr: OcrTextEntity) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "OCR 识别文本",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "置信度 ${(ocr.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("OCR", ocr.text))) } }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制文字")
            }
        }
        val flags = buildList {
            if (ocr.hasIdNumber) add("证件号")
            if (ocr.hasOrderNo) add("订单号")
            if (ocr.hasAmount) add("金额")
            if (ocr.hasPhone) add("手机号")
            if (ocr.hasAddress) add("地址")
        }
        if (flags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                flags.forEach { flag -> Text(flag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
        }
        SelectionContainer {
            Text(ocr.text.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
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
            modifier = Modifier.weight(0.32f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.68f),
        )
    }
}

private val detailTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
private val detailTitleFormatter = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)

private fun formatTime(ts: Long): String = detailTimeFormatter.format(Date(ts))

private fun formatTitleDate(ts: Long): String = detailTitleFormatter.format(Date(ts))

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.2f %s", value, units[exp])
}
