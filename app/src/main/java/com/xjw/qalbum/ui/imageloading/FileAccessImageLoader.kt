package com.xjw.qalbum.ui.imageloading

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Size
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsClient
import com.xjw.qalbum.qq.QqCachePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Coil3 自定义 Fetcher：图库/搜索/清理等网格缩略图与详情页原图的读取入口。
 *
 * 读取路径（快到慢）：
 * 1. Coil 磁盘缓存命中 → 本地文件直读，零 IPC（缓存由本文件的 fetcher 自行读写，
 *    Coil 内置磁盘缓存只服务网络 fetcher，对自定义 fetcher 不生效）；
 * 2. AIDL fd 快路径（[FdFastPath]）→ 一次 binder 往返拿 ParcelFileDescriptor 直读；
 * 3. provider（Shizuku shell / libsu）兜底 → 每图派生进程/串行 shell，读到后同样落盘缓存，
 *    保证滑回不再重走慢路径。
 *
 * 用法：UI 端用 `AsyncImage(model = ImageEntityModel(entity))`。
 *
 * [tile] 为真（网格小格子）时先读同一张图更小的版本：QQ 高清缩略图、原图对应的预览图（格子很小时也用普通缩略图），
 * 几十到几百 KB，比读几 MB 的原图快得多；都没有再读文件本身。看图页等需要原图的地方不设。
 */
data class ImageEntityModel(val entity: ImageEntity, val tile: Boolean = false)

/** 直接传一个绝对路径（用于详情页的原图加载等场景） */
data class FileAccessPath(val path: String)

/**
 * 特权 fd 快路径：进程级共享一份 [IFileOps] AIDL 绑定（引用计数由 [FileOpsClient] 管理，
 * 本持有者随进程存活不主动释放），每次读取一次 binder 往返经 openRead 拿 fd 直读，替代
 * 逐图 fork `cat` 进程（Shizuku）/单一 root shell 串行读（Root）。
 *
 * 绑定在独立 scope 异步进行：fetch 永不等待绑定（期间降级 provider 读），也不会因滑动取消
 * 把绑定协程一并取消；绑定失败按退避重试，binder 死亡/超时后失活并同样按退避重绑。
 */
