package com.xjw.qalbum.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.SegmentedSwitchItem
import com.xjw.qalbum.ui.components.ShapeIcon
import org.koin.androidx.compose.koinViewModel

/** 图库：合并版本、快速滚动、归档是否显示、打开图片的过渡动画、保存到相册的目录 */
@Composable
fun GallerySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val stackVariants by viewModel.galleryStackVariants.collectAsState()
    val fastScroll by viewModel.fastScrollEnabled.collectAsState()
    val archiveVisible by viewModel.archiveVisible.collectAsState()
    val sharedTransition by viewModel.detailSharedTransition.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }

    SettingsPageScaffold(SettingsCategory.GALLERY, onBack, sharedScope, animatedScope) { notify ->
        SegmentedGroup(header = "显示") {
            item("stack") { position ->
                SegmentedSwitchItem(
                    position = position,
                    icon = Icons.Outlined.Layers,
                    title = "合并同一张图的版本",
                    subtitle = "原图、预览图、缩略图合成一张，一起删除",
                    checked = stackVariants,
                    onCheckedChange = viewModel::setGalleryStackVariants,
                )
            }
            item("fastScroll") { position ->
                SegmentedSwitchItem(
                    position = position,
                    icon = Icons.Outlined.UnfoldMore,
                    title = "快速滚动条",
                    subtitle = "滚动时右侧出现把手，拖动快速定位",
                    checked = fastScroll,
                    onCheckedChange = viewModel::setFastScrollEnabled,
                )
            }
            item("archiveVisible") { position ->
                SegmentedSwitchItem(
                    position = position,
                    icon = Icons.Outlined.Archive,
                    title = "图库里显示归档的图",
                    subtitle = if (archiveVisible) "归档的图照常显示，不会被清理" else "只在「图库 → 归档」里显示",
                    checked = archiveVisible,
                    onCheckedChange = viewModel::setArchiveVisible,
                )
            }
            item("shared") { position ->
                SegmentedSwitchItem(
                    position = position,
                    icon = Icons.Outlined.Animation,
                    title = "从缩略图放大打开",
                    subtitle = "打开图片时缩略图放大成全屏（实验性）",
                    checked = sharedTransition,
                    onCheckedChange = viewModel::setDetailSharedTransition,
                )
            }
        }

        SegmentedGroup(header = "保存到相册") {
            exportSubdirs.forEach { dir ->
                item("dir-$dir") { position ->
                    SegmentedListItem(
                        position = position,
                        leading = { ShapeIcon(Icons.Outlined.Folder) },
                        headline = { Text(dir) },
                        supporting = { Text("Pictures/QAlbum/$dir") },
                        trailing = {
                            IconButton(
                                onClick = {
                                    viewModel.removeExportSubdir(dir)
                                    notify("已删除「$dir」，已保存的图不受影响")
                                },
                                shapes = IconButtonDefaults.shapes(),
                            ) { Icon(Icons.Outlined.Delete, contentDescription = "删除目录") }
                        },
                    )
                }
            }
            item("add") { position ->
                SegmentedListItem(
                    position = position,
                    onClick = { adding = true },
                    leading = {
                        ShapeIcon(
                            Icons.Outlined.Add,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    },
                    headline = { Text("新建目录") },
                    supporting = { Text("保存图片时可以选，保存弹窗里也能新建") },
                )
            }
        }
        SettingsNote("不选目录时保存到 Pictures/QAlbum。")
    }

    if (adding) {
        NewFolderDialog(
            onDismiss = { adding = false },
            onConfirm = { name ->
                adding = false
                viewModel.addExportSubdir(name)
            },
        )
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val valid = SettingsRepository.sanitizeExportSubdir(name) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        title = { Text("新建目录") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("目录名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = valid) { Text("新建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
