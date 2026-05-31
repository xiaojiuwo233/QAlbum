package com.xjw.qalbum.ui.detail

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ImageDetailScreen(
    imageId: Long,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    favoritesOnly: Boolean = false,
    viewModel: ImageDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(imageId, favoritesOnly) { viewModel.load(imageId, favoritesOnly) }
    val state by viewModel.state.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(contentPadding),
    ) {
        when (val s = state) {
            ImageDetailState.Loading -> CenteredLoading()
            ImageDetailState.NotFound -> CenteredText("图片不存在或已被清理")
            is ImageDetailState.Ready -> ImmersivePhotoViewer(
                state = s,
                onBack = onBack,
                onPageSelected = viewModel::selectIndex,
                onToggleFavorite = viewModel::toggleFavorite,
                onExport = viewModel::exportToGallery,
                onOpenExternal = {
                    viewModel.prepareOpenExternal { target ->
                        target ?: return@prepareOpenExternal
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(target.uri, target.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "打开图片"))
                        }.onFailure {
                            Toast.makeText(context, "没有可用的第三方应用", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onMoveToTrash = { viewModel.moveCurrentToTrash { ok -> if (ok) onBack() } },
                onDeletePermanently = { viewModel.deleteCurrentPermanently { ok -> if (ok) onBack() } },
            )
        }
    }
}

@Composable
private fun ImmersivePhotoViewer(
    state: ImageDetailState.Ready,
    onBack: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onExport: () -> Unit,
    onOpenExternal: () -> Unit,
    onMoveToTrash: () -> Unit,
    onDeletePermanently: () -> Unit,
) {
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var detailsVisible by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var dismissOffsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }
    var draggingClose by remember { mutableStateOf(false) }
    var dragDirectionDecided by remember { mutableStateOf(false) }
    val springBack = remember { Animatable(0f) }
    val pagerState = rememberPagerState(
        initialPage = state.currentIndex,
        pageCount = { state.timeline.size },
    )

    LaunchedEffect(state.currentIndex) {
        if (pagerState.currentPage != state.currentIndex) {
            pagerState.scrollToPage(state.currentIndex)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page -> onPageSelected(page) }
    }

    val effectiveOffsetY = if (draggingClose) dismissOffsetY else springBack.value
    val closeProgress = (effectiveOffsetY / 400f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - closeProgress * 0.7f))
            .pointerInput(Unit) {
                detectTapGestures {
                    chromeVisible = !chromeVisible
                    if (!chromeVisible) detailsVisible = false
                }
            }
            .pointerInput(detailsVisible, currentZoom) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragDirectionDecided = false
                        draggingClose = false
                        dismissOffsetY = 0f
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        if (!draggingClose) {
                            dragDirectionDecided = false
                            return@detectVerticalDragGestures
                        }
                        val finalOffset = dismissOffsetY
                        val velocity = try {
                            velocityTracker.calculateVelocity().y
                        } catch (_: Exception) {
                            0f
                        }
                        draggingClose = false
                        dragDirectionDecided = false
                        if (finalOffset > 120f || velocity > 600f) {
                            onBack()
                        } else {
                            scope.launch {
                                springBack.snapTo(finalOffset)
                                springBack.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                            chromeVisible = true
                        }
                    },
                    onDragCancel = {
                        val finalOffset = dismissOffsetY
                        draggingClose = false
                        dragDirectionDecided = false
                        scope.launch {
                            springBack.snapTo(finalOffset)
                            springBack.animateTo(
                                0f,
                                spring(stiffness = Spring.StiffnessMedium),
                            )
                        }
                        chromeVisible = true
                    },
                ) { change, dragAmount ->
                    if (!dragDirectionDecided) {
                        dragDirectionDecided = true
                        val canDismiss = !detailsVisible && currentZoom <= 1.05f
                        draggingClose = canDismiss && dragAmount > 0f
                    }
                    if (draggingClose && dragAmount > 0f) {
                        change.consume()
                        chromeVisible = false
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        dismissOffsetY = (dismissOffsetY + dragAmount).coerceAtLeast(0f)
                    } else if (dragAmount < -8f && !detailsVisible && !draggingClose) {
                        change.consume()
                        chromeVisible = true
                        detailsVisible = true
                    } else if (dragAmount > 8f && detailsVisible) {
                        change.consume()
                        detailsVisible = false
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = effectiveOffsetY
                    alpha = 1f - closeProgress * 0.2f
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { index -> state.timeline[index].id },
            ) { page ->
                val image = if (page == state.currentIndex) state.image else state.timeline[page]
                FullscreenPhoto(
                    image = image,
                    onZoomChanged = { zoom ->
                        if (page == state.currentIndex) currentZoom = zoom
                    },
                )
            }

            AnimatedVisibility(
                visible = chromeVisible && effectiveOffsetY < 1f,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopChrome(
                    image = state.image,
                    index = state.currentIndex,
                    total = state.timeline.size,
                    onBack = onBack,
                    onOpenExternal = onOpenExternal,
                )
            }

            AnimatedVisibility(
                visible = chromeVisible && !detailsVisible && effectiveOffsetY < 1f,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BottomActions(
                    image = state.image,
                    onToggleFavorite = onToggleFavorite,
                    onInfo = {
                        chromeVisible = true
                        detailsVisible = true
                    },
                    onExport = onExport,
                    onDelete = { confirmDelete = true },
                )
            }
        }

        AnimatedVisibility(
            visible = detailsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            DetailsSheet(
                image = state.image,
                ocr = state.ocr,
                onClose = { detailsVisible = false },
            )
        }

        if (confirmDelete) {
            DeleteConfirmDialog(
                onDismiss = { confirmDelete = false },
                onTrash = {
                    confirmDelete = false
                    onMoveToTrash()
                },
                onPermanent = {
                    confirmDelete = false
                    onDeletePermanently()
                },
            )
        }
    }
}

