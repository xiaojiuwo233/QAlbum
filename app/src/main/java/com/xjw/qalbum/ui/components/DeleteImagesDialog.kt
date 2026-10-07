package com.xjw.qalbum.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import com.xjw.qalbum.service.cleanup.PictureDeletePreview
import com.xjw.qalbum.service.cleanup.QuarantineManager
import org.koin.compose.koinInject
import com.xjw.qalbum.qq.QqSourceScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xjw.qalbum.ui.common.formatBytes

/**
 * 删除图片的确认：默认「移入回收站」（可恢复），次选「永久删除」（错误色，[onPermanent] 为 null 时不提供）。
 *
 * [sizeOf] 异步统计所选图片的总大小，算出前显示「计算中…」。[note] 用于补充来源相关的提醒。
 * 传入 [imageIds] 时提供「关联版本 / 共用图片」选项，初值为 [initialOptions]；所选不超过
 * [DETAILED_PREVIEW_LIMIT] 张时逐个核对关联文件并列出，更多时只显示数量与大小，核对放到实际执行时。
 */
@Composable
fun DeleteImagesDialog(
    count: Int,
    onDismiss: () -> Unit,
    onTrash: () -> Unit,
    onPermanent: (() -> Unit)?,
    sizeOf: (suspend () -> Long)? = null,
    note: String? = null,
    imageIds: Collection<Long>? = null,
    onTrashOptions: ((PictureDeleteOptions) -> Unit)? = null,
    onPermanentOptions: ((PictureDeleteOptions) -> Unit)? = null,
    sourceScope: QqSourceScope? = null,
    previewOf: (suspend (PictureDeleteOptions) -> PictureDeletePreview)? = null,
    initialOptions: PictureDeleteOptions = PictureDeleteOptions(),
) {
    val bytes by produceState(initialValue = -1L, count) { value = sizeOf?.invoke() ?: -1L }
    var options by remember(imageIds) { mutableStateOf(initialOptions) }
    val detailed = imageIds != null && imageIds.size <= DETAILED_PREVIEW_LIMIT
    val manager = if(imageIds!=null && previewOf==null)koinInject<QuarantineManager>() else null
    val plan by produceState<PictureDeletePreview?>(null,imageIds,options,sourceScope) {
        value=null
        if(detailed)value=previewOf?.invoke(options)?:manager!!.previewDeletion(imageIds!!,options,sourceScope)
    }
    // 大量选择不逐个核对，但仍要先确认文件访问可用，否则执行时会整批跳过
    val access by produceState<AccessCheck>(AccessCheck.Checking, imageIds) {
        value = if (imageIds == null || detailed || manager == null) AccessCheck.Ready
            else manager.fileAccessError()?.let { AccessCheck.Failed(it) } ?: AccessCheck.Ready
    }
    val ready = if (detailed) plan?.error==null && (plan?.removablePictures?:0)>0 else access == AccessCheck.Ready
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text(if(imageIds!=null)"删除所选图片？" else if (count == 1) "删除这张图片？" else "删除 $count 张图片？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (onPermanent != null) "移入回收站后可在「清理 → 回收站」恢复；永久删除无法撤销。"
                    else "移入回收站后可在「清理 → 回收站」恢复。",
                )
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(imageIds!=null) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(options.linkedVersions,{ options=options.copy(linkedVersions=it) })
                        Text("同时删除已关联的原图、预览图和缩略图",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(options.includeShared,{ options=options.copy(includeShared=it) })
                        Text("包含其他聊天共用的图片",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                    }
                    val current=plan
                    Text(when {
                        !detailed -> (access as? AccessCheck.Failed)?.message
                            ?: if (bytes >= 0) "已选 $count 张 · ${formatBytes(bytes)}，执行时逐组核对关联文件" else "已选 $count 张"
                        current==null -> "正在核对关联文件…"
                        current.error!=null -> current.error
                        else -> "${current.pictures} 张图片 · ${current.files} 个文件 · ${formatBytes(current.bytes)}"
                    },style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                    current?.let { p ->
                        if(p.filePaths.isNotEmpty())LazyColumn(Modifier.heightIn(max=112.dp)) {
                            items(p.filePaths) { path -> Text(path.split('/').takeLast(3).joinToString("/"),
                                style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis) }
                            if(p.files>p.filePaths.size)item { Text("另 ${p.files-p.filePaths.size} 个文件",style=MaterialTheme.typography.bodySmall) }
                        }
                        if(p.protectedPictures>0 || p.sharedPictures>0 || p.unavailablePictures>0)
                            Text("跳过：保护 ${p.protectedPictures} 组、共用 ${p.sharedPictures} 组、无法核查 ${p.unavailablePictures} 组",style=MaterialTheme.typography.bodySmall)
                        if(p.unlinkedPictures>0 && options.linkedVersions)Text("${p.unlinkedPictures} 张尚无完整关联，仅处理所选文件。",style=MaterialTheme.typography.bodySmall)
                    }
                    Text(if(options.linkedVersions)"关联只覆盖已读取的本地消息。删除缓存会影响 QQ 内查看；收藏或保留任一版本时跳过整组。"
                        else "仅处理所选文件，收藏或保留的文件会跳过。删除缓存会影响 QQ 内查看。",style=MaterialTheme.typography.bodySmall)
                } else if (sizeOf != null) {
                    Text(
                        if (bytes >= 0) "$count 张 · ${formatBytes(bytes)}" else "$count 张 · 计算中…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onTrashOptions?.invoke(options)?:onTrash() },enabled=ready) { Text("移入回收站") } },
        dismissButton = {
            Row {
                if (onPermanent != null) {
                    TextButton(
                        onClick = { onPermanentOptions?.invoke(options)?:onPermanent() },
                        enabled = ready,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("永久删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 超过这个数量不再逐个预先核对关联文件（大量选择时核对本身要很久） */
private const val DETAILED_PREVIEW_LIMIT = 300

/** 大量选择时的文件访问检查结果 */
private sealed interface AccessCheck {
    data object Checking : AccessCheck
    data object Ready : AccessCheck
    data class Failed(val message: String) : AccessCheck
}
