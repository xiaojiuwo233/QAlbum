package com.xjw.qalbum.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.sourceLabel
import com.xjw.qalbum.ui.imageloading.ImageEntityModel

/**
 * 底部版本条：同一张图的原图 / 预览图 / 缩略图（类似相册的图集），点选切换显示，标出各自大小与合计。
 */
@Composable
internal fun VariantStrip(variants: List<ImageEntity>, currentId: Long, onSelect: (Long) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = ViewerChromeColor,
        contentColor = Color.White,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .widthIn(max = 520.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp)) {
            Text(
                "同一张图的 ${variants.size} 个版本 · 合计 ${formatBytes(variants.sumOf { it.size })}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                variants.forEach { variant ->
                    VariantThumb(variant, selected = variant.id == currentId, onClick = { onSelect(variant.id) })
                }
            }
        }
    }
}

@Composable
private fun VariantThumb(image: ImageEntity, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val border by animateColorAsState(
        if (selected) Color.White else Color.Transparent,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "variantBorder",
    )
    val scale by animateFloatAsState(if (selected) 1f else 0.9f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "variantScale")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(ImageEntityModel(image)).crossfade(true).build(),
            contentDescription = variantLabel(image),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(2.dp, border, RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(4.dp))
        Text(variantLabel(image), style = MaterialTheme.typography.labelSmall, color = Color.White)
        Text(formatBytes(image.size), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.66f))
    }
}

/** 版本名称：原图 / 预览图 / 缩略图 / 高清缩略图 */
internal fun variantLabel(image: ImageEntity): String = when (image.sourceType) {
    "chatraw" -> "原图"
    "chatimg" -> "预览图"
    "chatthumb" -> if (image.filename.endsWith("_hd")) "高清缩略图" else "缩略图"
    else -> sourceLabel(image.sourceType)
}
