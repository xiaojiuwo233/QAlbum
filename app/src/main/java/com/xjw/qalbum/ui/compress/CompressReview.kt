package com.xjw.qalbum.ui.compress

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.service.cleanup.CompressionCoordinator
import com.xjw.qalbum.service.cleanup.CompressionCoordinator.Entry
import com.xjw.qalbum.service.cleanup.CompressionCoordinator.Session
import com.xjw.qalbum.service.cleanup.OriginalCompressor.Prepared
import com.xjw.qalbum.service.cleanup.OriginalCompressor.Preview
import com.xjw.qalbum.service.cleanup.rules.CleanupRunner
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatCountExact
import com.xjw.qalbum.ui.common.formatTitleDate
import com.xjw.qalbum.ui.components.ConnectedChoiceRow
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * 界面根部：压缩对比页，以及压缩 / 清理任务的结果提示（任何页面发起的都在这里显示，离开发起的页面也看得到）。
 */
@Composable
fun CompressionHost() {
    val coordinator = koinInject<CompressionCoordinator>()
    val runner = koinInject<CleanupRunner>()
    val session by coordinator.session.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(coordinator) { coordinator.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    LaunchedEffect(runner) { runner.results.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    session?.let { CompressReviewDialog(it, coordinator) }
}

/** 压缩对比：前后效果、省下的空间，可以换质量重压；「应用」才替换原图，「撤销」原图不动 */
@Composable
private fun CompressReviewDialog(session: Session, coordinator: CompressionCoordinator) {
    var opened by remember { mutableStateOf<Long?>(null) }
    Dialog(
        onDismissRequest = coordinator::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("压缩效果") },
                    subtitle = { Text(subtitle(session)) },
                    navigationIcon = {
                        IconButton(onClick = coordinator::dismiss, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Close, contentDescription = "撤销")
                        }
                    },
                )
            },
            bottomBar = { ReviewActions(session, onUndo = coordinator::dismiss, onApply = coordinator::apply) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                QualitySelector(
                    selected = session.quality,
                    onSelect = coordinator::setQuality,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                when (session) {
                    is Session.Preparing -> PreparingContent(session)
                    is Session.Review -> ReviewContent(session, onToggle = coordinator::toggle, onOpen = { opened = it })
                }
            }
        }
        val entry = (session as? Session.Review)?.entries?.firstOrNull { it.preview.image.id == opened }
        val prepared = entry?.prepared
        if (entry != null && prepared != null) {
            FullCompare(entry, prepared, onToggle = { coordinator.toggle(prepared.image.id) }, onClose = { opened = null })
        }
    }
}

