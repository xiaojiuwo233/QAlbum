package com.xjw.qalbum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.cleanup.CleanupViewModel
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import com.xjw.qalbum.ui.theme.QAlbumTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel

class ThumbnailTrashActivity : ComponentActivity() {

    private val settings: SettingsRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
        )
        super.onCreate(savedInstanceState)

        setContent {
            val dynamicColor by settings.dynamicColorEnabled.collectAsState(initial = true)
            QAlbumTheme(dynamicColor = dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ThumbnailTrashScreen(onBack = ::finish)
                }
            }
        }
    }
}

@Composable
private fun ThumbnailTrashScreen(
    onBack: () -> Unit,
    viewModel: CleanupViewModel = koinViewModel(),
) {
    val items by viewModel.thumbnailTrash.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    val totalBytes = items.sumOf { it.size }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val clearSelection = { selectedIds = emptySet() }
    val toggle: (Long) -> Unit = { id ->
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    Scaffold(
        topBar = {
            if (selectedIds.isEmpty()) {
                ThumbnailTopBar(
                    count = items.size,
                    bytes = totalBytes,
                    busy = busy,
                    onBack = onBack,
                    onPurgeAll = { viewModel.purgeAllThumbnails() },
                )
            } else {
                ThumbnailSelectionTopBar(
                    count = selectedIds.size,
                    busy = busy,
                    onClose = clearSelection,
                    onDelete = {
                        selectedIds.forEach { viewModel.purgeThumbnail(it) }
                        clearSelection()
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding.calculateTopPadding()),
        ) {
            if (items.isEmpty()) {
                ThumbnailEmptyState()
            } else {
                ThumbnailTrashGrid(
                    items = items,
                    selectedIds = selectedIds,
                    bottomInset = bottomInset,
                    onToggle = toggle,
                )
            }
        }
    }
}

@Composable
private fun ThumbnailTopBar(
    count: Int,
    bytes: Long,
    busy: Boolean,
    onBack: () -> Unit,
    onPurgeAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, top = 10.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "缩略图",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "$count 项 · ${formatBytes(bytes)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onPurgeAll,
            enabled = count > 0 && !busy,
        ) {
            Text("一键清理")
        }
    }
}

@Composable
private fun ThumbnailSelectionTopBar(
    count: Int,
    busy: Boolean,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "取消选择")
        }
        Text(
            "已选择 $count 项",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete, enabled = !busy) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除所选")
        }
    }
}

@Composable
private fun ThumbnailTrashGrid(
    items: List<ImageEntity>,
    selectedIds: Set<Long>,
    bottomInset: Dp,
    onToggle: (Long) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 1.5.dp,
            end = 1.5.dp,
            top = 2.dp,
            bottom = bottomInset + 12.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        items(items, key = { it.id }) { image ->
            ThumbnailTile(
                image = image,
                selected = image.id in selectedIds,
                selectionMode = selectedIds.isNotEmpty(),
                onClick = { onToggle(image.id) },
            )
        }
    }
}

@Composable
private fun ThumbnailTile(
    image: ImageEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val scale by animateFloatAsState(
        targetValue = if (selected) 0.88f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "thumbSelectScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(if (selected) 8.dp else 0.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(ImageEntityModel(image))
                .crossfade(true)
                .build(),
            contentDescription = image.filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selectionMode || selected) {
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.36f)),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ThumbnailEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "没有待清理缩略图",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes.toDouble() / Math.pow(1024.0, exp.toDouble())
    return String.format("%.1f %s", value, units[exp])
}
