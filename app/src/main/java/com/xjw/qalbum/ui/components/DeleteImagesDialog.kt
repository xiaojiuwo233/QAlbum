package com.xjw.qalbum.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.ui.common.formatBytes

/**
 * 删除图片的确认：默认「移入回收站」（可恢复），次选「永久删除」（错误色，[onPermanent] 为 null 时不提供）。
 *
 * [sizeOf] 异步统计所选图片的总大小，算出前显示「计算中…」。[note] 用于补充来源相关的提醒。
 */
@Composable
fun DeleteImagesDialog(
    count: Int,
    onDismiss: () -> Unit,
    onTrash: () -> Unit,
    onPermanent: (() -> Unit)?,
    sizeOf: (suspend () -> Long)? = null,
    note: String? = null,
) {
    val bytes by produceState(initialValue = -1L, count) { value = sizeOf?.invoke() ?: -1L }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text(if (count == 1) "删除这张图片？" else "删除 $count 张图片？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (onPermanent != null) "移入回收站后可在「清理 → 回收站」恢复；永久删除无法撤销。"
                    else "移入回收站后可在「清理 → 回收站」恢复。",
                )
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (sizeOf != null) {
                    Text(
                        if (bytes >= 0) "$count 张 · ${formatBytes(bytes)}" else "$count 张 · 计算中…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onTrash) { Text("移入回收站") } },
        dismissButton = {
            Row {
                if (onPermanent != null) {
                    TextButton(onClick = onPermanent) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