/** 压缩质量：越低越模糊、省得越多（对比页与设置页共用） */
@Composable
internal fun QualitySelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ConnectedChoiceRow(
            options = SettingsRepository.COMPRESS_QUALITY_OPTIONS,
            selected = selected.takeIf { it in SettingsRepository.COMPRESS_QUALITY_OPTIONS },
            label = { "$it%" },
            onSelect = onSelect,
        )
        Text(
            "越低越省空间，也越模糊",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun subtitle(session: Session): String = when (session) {
    is Session.Preparing -> "正在压缩 ${session.done} / ${session.total} 张 · 质量 ${session.quality}%"
    is Session.Review -> when {
        session.sampled -> "先压了 ${session.entries.count { it.prepared != null }} 张样张 · 共 ${formatCountExact(session.eligible)} 张"
        else -> "${session.entries.count { it.prepared != null }} 张 · 质量 ${session.quality}%"
    }
}

@Composable
private fun PreparingContent(session: Session.Preparing) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearWavyProgressIndicator(
            progress = { if (session.total > 0) session.done.toFloat() / session.total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text("正在压缩 ${session.done} / ${session.total} 张", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "原图不动，看过效果再决定",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReviewContent(review: Session.Review, onToggle: (Long) -> Unit, onOpen: (Long) -> Unit) {
    val ready = review.entries.mapNotNull { it.prepared }
    val single = review.entries.size == 1 && ready.size == 1
    if (single) {
        val prepared = ready.single()
        Column(Modifier.fillMaxSize()) {
            CompareSlider(
                before = ImageEntityModel(prepared.image),
                after = prepared.file,
                decodeSize = IntSize(prepared.width, prepared.height),
                afterLabel = "压缩后 ${prepared.quality}%",
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black),
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(savingText(prepared), style = MaterialTheme.typography.titleMedium)
                Text(sizeLine(prepared), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "拖动把手对比，双指或双击放大",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") { Summary(review) }
        items(review.entries, key = { it.preview.image.id }) { entry ->
            EntryRow(entry, onToggle = { onToggle(entry.preview.image.id) }, onOpen = { onOpen(entry.preview.image.id) })
        }
    }
}

@Composable
private fun Summary(review: Session.Review) {
    val chosen = review.chosen
    val before = chosen.sumOf { it.image.size }
    val after = chosen.sumOf { it.size }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (chosen.isEmpty()) "没有勾选要替换的图片"
                else "勾选的 ${chosen.size} 张：${formatBytes(before)} → ${formatBytes(after)}，省 ${formatBytes(before - after)}（-${percent(before - after, before)}%）",
                style = MaterialTheme.typography.titleSmall,
            )
            if (review.sampled) {
                val ready = review.entries.mapNotNull { it.prepared }
                val ratio = ready.sumOf { it.savedBytes }.toDouble() / ready.sumOf { it.image.size }.coerceAtLeast(1)
                Text(
                    "按样张推算，全部 ${formatCountExact(review.eligible)} 张约省 ${formatBytes((review.eligibleBytes * ratio).toLong())}；" +
                        "应用后其余 ${formatCountExact(review.remaining.size)} 张在后台压缩。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (review.skipped > 0) {
                Text(
                    "另有 ${formatCountExact(review.skipped)} 张不能压缩（动图、已压缩过等），跳过。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "点开可对比前后；取消勾选的保持原样。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun EntryRow(entry: Entry, onToggle: () -> Unit, onOpen: () -> Unit) {
    val prepared = entry.prepared
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(enabled = prepared != null, onClick = onOpen)
                .padding(10.dp),
        ) {
            AsyncImage(
                model = prepared?.file ?: ImageEntityModel(entry.preview.image, tile = true),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (prepared != null) {
                    Text(savingText(prepared), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sizeLine(prepared), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val reason = (entry.preview as? Preview.Unavailable)?.reason.orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("不能压缩：$reason", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    }
                }
                Text(
                    formatTitleDate(entry.preview.image.mtime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (prepared != null) Checkbox(checked = entry.checked, onCheckedChange = { onToggle() })
        }
    }
}

/** 一张的大图对比（放大看细节），可以在这里决定要不要应用；顶栏、图片、底栏上下排开，标签不被遮住 */
@Composable
private fun FullCompare(entry: Entry, prepared: Prepared, onToggle: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(savingText(prepared), style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text(sizeLine(prepared), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            }
        }
        CompareSlider(
            before = ImageEntityModel(prepared.image),
            after = prepared.file,
            decodeSize = IntSize(prepared.width, prepared.height),
            afterLabel = "压缩后 ${prepared.quality}%",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onToggle)
                    .padding(start = 4.dp, end = 16.dp),
            ) {
                Checkbox(
                    checked = entry.checked,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(uncheckedColor = Color.White),
                )
                Text(if (entry.checked) "应用这张" else "这张保持原样", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ReviewActions(session: Session, onUndo: () -> Unit, onApply: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        OutlinedButton(onClick = onUndo, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("撤销") }
        val review = session as? Session.Review
        val label = when {
            review == null -> "正在压缩…"
            review.sampled -> "应用到全部 ${formatCountExact(review.eligible)} 张"
            else -> "应用 ${review.chosen.size} 张"
        }
        Button(
            onClick = onApply,
            enabled = review != null && (review.chosen.isNotEmpty() || review.sampled),
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f),
        ) { Text(label, maxLines = 1) }
    }
}

private fun percent(part: Long, whole: Long): Int = if (whole <= 0) 0 else (part * 100.0 / whole).roundToInt()

private fun savingText(prepared: Prepared): String =
    "-${percent(prepared.savedBytes, prepared.image.size)}% · 省 ${formatBytes(prepared.savedBytes)}"

private fun sizeLine(prepared: Prepared): String {
    val image = prepared.image
    val resized = image.width > 0 && (image.width != prepared.width || image.height != prepared.height)
    val dims = if (resized) "${image.width}×${image.height} → ${prepared.width}×${prepared.height}" else "${prepared.width}×${prepared.height}"
    val format = if (prepared.fromPng) " · PNG → JPEG" else ""
    return "${formatBytes(image.size)} → ${formatBytes(prepared.size)} · $dims$format"
}
