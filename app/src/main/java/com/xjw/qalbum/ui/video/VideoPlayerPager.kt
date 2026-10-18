package com.xjw.qalbum.ui.video

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.AsyncImage
import com.xjw.qalbum.data.db.entity.VideoEntity
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.common.formatDateTime
import com.xjw.qalbum.ui.common.formatTitleDate
import com.xjw.qalbum.ui.components.ConfirmDialog
import com.xjw.qalbum.ui.components.PreviewAction
import com.xjw.qalbum.ui.components.SaveToAlbumSheet
import com.xjw.qalbum.ui.components.ViewerActionBar
import com.xjw.qalbum.ui.components.ViewerChromeColor
import com.xjw.qalbum.ui.components.ViewerIconButton
import kotlinx.coroutines.delay

/**
 * 全屏看视频：左右滑动切换，当前页自动播放（MediaPlayer 读特权进程传来的 fd，画面画在 TextureView 上），
 * 单击显隐浮层、双击暂停 / 继续；底部进度条可拖动。顶部「关闭 + 时间 + 详情」，底部「保存 / 删除」。
 * [videos] 是打开时的快照，删掉一个后调用方传入新列表即可。
 */
@Composable
internal fun VideoPlayerPager(
    videos: List<VideoEntity>,
    startIndex: Int,
    onClose: () -> Unit,
    open: suspend (VideoEntity) -> ParcelFileDescriptor?,
    posterOf: (VideoEntity) -> Any,
    exportSubdirs: List<String>,
    onCreateSubdir: (String) -> Unit,
    onSave: (VideoEntity, String?) -> Unit,
    onDelete: (VideoEntity) -> Unit,
) {
    if (videos.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, videos.lastIndex)) { videos.size }
    var chromeVisible by remember { mutableStateOf(true) }
    var detailsVisible by remember { mutableStateOf(false) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<VideoEntity?>(null) }
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(enabled = detailsVisible) { detailsVisible = false }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                key = { videos[it].id },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val video = videos[page]
                VideoPage(
                    video = video,
                    // 停稳在这一页才开始播；拖动途中当前页照常播放
                    active = page == pagerState.settledPage,
                    chromeVisible = chromeVisible,
                    poster = posterOf(video),
                    open = open,
                    onTap = {
                        chromeVisible = !chromeVisible
                        if (!chromeVisible) detailsVisible = false
                    },
                )
            }

            val page = pagerState.currentPage.coerceIn(0, videos.lastIndex)
            val current = videos[page]

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(fade),
                exit = fadeOut(fade),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(start = 4.dp, top = 6.dp, end = 4.dp, bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerIconButton(Icons.Outlined.Close, "关闭", onClose)
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            formatTitleDate(current.mtime),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull("${page + 1}/${videos.size}", formatBytes(current.size), formatDuration(current.durationMs)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ViewerIconButton(Icons.Outlined.Info, "详情") { detailsVisible = !detailsVisible }
                }
            }

            AnimatedVisibility(
                visible = chromeVisible && !detailsVisible,
                enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 2 } + fadeIn(fade),
                exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 2 } + fadeOut(fade),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ViewerActionBar {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PreviewAction(icon = Icons.Outlined.Download, label = "保存") { showSaveSheet = true }
                        PreviewAction(icon = Icons.Outlined.DeleteForever, label = "删除") { pendingDelete = current }
                    }
                }
            }

            if (detailsVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { detailsVisible = false },
                )
            }
            AnimatedVisibility(
                visible = detailsVisible,
                enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(fade),
                exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(fade),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                VideoDetailsCard(current)
            }

            if (showSaveSheet) {
                SaveToAlbumSheet(
                    imageCount = 1,
                    subdirs = exportSubdirs,
                    onCreateSubdir = onCreateSubdir,
                    onDismiss = { showSaveSheet = false },
                    onConfirm = { subdir, _ ->
                        showSaveSheet = false
                        onSave(current, subdir)
                    },
                    allowTrash = false,
                )
            }
            pendingDelete?.let { target ->
                ConfirmDialog(
                    icon = Icons.Outlined.DeleteForever,
                    title = "删除这个视频？",
                    text = "${formatBytes(target.size)}，删除后无法恢复。",
                    confirmLabel = "删除",
                    destructive = true,
                    onDismiss = { pendingDelete = null },
                    onConfirm = {
                        pendingDelete = null
                        onDelete(target)
                    },
                )
            }
        }
    }
}

