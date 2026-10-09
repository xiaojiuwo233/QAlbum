package com.xjw.qalbum.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.qq.QqSourceScope
import com.xjw.qalbum.service.cleanup.PictureDeleteFacts
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.ui.common.formatBytes
import org.koin.compose.koinInject

/**
 * 删除图片的确认：默认「移入回收站」（可恢复），[onPermanent] 不为 null 时另有「永久删除」（错误色）。
 *
 * 只在用得上时才出现选项：所选图片还有其他版本（原图 / 预览图 / 缩略图）时显示「连同其他版本」，
 * 其中有其他聊天也在用的图片时显示「包括共用的 N 张」；已收藏 / 保留的一行带过。
 * 这些都只查库（[QuarantineManager.deleteFacts]），实际删除时再逐组核对文件。
 */
@Composable
fun DeleteImagesDialog(
    imageIds: Collection<Long>,
    onDismiss: () -> Unit,
    onTrash: (PictureDeleteOptions) -> Unit,
    onPermanent: ((PictureDeleteOptions) -> Unit)? = null,
    sourceScope: QqSourceScope? = null,
    initialOptions: PictureDeleteOptions = PictureDeleteOptions(),
    noun: String = "图片",
    note: String? = null,
) {
    val manager = koinInject<QuarantineManager>()
    val facts by produceState<PictureDeleteFacts?>(null, imageIds, sourceScope) {
        value = manager.deleteFacts(imageIds, sourceScope)
    }
    var options by remember(imageIds) { mutableStateOf(initialOptions) }
    val count = imageIds.size
    val current = facts
    val ready = current != null && current.accessError == null && current.removable(options) > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text(if (count == 1) "删除这张$noun？" else "删除 $count 张$noun？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    buildString {
                        if (current != null) append(formatBytes(current.bytes(options))).append(" · ")
                        append("移入回收站后可恢复")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current != null && current.withVersions > 0) {
                    OptionRow(
                        checked = options.linkedVersions,
                        label = "连同原图、缩略图等其他版本",
                        onChange = { options = options.copy(linkedVersions = it) },
                    )
                }
                if (current != null && current.shared > 0) {
                    OptionRow(
                        checked = options.includeShared,
                        label = if (count == 1) "其他聊天也在用，仍然删除" else "包括其他聊天也在用的 ${current.shared} 张",
                        onChange = { options = options.copy(includeShared = it) },
                    )
                }
                val hint = when {
                    current == null -> null
                    current.accessError != null -> current.accessError
                    current.protectedCount >= count -> if (count == 1) "已收藏或保留，不会删除" else "都已收藏或保留，不会删除"
                    current.protectedCount > 0 -> "已收藏或保留的 ${current.protectedCount} 张不会删除"
                    else -> null
                }
                (listOfNotNull(hint, note)).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line == current?.accessError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onTrash(options) }, enabled = ready) { Text("移入回收站") } },
        dismissButton = {
            Row {
                if (onPermanent != null) {
                    TextButton(
                        onClick = { onPermanent(options) },
                        enabled = ready,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("永久删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun OptionRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(end = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
