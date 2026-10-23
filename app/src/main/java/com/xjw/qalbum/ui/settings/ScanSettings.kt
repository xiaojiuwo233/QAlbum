package com.xjw.qalbum.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.WavyProgress
import com.xjw.qalbum.ui.dashboard.statusText
import com.xjw.qalbum.ui.dashboard.toProgressFraction
import org.koin.androidx.compose.koinViewModel

/** 扫描：QQ 图片目录（点开修改）、增量 / 全量扫描（扫描中显示进度）、深度校验 */
@Composable
fun ScanSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val path by viewModel.chatpicPath.collectAsState()
    val running by viewModel.scanRunning.collectAsState()
    val progress by viewModel.scanProgress.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }

    SettingsPageScaffold(SettingsCategory.SCAN, onBack, sharedScope, animatedScope) { notify ->
        SegmentedGroup(header = "图片目录") {
            item("path") { position ->
                SegmentedListItem(
                    position = position,
                    onClick = { editing = true },
                    leading = { ShapeIcon(Icons.Outlined.FolderOpen) },
                    headline = { Text(if (path == SettingsRepository.DEFAULT_CHATPIC_PATH) "QQ 默认目录" else "自定义目录") },
                    supporting = { Text(path) },
                    trailing = { Icon(Icons.Outlined.Edit, contentDescription = "修改") },
                )
            }
        }
        SettingsNote("TIM、国际版 QQ 的目录不一样，点上面修改。")

        SegmentedGroup(header = "扫描") {
            item("scan") { position ->
                SegmentedCard(position, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ShapeIcon(Icons.Outlined.TravelExplore)
                        Text(
                            if (running || progress != null) statusText(running, progress)
                            else "增量扫描只看有变化的目录，几秒完成；全量扫描核对全部文件，能发现删掉的。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    progress.toProgressFraction()?.let { fraction -> WavyProgress(fraction.takeIf { it >= 0f }) }
                    AnimatedContent(targetState = running, label = "scanActions") { isRunning ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (isRunning) {
                                OutlinedButton(onClick = viewModel::cancelScan, shapes = ButtonDefaults.shapes()) { Text("取消扫描") }
                            } else {
                                FilledTonalButton(onClick = viewModel::startIncrementalScan, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                                    Text("增量扫描")
                                }
                                Button(onClick = viewModel::startFullScan, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                                    Text("全量扫描")
                                }
                            }
                        }
                    }
                }
            }
            item("deep") { position ->
                SegmentedListItem(
                    position = position,
                    enabled = !running,
                    onClick = { if (!running) viewModel.startDeepVerify() },
                    leading = { ShapeIcon(Icons.AutoMirrored.Outlined.FactCheck) },
                    headline = { Text("深度校验") },
                    supporting = { Text("重读全部文件头，改正认错的格式。较慢") },
                )
            }
        }

        if (editing) {
            PathDialog(
                current = path,
                onDismiss = { editing = false },
                onSave = { value ->
                    editing = false
                    notify(if (viewModel.setChatpicPath(value)) "已保存" else "目录不能为空")
                },
                onReset = {
                    editing = false
                    viewModel.setChatpicPath(SettingsRepository.DEFAULT_CHATPIC_PATH)
                    notify("已恢复默认目录")
                },
            )
        }
    }
}

/** 修改图片目录（扫描页与首次引导共用） */
@Composable
internal fun PathDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onReset: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
        title = { Text("图片目录") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("chatpic 目录") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("保存") } },
        dismissButton = {
            Row {
                if (current != SettingsRepository.DEFAULT_CHATPIC_PATH) TextButton(onClick = onReset) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
