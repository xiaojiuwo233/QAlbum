package com.xjw.qalbum.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.preferences.SettingsRepository

/**
 * 保存到相册弹窗：选择目标子目录（含「根目录」）、临时新建目录、勾选「保存后移入回收站」。
 *
 * 图库 / 详情 / 审核预览的「保存」共用同一弹窗。[onConfirm] 回传所选子目录（null = 根目录）
 * 与是否移入回收站；弹窗的显示/关闭由外部状态控制（[onDismiss] 负责复位）。
 */
@Composable
fun SaveToAlbumSheet(
    imageCount: Int,
    subdirs: List<String>,
    onCreateSubdir: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (subdir: String?, alsoTrash: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var selected by remember { mutableStateOf<String?>(null) } // null = 根目录
    var alsoTrash by remember { mutableStateOf(true) }
    var newDir by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "保存 $imageCount 张到相册",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "目标：Pictures/QAlbum" + (selected?.let { "/$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 目录选择：根目录 + 已建子目录（横向滚动）
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selected == null,
                    onClick = { selected = null },
                    label = { Text("QAlbum（根目录）") },
                )
                subdirs.forEach { dir ->
                    FilterChip(
                        selected = selected == dir,
                        onClick = { selected = dir },
                        label = { Text(dir) },
                    )
                }
            }

            // 临时新建目录（同步写入设置，全局可见；新建后自动选中）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newDir,
                    onValueChange = { newDir = it },
                    placeholder = { Text("新建目录名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                val cleanNew = SettingsRepository.sanitizeExportSubdir(newDir)
                FilledTonalButton(
                    onClick = {
                        cleanNew?.let { onCreateSubdir(it); selected = it; newDir = "" }
                    },
                    enabled = cleanNew != null,
                ) {
                    Text("新建")
                }
            }

            // 保存后移入回收站（默认勾选）
            Row(
                modifier = Modifier.fillMaxWidth().clickable { alsoTrash = !alsoTrash },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = alsoTrash, onCheckedChange = { alsoTrash = it })
                Spacer(Modifier.width(4.dp))
                Text("保存后将原图移入回收站", style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = { onConfirm(selected, alsoTrash) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存")
            }
        }
    }
}
