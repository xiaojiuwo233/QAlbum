package com.xjw.qalbum.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.preferences.SettingsRepository

/**
 * 保存到相册：选择目标子目录（含根目录）、临时新建目录、选择「保存后移入回收站」。
 *
 * 图库 / 详情 / 预览的「保存」共用。[onConfirm] 回传所选子目录（null = 根目录）与是否移入回收站；
 * 显示 / 关闭由外部状态控制（[onDismiss] 负责复位）。归档的图片不会删除，[allowTrash] 为 false 时不提供移入回收站。
 */
@Composable
fun SaveToAlbumSheet(
    imageCount: Int,
    subdirs: List<String>,
    onCreateSubdir: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (subdir: String?, alsoTrash: Boolean) -> Unit,
    allowTrash: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf<String?>(null) } // null = 根目录
    var alsoTrash by remember { mutableStateOf(allowTrash) }
    var newDir by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text(
                    if (imageCount == 1) "保存到相册" else "保存 $imageCount 张到相册",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "保存位置：Pictures/QAlbum" + (selected?.let { "/$it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DirectoryChip(label = "QAlbum（根目录）", selected = selected == null) { selected = null }
                subdirs.forEach { dir ->
                    DirectoryChip(label = dir, selected = selected == dir) { selected = dir }
                }
            }

            // 新建目录同步写入设置（全局可见），建好后自动选中
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                OutlinedTextField(
                    value = newDir,
                    onValueChange = { newDir = it },
                    placeholder = { Text("新建目录") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                val cleanNew = SettingsRepository.sanitizeExportSubdir(newDir)
                FilledTonalIconButton(
                    onClick = { cleanNew?.let { onCreateSubdir(it); selected = it; newDir = "" } },
                    enabled = cleanNew != null,
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建目录") }
            }

            if (allowTrash) {
                SegmentedSwitchItem(
                    position = SegmentPosition.Single,
                    title = "保存后移入回收站",
                    subtitle = "只对保存成功的图片生效，保留期内可在回收站恢复",
                    checked = alsoTrash,
                    onCheckedChange = { alsoTrash = it },
                )
            }

            Button(
                onClick = { onConfirm(selected, alsoTrash) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = ButtonDefaults.MediumContentPadding,
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("保存")
            }
        }
    }
}

@Composable
private fun DirectoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                if (selected) Icons.Outlined.Check else Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
    )
}
