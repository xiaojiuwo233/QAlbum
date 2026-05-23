package com.xjw.qalbum.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ImageDetailScreen(
    imageId: Long,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: ImageDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(imageId) { viewModel.load(imageId) }
    val state by viewModel.state.collectAsState()
    val toast by viewModel.toast.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图片详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding())
                .padding(contentPadding),
        ) {
            when (val s = state) {
                ImageDetailState.Loading -> CenteredLoading()
                ImageDetailState.NotFound -> CenteredText("图片不存在或已被清理")
                is ImageDetailState.Ready -> Content(
                    image = s.image,
                    variants = s.variants,
                    ocr = s.ocr,
                    onAddToVault = viewModel::addToVault,
                    onToggleProtection = viewModel::toggleProtection,
                    onExport = viewModel::exportToGallery,
                )
            }
        }
    }
}

@Composable
private fun Content(
    image: ImageEntity,
    variants: List<ImageEntity>,
    ocr: OcrTextEntity?,
    onAddToVault: () -> Unit,
    onToggleProtection: () -> Unit,
    onExport: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HeroImage(image) }
        item { ActionsRow(image, onAddToVault, onToggleProtection, onExport) }
        item { MetadataCard(image) }
        ocr?.takeIf { it.text.isNotBlank() }?.let { ocrText ->
            item { OcrCard(ocrText) }
        }
        if (variants.size > 1) {
            item { VariantsHeader(variants.size) }
            items(count = variants.size, key = { variants[it].id }) { idx ->
                VariantRow(variants[idx], isPrimary = variants[idx].id == image.id)
            }
        }
    }
}

@Composable
private fun ActionsRow(
    image: ImageEntity,
    onAddToVault: () -> Unit,
    onToggleProtection: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = onAddToVault, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("加入保险箱")
        }
        OutlinedButton(onClick = onToggleProtection, modifier = Modifier.weight(1f)) {
            Icon(
                if (image.isProtected) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(if (image.isProtected) "取消保护" else "保护")
        }
    }
    Spacer(Modifier.size(8.dp))
    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text("导出到系统相册")
    }
}

@Composable
private fun OcrCard(ocr: OcrTextEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OCR 识别文本", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "置信度 ${(ocr.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 敏感信息标签
            val flags = buildList {
                if (ocr.hasIdNumber) add("证件号")
                if (ocr.hasOrderNo) add("订单号")
                if (ocr.hasAmount) add("金额")
                if (ocr.hasPhone) add("手机号")
                if (ocr.hasAddress) add("地址")
            }
            if (flags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    flags.forEach { f ->
                        AssistChip(onClick = {}, label = { Text(f) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                ocr.text.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 12,
            )
        }
    }
}

@Composable
private fun HeroImage(image: ImageEntity) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (image.width > 0 && image.height > 0) image.width.toFloat() / image.height else 1f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(ImageEntityModel(image))
                .crossfade(true)
                .build(),
            contentDescription = image.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MetadataCard(image: ImageEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(image.filename, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                image.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            InfoRow("大小", formatBytes(image.size))
            InfoRow("尺寸", if (image.width > 0) "${image.width} × ${image.height}" else "未读取（Phase 2 解析）")
            InfoRow("修改时间", formatTime(image.mtime))
            InfoRow("首次扫描", formatTime(image.firstSeen))
            image.variantGroup?.let {
                InfoRow("变体组 ID", "#$it" + if (image.isVariantPrimary) "（主图）" else "")
            }
            image.duplicateGroupId?.let {
                InfoRow("重复组 ID", "#$it")
            }
        }
    }
}

@Composable
private fun VariantsHeader(count: Int) {
    Text(
        "同源变体（$count 个）",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun VariantRow(image: ImageEntity, isPrimary: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPrimary) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val context = LocalContext.current
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(ImageEntityModel(image)).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(image.sourceType, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${formatBytes(image.size)} · ${if (image.width > 0) "${image.width}×${image.height}" else "尺寸待解析"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isPrimary) {
                AssistChip(onClick = {}, label = { Text("主图") })
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

// ---- 工具 ----

private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
private fun formatTime(ts: Long): String = timeFormatter.format(Date(ts))

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.2f %s", value, units[exp])
}