private class FdFastPath(
    private val accessManager: AccessManager,
    private val fileOpsClient: FileOpsClient,
) {
    private data class ActiveBinding(
        val mode: AccessMode,
        val handle: BoundFileOps,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bound = AtomicReference<ActiveBinding?>(null)
    private val binding = AtomicBoolean(false)

    @Volatile
    private var nextBindAt = 0L

    /** 返回 null = 快路径当前不可用或该文件打不开，调用方降级 provider 读 */
    suspend fun openRead(path: String): ParcelFileDescriptor? {
        val mode = accessManager.current().getAccessMode()
        var snapshot = bound.get()
        if (snapshot != null && snapshot.mode != mode) {
            if (bound.compareAndSet(snapshot, null)) snapshot.handle.close()
            snapshot = bound.get()
        }
        if (snapshot == null) {
            maybeBindAsync(mode)
            return null
        }
        return try {
            snapshot.handle.callWithTimeout(
                timeoutMs = OPEN_TIMEOUT_MS,
                onAbandoned = { pfd: ParcelFileDescriptor? -> pfd?.close() },
            ) { it.openRead(path) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // binder 死亡 / 调用超时：失活当前绑定（并发失败只有一个生效），之后按退避重绑
            if (bound.compareAndSet(snapshot, null)) {
                snapshot.handle.close()
                Timber.w(t, "图片 fd 快路径失活，降级 provider 读")
            }
            null
        }
    }

    private fun maybeBindAsync(mode: AccessMode) {
        if (SystemClock.elapsedRealtime() < nextBindAt) return
        if (!binding.compareAndSet(false, true)) return
        scope.launch {
            try {
                val fresh = fileOpsClient.bind(mode)
                if (fresh != null) {
                    val active = ActiveBinding(mode, fresh)
                    if (accessManager.current().getAccessMode() != mode) {
                        fresh.close()
                    } else if (bound.compareAndSet(null, active)) {
                        nextBindAt = 0L
                        Timber.i("图片 fd 快路径就绪（$mode）")
                    } else {
                        fresh.close()
                    }
                } else {
                    nextBindAt = SystemClock.elapsedRealtime() + BIND_BACKOFF_MS
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                nextBindAt = SystemClock.elapsedRealtime() + BIND_BACKOFF_MS
                Timber.w(t, "图片 fd 快路径绑定失败")
            } finally {
                binding.set(false)
            }
        }
    }

    companion object {
        /** 单次 openRead 的 binder 超时：UI 路径不允许默认 30s 级挂起 */
        private const val OPEN_TIMEOUT_MS = 10_000L

        /** 绑定失败的重试退避：Shizuku/Root 未就绪时避免每个格子都触发一次绑定尝试 */
        private const val BIND_BACKOFF_MS = 10_000L
    }
}

/**
 * provider 兜底读的取消安全获取：readFile 在实现内部的 withContext 里派生 cat 进程/打开
 * su 流，调用协程恰在此窗口被取消时 withContext 会丢弃返回值——进程与 pipe fd 无人持有，
 * 只能等 GC finalizer 兜底。先以 NonCancellable 保证流完整送达，再自查取消：确已取消则
 * 先关流再照常抛出。
 */
private suspend fun acquireProviderStream(accessManager: AccessManager, path: String): InputStream {
    val stream = withContext(NonCancellable) { accessManager.current().readFile(path) }
    try {
        currentCoroutineContext().ensureActive()
    } catch (ce: CancellationException) {
        runCatching { stream.close() }
        throw ce
    }
    return stream
}

/** fd 快路径优先、provider 兜底；返回的流由 Coil 的 Source 生命周期负责关闭。 */
private suspend fun openSourceStream(
    fastPath: FdFastPath,
    accessManager: AccessManager,
    path: String,
): InputStream = fastPath.openRead(path)
    ?.let(ParcelFileDescriptor::AutoCloseInputStream)
    ?: acquireProviderStream(accessManager, path)

private class ImageEntityFetcher(
    private val data: ImageEntityModel,
    private val options: Options,
    private val accessManager: AccessManager,
    private val fastPath: FdFastPath,
    private val diskCache: DiskCache?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val entity = data.entity
        if (data.tile) {
            for (path in smallerVersions(entity, options.size)) {
                fetchSmallerVersion(path)?.let { return it }
            }
        }
        val diskKey = entityDiskKey(entity)
        if (options.diskCachePolicy.readEnabled) {
            readFromDiskCache(diskKey)?.let { return it }
        }
        if (options.diskCachePolicy.writeEnabled) {
            writeToDiskCache(diskKey, entity.path, entity.size)?.let { return it }
        }
        val stream = openSourceStream(fastPath, accessManager, entity.path)
        return SourceFetchResult(
            source = ImageSource(source = stream.source().buffer(), fileSystem = FileSystem.SYSTEM),
            mimeType = entity.mime,
            dataSource = DataSource.DISK,
        )
    }

    private fun readFromDiskCache(key: String, mimeType: String? = data.entity.mime): SourceFetchResult? {
        val cache = diskCache ?: return null
        val snapshot = runCatching { cache.openSnapshot(key) }.getOrNull() ?: return null
        return SourceFetchResult(
            source = ImageSource(
                file = snapshot.data,
                fileSystem = cache.fileSystem,
                diskCacheKey = key,
                closeable = snapshot,
            ),
            mimeType = mimeType,
            dataSource = DataSource.DISK,
        )
    }

    /**
     * 读同一张图的另一个（更小的）版本：先查磁盘缓存，再只走 fd 快路径打开（不存在时一次 binder 往返即返回 null，
     * 快路径未就绪也返回 null，交给原文件的读取流程）。同一 MD5 的版本内容不变，缓存键不带大小 / 时间。
     */
    private suspend fun fetchSmallerVersion(path: String): SourceFetchResult? {
        val key = "qalbum-version://$path"
        if (options.diskCachePolicy.readEnabled) readFromDiskCache(key, mimeType = null)?.let { return it }
        val pfd = fastPath.openRead(path) ?: return null
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val cache = diskCache
        val editor = if (cache != null && options.diskCachePolicy.writeEnabled) runCatching { cache.openEditor(key) }.getOrNull() else null
        if (cache == null || editor == null) {
            return SourceFetchResult(ImageSource(stream.source().buffer(), FileSystem.SYSTEM), mimeType = null, dataSource = DataSource.DISK)
        }
        return try {
            val copied = stream.source().use { source -> cache.fileSystem.write(editor.data) { writeAll(source) } }
            if (copied <= 0L) throw IOException("图片为空：$path")
            val snapshot = editor.commitAndOpenSnapshot() ?: return null
            SourceFetchResult(
                source = ImageSource(file = snapshot.data, fileSystem = cache.fileSystem, diskCacheKey = key, closeable = snapshot),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } catch (ce: CancellationException) {
            runCatching { editor.abort() }
            throw ce
        } catch (t: Throwable) {
            runCatching { editor.abort() }
            Timber.w(t, "小版本读取失败，改读原文件：$path")
            null
        }
    }

    /** 完整源文件流式落盘；任何一步失败返回 null，缓存故障绝不挡住直接展示。 */
    private suspend fun writeToDiskCache(key: String, path: String, expectedSize: Long): SourceFetchResult? {
        val cache = diskCache ?: return null
        val editor = runCatching { cache.openEditor(key) }.getOrNull() ?: return null
        return try {
            val copied = try {
                copySourceTo(cache, editor.data, path, expectedSize, preferFastPath = true)
            } catch (ce: CancellationException) {
                throw ce
            } catch (io: IOException) {
                Timber.w(io, "fd/cache 读取失败，provider 复读一次：$path")
                copySourceTo(cache, editor.data, path, expectedSize, preferFastPath = false)
            }
            if (copied <= 0L) throw IOException("图片为空：$path")
            val snapshot = editor.commitAndOpenSnapshot()
            if (snapshot == null) {
                editor.abort()
                return null
            }
            SourceFetchResult(
                source = ImageSource(
                    file = snapshot.data,
                    fileSystem = cache.fileSystem,
                    diskCacheKey = key,
                    closeable = snapshot,
                ),
                mimeType = data.entity.mime,
                dataSource = DataSource.DISK,
            )
        } catch (ce: CancellationException) {
            runCatching { editor.abort() }
            throw ce
        } catch (t: Throwable) {
            runCatching { editor.abort() }
            Timber.w(t, "图片磁盘缓存写入失败：$key")
            null
        }
    }

    private suspend fun copySourceTo(
        cache: DiskCache,
        target: okio.Path,
        path: String,
        expectedSize: Long,
        preferFastPath: Boolean,
    ): Long {
        val stream = if (preferFastPath) {
            openSourceStream(fastPath, accessManager, path)
        } else {
            acquireProviderStream(accessManager, path)
        }
        val copied = stream.source().use { source ->
            cache.fileSystem.write(target) { writeAll(source) }
        }
        if (expectedSize > 0L && copied != expectedSize) {
            throw IOException("图片读取长度变化：$path ($copied != $expectedSize)")
        }
        return copied
    }

    class Factory(
        private val accessManager: AccessManager,
        private val fastPath: FdFastPath,
    ) : Fetcher.Factory<ImageEntityModel> {
        override fun create(data: ImageEntityModel, options: Options, imageLoader: ImageLoader): Fetcher {
            return ImageEntityFetcher(data, options, accessManager, fastPath, imageLoader.diskCache)
        }
    }
}

private class FilePathFetcher(
    private val data: FileAccessPath,
    private val accessManager: AccessManager,
    private val fastPath: FdFastPath,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // 裸路径无 size/mtime 版本信息，不落磁盘缓存（避免文件原位替换后读到陈旧内容）；
        // fd 直读为流式，不整图进内存
        val pfd = fastPath.openRead(data.path)
        val source = if (pfd != null) {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).source().buffer()
        } else {
            acquireProviderStream(accessManager, data.path).source().buffer()
        }
        return SourceFetchResult(
            source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val accessManager: AccessManager,
        private val fastPath: FdFastPath,
    ) : Fetcher.Factory<FileAccessPath> {
        override fun create(data: FileAccessPath, options: Options, imageLoader: ImageLoader): Fetcher {
            return FilePathFetcher(data, accessManager, fastPath)
        }
    }
}

/** 磁盘缓存 key：同一文件只存一份源字节，size+mtime 版本化（文件被替换后自动失效） */
private fun entityDiskKey(entity: ImageEntity): String =
    "qalbum://${entity.path}?size=${entity.size}&mt=${entity.mtime}"

/**
 * 网格格子可以改读的同一张图更小的版本，按优先顺序：格子很小时的普通缩略图（长边约 198px）、高清缩略图、
 * 原图对应的预览图。只有 QQ 缓存命名的图片有（见 QqCachePaths.familyPaths），缩略图本身不再找。
 */
internal fun smallerVersions(entity: ImageEntity, size: Size): List<String> {
    if (entity.sourceType == "chatthumb") return emptyList()
    val family = QqCachePaths.familyPaths(entity.path)
    if (family.size < 2) return emptyList()
    val thumbs = family.filter { "/chatthumb/" in it }
    val hd = thumbs.filter { it.endsWith("_hd") }
    val plain = thumbs.filterNot { it.endsWith("_hd") }
    val requested = listOf(size.width, size.height).mapNotNull { (it as? Dimension.Pixels)?.px }.maxOrNull() ?: Int.MAX_VALUE
    val tiny = if (requested <= SMALL_THUMB_PX) plain else emptyList()
    val preview = if (entity.sourceType == "chatraw") family.filter { "/chatimg/" in it } else emptyList()
    return tiny + hd + preview
}

/** QQ 普通缩略图长边约 198px：格子不超过这么大时才用它 */
private const val SMALL_THUMB_PX = 220

/** 内存缓存 key 在磁盘 key 之上追加请求尺寸：网格小图与详情页大图各自命中，互不顶替；格子读的是小版本，再加标记 */
private object ImageEntityKeyer : Keyer<ImageEntityModel> {
    override fun key(data: ImageEntityModel, options: Options): String =
        "${entityDiskKey(data.entity)}&vp=${options.size}" + if (data.tile) "&tile" else ""
}

private object FilePathKeyer : Keyer<FileAccessPath> {
    override fun key(data: FileAccessPath, options: Options): String =
        "qalbum-path://${data.path}?vp=${options.size}"
}

/** 构造 QAlbum 全局 ImageLoader，带磁盘缓存 + 自定义 Fetcher + 动画解码 */
fun buildImageLoader(
    context: Context,
    accessManager: AccessManager,
    fileOpsClient: FileOpsClient,
): ImageLoader {
    val platform: PlatformContext = context
    val fastPath = FdFastPath(accessManager, fileOpsClient)
    return ImageLoader.Builder(platform)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(platform, 0.30)  // 内存缓存占 30%
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "image_cache").toOkioPath())
                // 512 MB 磁盘缓存：由 ImageEntityFetcher 自行读写（Coil 内置只服务网络
                // fetcher），滑动回读命中本地文件，不再逐图经特权 IPC 重读原图
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .crossfade(180)
        .components {
            // 动画解码（GIF / Animated WebP）；HEIF 由系统 BitmapFactory 直接支持
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            // 自定义 fetcher + keyer
            add(ImageEntityKeyer)
            add(FilePathKeyer)
            add(ImageEntityFetcher.Factory(accessManager, fastPath))
            add(FilePathFetcher.Factory(accessManager, fastPath))
        }
        .build()
}