@Composable
private fun FullscreenPhoto(
    image: ImageEntity,
    onZoomChanged: (Float) -> Unit,
) {
    val context = LocalContext.current
    var scale by remember(image.id) { mutableFloatStateOf(1f) }
    var offset by remember(image.id) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale) { onZoomChanged(scale) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(ImageEntityModel(image))
                .crossfade(true)
                .build(),
            contentDescription = image.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(image.id) {
                    awaitEachGesture {
                        var lastDistance = 0f
                        var lastCentroid: Offset? = null
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                val first = pressed[0].position
                                val second = pressed[1].position
                                val distance = (first - second).getDistance()
                                val centroid = Offset(
                                    x = pressed.sumOf { it.position.x.toDouble() }.toFloat() / pressed.size,
                                    y = pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressed.size,
                                )
                                if (lastDistance > 0f) {
                                    val nextScale = (scale * (distance / lastDistance)).coerceIn(1f, 5f)
                                    scale = nextScale
                                    offset = if (nextScale <= 1.01f) {
                                        Offset.Zero
                                    } else {
                                        offset + (centroid - (lastCentroid ?: centroid))
                                    }
                                }
                                lastDistance = distance
                                lastCentroid = centroid
                                pressed.forEach { it.consume() }
                            } else if (scale > 1.01f) {
                                val change = pressed.first()
                                offset += change.positionChange()
                                change.consume()
                                lastDistance = 0f
                                lastCentroid = change.position
                            } else {
                                lastDistance = 0f
                                lastCentroid = null
                            }
                        }
                        if (scale <= 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

@Composable
private fun TopChrome(
    image: ImageEntity,
    index: Int,
    total: Int,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                ),
            )
            .statusBarsPadding()
            .padding(start = 4.dp, top = 6.dp, end = 16.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                formatTitleDate(image.mtime),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                "${index + 1} / $total",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
        CircleIconButton(onClick = onOpenExternal) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "用第三方应用打开", tint = Color.White)
        }
    }
}

@Composable
private fun BottomActions(
    image: ImageEntity,
    onToggleFavorite: () -> Unit,
    onInfo: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                ),
            )
            .padding(start = 16.dp, top = 42.dp, end = 16.dp, bottom = 8.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerAction(
            icon = if (image.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            label = if (image.isFavorite) "取消收藏" else "收藏",
            onClick = onToggleFavorite,
        )
        ViewerAction(Icons.Outlined.Info, "详情", onInfo)
        ViewerAction(Icons.Outlined.IosShare, "导出", onExport)
        ViewerAction(Icons.Outlined.Delete, "删除", onDelete)
    }
}

@Composable
private fun DeleteConfirmDialog(
    onDismiss: () -> Unit,
    onTrash: () -> Unit,
    onPermanent: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这张图片？") },
        text = { Text("可以先移动到回收站，或直接永久删除。永久删除后不可恢复。") },
        confirmButton = {
            TextButton(onClick = onTrash) { Text("移动到回收站") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onPermanent) { Text("永久删除") }
            }
        },
    )
}

@Composable
private fun DetailsSheet(
    image: ImageEntity,
    ocr: OcrTextEntity?,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.62f)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 42.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    Text(
                        formatTitleDate(image.mtime),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        image.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item { MetadataBlock(image) }
            ocr?.takeIf { it.text.isNotBlank() }?.let { text ->
                item { OcrBlock(text) }
            }
        }
    }
}

@Composable
private fun MetadataBlock(image: ImageEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                label = { Text(image.sourceType) },
                colors = AssistChipDefaults.assistChipColors(),
            )
            AssistChip(
                onClick = {},
                label = { Text(image.mime) },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        InfoRow("大小", formatBytes(image.size))
        InfoRow("尺寸", if (image.width > 0) "${image.width} x ${image.height}" else "未读取")
        InfoRow("修改时间", formatTime(image.mtime))
        InfoRow("首次扫描", formatTime(image.firstSeen))
        InfoRow("路径", image.path)
        image.duplicateGroupId?.let {
            InfoRow("重复组", "#$it")
        }
    }
}

@Composable
private fun OcrBlock(ocr: OcrTextEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "OCR 识别文本",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "置信度 ${(ocr.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val flags = buildList {
            if (ocr.hasIdNumber) add("证件号")
            if (ocr.hasOrderNo) add("订单号")
            if (ocr.hasAmount) add("金额")
            if (ocr.hasPhone) add("手机号")
            if (ocr.hasAddress) add("地址")
        }
        if (flags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                flags.forEach { flag -> AssistChip(onClick = {}, label = { Text(flag) }) }
            }
        }
        Text(
            ocr.text.trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 12,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.32f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.68f),
        )
    }
}

@Composable
private fun ViewerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircleIconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.88f),
        )
    }
}

@Composable
private fun CircleIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
    ) {
        content()
    }
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}

private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
private val titleFormatter = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)

private fun formatTime(ts: Long): String = timeFormatter.format(Date(ts))

private fun formatTitleDate(ts: Long): String = titleFormatter.format(Date(ts))

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.2f %s", value, units[exp])
}
