package com.xjw.qalbum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.cleanup.CleanupViewModel
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import com.xjw.qalbum.ui.theme.QAlbumTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

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
                ThumbnailCacheScreen(onBack = ::finish)
            }
        }
    }
}

@Composable
private fun ThumbnailCacheScreen(onBack: () -> Unit, viewModel: CleanupViewModel = koinViewModel()) {
    val images = viewModel.thumbnailTrash.collectAsLazyPagingItems()
    val summary by viewModel.thumbnailTrashSummary.collectAsState()
    var preview by remember { mutableStateOf<ImageEntity?>(null) }
    val context = LocalContext.current
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                Column {
                    Text("QQ 预览缓存", style = MaterialTheme.typography.titleLarge)
                    Text("${summary.count} 项 · ${formatThumbnailBytes(summary.bytes)} · 已保护", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (images.itemCount == 0) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("暂无已索引的预览缓存")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(images.itemCount, key = { images.peek(it)?.id ?: "loading-$it" }) { index ->
                    val image = images[index]
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        image?.let {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(ImageEntityModel(it)).build(),
                                contentDescription = it.filename,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clickable { preview = it },
                            )
                        }
                    }
                }
            }
        }
    }
    preview?.let { image ->
        Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(ImageEntityModel(image)).build(),
                    contentDescription = image.filename,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(onClick = { preview = null }, modifier = Modifier.statusBarsPadding().align(Alignment.TopStart)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                }
            }
        }
    }
}

private fun formatThumbnailBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}
