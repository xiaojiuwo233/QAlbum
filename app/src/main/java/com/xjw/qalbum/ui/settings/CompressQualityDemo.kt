package com.xjw.qalbum.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xjw.qalbum.service.cleanup.CompressDemo
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.compress.CompareSlider
import kotlin.math.roundToInt

/** 演示一开始放大到的位置：纸箱上的小字和旁边的青桔，压缩痕迹最明显 */
private val DEMO_FOCUS = Offset(0.17f, 0.25f)
private const val DEMO_ZOOM = 3f

/**
 * 压缩质量演示：内置照片按所选质量实际压缩后，与原图左右对比（拖动把手），下方是大小变化；
 * 「放大看」全屏对比，可以双指缩放。
 */
@Composable
internal fun CompressQualityDemo(result: CompressDemo.Result?) {
    var full by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (result == null) {
                LoadingIndicator(Modifier.align(Alignment.Center))
            } else {
                CompareSlider(
                    before = result.original,
                    after = result.compressed,
                    decodeSize = IntSize(result.width, result.height),
                    beforeLabel = "原图",
                    afterLabel = "${result.quality}%",
                    zoomable = false,
                    initialScale = DEMO_ZOOM,
                    focus = DEMO_FOCUS,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (result != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "示例照片 ${sizeChange(result)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { full = true }) { Text("放大看") }
            }
        }
        Text(
            "拖动把手对比 · 照片 Mx. Granger（CC0）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (full && result != null) {
        Dialog(
            onDismissRequest = { full = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
                ) {
                    IconButton(onClick = { full = false }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                    }
                    Text(
                        "质量 ${result.quality}%：${sizeChange(result)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                }
                CompareSlider(
                    before = result.original,
                    after = result.compressed,
                    decodeSize = IntSize(result.width, result.height),
                    beforeLabel = "原图",
                    afterLabel = "${result.quality}%",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Text(
                    "拖动把手对比，双指或双击放大",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(16.dp),
                )
            }
        }
    }
}

private fun sizeChange(result: CompressDemo.Result): String {
    val before = result.original.size.toLong()
    val after = result.compressed.size.toLong()
    val percent = if (before > 0) ((before - after) * 100.0 / before).roundToInt() else 0
    return "${formatBytes(before)} → ${formatBytes(after)}（-$percent%）"
}
