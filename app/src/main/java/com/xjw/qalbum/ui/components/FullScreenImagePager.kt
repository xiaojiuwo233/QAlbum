package com.xjw.qalbum.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.ui.detail.PhotoDetailsSheet
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏图片预览：左右滑动切换上一张/下一张、双指/双击缩放（见 [ZoomableImage]）；
 * 顶部「关闭 + 时间 + 详情 + 保存 + 分享」；底部操作栏由调用方通过 [bottomBar] 插槽提供
 * （审核页放「收藏/勾选/清理/保留」，标签页放「收藏/取消标签」）。
 *
 * 「详情」复用图库的 [PhotoDetailsSheet]（元数据 + OCR），从底部滑入覆盖底部操作栏；
 * 此时铺一层全屏遮罩——**点详情卡以外任意处即可关闭**。
 *
 * [images] 是预览时的快照列表（调用方负责取 active/已加载的有限集合，不全量装载）。
 */
@Composable
internal fun FullScreenImagePager(
    images: List<ImageEntity>,
    startIndex: Int,
    onClose: () -> Unit,
    onShare: (Long) -> Unit,
    exportSubdirs: List<String>,
    onCreateSubdir: (String) -> Unit,
    onSave: (id: Long, subdir: String?, alsoTrash: Boolean) -> Unit,
    onLoadOcr: suspend (Long) -> OcrTextEntity?,
    bottomBar: @Composable (current: ImageEntity) -> Unit,
) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { images.size }
    var detailsVisible by remember { mutableStateOf(false) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomReset by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(enabled = detailsVisible || zoom > 1f) {
            if (detailsVisible) detailsVisible = false else zoomReset++
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { images[it].id }) { page ->
                ZoomableImage(
                    model = ImageEntityModel(images[page]),
                    contentDescription = images[page].filename,
                    modifier = Modifier.fillMaxSize(),
                    stateKey = images[page].id,
                    isActive = page == pagerState.currentPage,
                    resetSignal = zoomReset,
                    onZoomChanged = { if (page == pagerState.currentPage) zoom = it },
                )
            }

            val current = images.getOrNull(pagerState.currentPage) ?: images.first()

            // 顶部：关闭 + 时间 + 详情 + 保存 + 分享
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(start = 4.dp, top = 6.dp, end = 4.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        formatImageTime(current.mtime),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(imageMeta(current), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.72f))
                }
                IconButton(onClick = { detailsVisible = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "详情", tint = Color.White)
                }
                IconButton(onClick = { showSaveSheet = true }) {
                    Icon(Icons.Outlined.Download, contentDescription = "保存", tint = Color.White)
                }
                IconButton(onClick = { onShare(current.id) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "分享", tint = Color.White)
                }
            }

            // 底部操作栏（样式容器统一，内容由调用方插槽决定）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                    .navigationBarsPadding()
                    .padding(top = 28.dp, bottom = 12.dp),
            ) {
                bottomBar(current)
            }

            // 「查看详情」：复用图库详情卡（元数据 + OCR），从底部滑入
            val ocr by produceState<OcrTextEntity?>(null, current.id, detailsVisible) {
                value = null
                value = if (detailsVisible) onLoadOcr(current.id) else null
            }
            // 详情卡外的全屏遮罩：点详情卡以外任意处关闭（绘制顺序在详情卡之下、其余之上）
            if (detailsVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { detailsVisible = false },
                )
            }
            AnimatedVisibility(
                visible = detailsVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                PhotoDetailsSheet(
                    image = current,
                    ocr = ocr,
                    onClose = { detailsVisible = false },
                )
            }

            if (showSaveSheet) {
                SaveToAlbumSheet(
                    imageCount = 1,
                    subdirs = exportSubdirs,
                    onCreateSubdir = onCreateSubdir,
                    onDismiss = { showSaveSheet = false },
                    onConfirm = { subdir, alsoTrash ->
                        showSaveSheet = false
                        onSave(current.id, subdir, alsoTrash)
                    },
                )
            }
        }
    }
}

/** 预览底部操作项（图标 + 文案的竖排按钮）：审核页与标签页底栏共用。 */
@Composable
internal fun PreviewAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

// ---- 本地小工具（与 ui/cleanup 同口径；按本仓惯例就近复制，避免反向依赖 cleanup 包）----

private val previewTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

/** 图片拍摄/修改时间（mtime 兼容秒与毫秒）：预览标题用 */
private fun formatImageTime(mtime: Long): String {
    val ms = if (mtime < 1_000_000_000_000L) mtime * 1000 else mtime
    return previewTimeFormatter.format(Date(ms))
}

private fun imageMeta(image: ImageEntity): String {
    val size = formatBytes(image.size)
    val dimensions = if (image.width > 0 && image.height > 0) "${image.width}×${image.height}" else "未知尺寸"
    val clarity = image.blurScore?.let { " · 清晰度 ${String.format("%.0f", it)}" } ?: ""
    return "$dimensions · $size$clarity"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.1f %s", value, units[exp])
}