/** 一页视频：当前页创建播放器并自动播放，其余页只显示封面 */
@Composable
private fun VideoPage(
    video: VideoEntity,
    active: Boolean,
    chromeVisible: Boolean,
    poster: Any,
    open: suspend (VideoEntity) -> ParcelFileDescriptor?,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    val state = remember(video.id) { PlayerState(context, video) }
    if (active) {
        DisposableEffect(state) {
            state.create()
            onDispose { state.release() }
        }
        LaunchedEffect(state) { state.load { open(video) } }
        LaunchedEffect(state, state.playing) {
            while (state.playing) {
                state.syncPosition()
                delay(200)
            }
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, state) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> state.pause()
                    // 切回来时画面可能被系统丢掉了（只剩黑底）：暂停着就在原处重画一帧
                    Lifecycle.Event.ON_RESUME -> state.redraw()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val view = LocalView.current
        DisposableEffect(view, state.playing) {
            view.keepScreenOn = state.playing
            onDispose { view.keepScreenOn = false }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(state) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { state.toggle() })
            },
        contentAlignment = Alignment.Center,
    ) {
        val aspect = state.aspect
        Box(if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize()) {
            if (active) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).apply { surfaceTextureListener = state.surfaceListener } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 第一帧出来前先显示封面
            if (!active || !state.rendering) {
                AsyncImage(model = poster, contentDescription = video.filename, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }

        val error = state.error
        when {
            error != null -> Surface(color = ViewerChromeColor, contentColor = Color.White, shape = MaterialTheme.shapes.large) {
                Text(error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            active && !state.prepared -> LoadingIndicator(modifier = Modifier.size(56.dp))
            else -> AnimatedVisibility(visible = chromeVisible && active, enter = fadeIn(), exit = fadeOut()) {
                FilledIconButton(
                    onClick = state::toggle,
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = ViewerChromeColor, contentColor = Color.White),
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        when {
                            state.playing -> Icons.Filled.Pause
                            state.completed -> Icons.Filled.Replay
                            else -> Icons.Filled.PlayArrow
                        },
                        contentDescription = if (state.playing) "暂停" else "播放",
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible && active && state.prepared,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ProgressBar(state)
        }
    }
}

/** 进度条：已播放时长、可拖动的进度、总时长；放在底部操作栏之上 */
@Composable
private fun ProgressBar(state: PlayerState) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.duration.coerceAtLeast(1L)
    val fraction = dragging ?: (state.position.toFloat() / duration).coerceIn(0f, 1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 92.dp)
            .widthIn(max = 640.dp),
    ) {
        Text(formatClock((fraction * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = Color.White)
        Spacer(Modifier.width(10.dp))
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { state.seekTo((it * duration).toLong()) }
                dragging = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(formatClock(state.duration), style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/** 详情：文件名、路径（可复制）、大小、时长、尺寸、修改时间、来源 */
@Composable
private fun VideoDetailsCard(video: VideoEntity) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(12.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(video.filename, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            DetailRow("来源", if (video.source == VideoEntity.SOURCE_CHAT) "聊天视频" else "收到的文件")
            DetailRow("大小", formatBytes(video.size))
            formatDuration(video.durationMs)?.let { DetailRow("时长", it) }
            if (video.width > 0 && video.height > 0) DetailRow("尺寸", "${video.width} × ${video.height}")
            DetailRow("修改时间", formatDateTime(video.mtime))
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    video.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(top = 10.dp),
                )
                IconButton(onClick = { copyPath(context, video.path) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制路径")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.7f))
    }
}

/** 复制路径（HyperOS 复制后系统不提示，这里总是提示一下） */
internal fun copyPath(context: Context, path: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("路径", path))
    Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
}

private fun formatClock(ms: Long): String = formatDuration(ms.coerceAtLeast(0)) ?: "0:00"

/**
 * 一个视频的播放状态。MediaPlayer 在主线程创建（回调也回到主线程），数据源是只读 fd（setDataSource 返回后即可关闭）；
 * 画面在 TextureView 的 Surface 准备好时接上。播放时申请音频焦点，被别的应用抢走时暂停。
 */
@Stable
private class PlayerState(private val context: Context, video: VideoEntity) {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var focus: AudioFocusRequest? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    var prepared by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var completed by mutableStateOf(false)
        private set
    var rendering by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(video.durationMs.coerceAtLeast(0L))
        private set
    var aspect by mutableFloatStateOf(if (video.width > 0 && video.height > 0) video.width.toFloat() / video.height else 0f)
        private set

    val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            surface = Surface(texture).also { player?.setSurface(it) }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            runCatching { player?.setSurface(null) }
            surface?.release()
            surface = null
            rendering = false
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    fun create() {
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        mp.setOnPreparedListener {
            prepared = true
            duration = it.duration.toLong().coerceAtLeast(0L)
            start()
        }
        mp.setOnVideoSizeChangedListener { _, width, height -> if (width > 0 && height > 0) aspect = width.toFloat() / height }
        mp.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendering = true
            false
        }
        mp.setOnCompletionListener {
            playing = false
            completed = true
            position = duration
            abandonFocus()
        }
        mp.setOnErrorListener { _, what, extra ->
            error = "无法播放这个视频（$what/$extra）"
            playing = false
            true
        }
        surface?.let(mp::setSurface)
        player = mp
    }

    suspend fun load(open: suspend () -> ParcelFileDescriptor?) {
        val pfd = open()
        val mp = player
        if (pfd == null || mp == null) {
            pfd?.close()
            if (mp != null) error = "视频打不开：文件访问未就绪或文件已不在"
            return
        }
        try {
            pfd.use { mp.setDataSource(it.fileDescriptor) }
            mp.prepareAsync()
        } catch (e: Exception) {
            error = "无法播放这个视频"
        }
    }

    fun toggle() {
        if (!prepared) return
        if (playing) {
            pause()
        } else {
            if (completed) seekTo(0)
            start()
        }
    }

    private fun start() {
        val mp = player ?: return
        requestFocus()
        mp.start()
        playing = true
        completed = false
    }

    fun pause() {
        val mp = player ?: return
        if (prepared && playing) mp.pause()
        playing = false
    }

    fun seekTo(ms: Long) {
        val mp = player ?: return
        if (!prepared) return
        mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        position = ms
        completed = false
    }

    /** 暂停时在当前位置重新解一帧 */
    fun redraw() {
        val mp = player ?: return
        if (prepared && !playing) mp.seekTo(position, MediaPlayer.SEEK_CLOSEST)
    }

    fun syncPosition() {
        val mp = player ?: return
        if (prepared) position = mp.currentPosition.toLong()
    }

    fun release() {
        abandonFocus()
        player?.release()
        player = null
        prepared = false
        playing = false
        rendering = false
    }

    private fun requestFocus() {
        val manager = audio ?: return
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause() }
            .build()
            .also { focus = it }
        manager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focus?.let { audio?.abandonAudioFocusRequest(it) }
    }
}
