package com.xjw.qalbum.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.xjw.qalbum.data.db.DatabaseOpener
import com.xjw.qalbum.data.db.DatabaseUpgrade
import com.xjw.qalbum.ui.common.formatCountExact
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * 数据库升级进度：升级期间所有页面都在等数据，这里说明在做什么、做到哪了，不能关闭；
 * 很快就完成的升级不弹窗。失败时显示原因，可以重试。
 */
@Composable
fun DatabaseUpgradeDialog() {
    val state by DatabaseUpgrade.state.collectAsState()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(state == null) {
        if (state == null) {
            shown = false
        } else {
            delay(400)
            shown = true
        }
    }
    val current = state ?: return
    if (!shown && current !is DatabaseUpgrade.State.Failed) return
    val opener = koinInject<DatabaseOpener>()
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = {
            Icon(if (current is DatabaseUpgrade.State.Failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Storage, contentDescription = null)
        },
        title = { Text(if (current is DatabaseUpgrade.State.Failed) "数据库升级失败" else "正在升级数据库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (current) {
                    is DatabaseUpgrade.State.Running -> {
                        Text("新版本要整理图片索引，图片越多越久。切到后台也会继续，别关掉 QAlbum。")
                        if (current.total > 0) {
                            LinearWavyProgressIndicator(
                                progress = { (current.done.toFloat() / current.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            if (current.total > 0) "${current.step}：${formatCountExact(current.done.toInt())} / ${formatCountExact(current.total.toInt())}"
                            else current.step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is DatabaseUpgrade.State.Failed -> {
                        Text("索引没有改动，可以重试。一直失败请在「设置 → 日志」导出日志反馈。")
                        Text(current.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (current is DatabaseUpgrade.State.Failed) TextButton(onClick = opener::start) { Text("重试") }
        },
        dismissButton = {
            // 关掉后图库等页面读不到数据，但「设置 → 日志」还能打开导出日志
            if (current is DatabaseUpgrade.State.Failed) TextButton(onClick = DatabaseUpgrade::finish) { Text("关闭") }
        },
    )
}
