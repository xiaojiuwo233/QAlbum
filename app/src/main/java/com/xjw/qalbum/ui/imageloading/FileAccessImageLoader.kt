package com.xjw.qalbum.ui.imageloading

import android.content.Context
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
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.data.db.entity.ImageEntity
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import java.io.File

/**
 * Coil3 自定义 Fetcher：通过 [AccessManager] 派发当前模式（Shizuku/Root）
 * 把 chatpic 中的 `Cache_{hash}` 文件读成 ImageSource。
 *
 * 用法：UI 端用 `AsyncImage(model = ImageEntityModel(entity))`。
 */
data class ImageEntityModel(val entity: ImageEntity)

/** 直接传一个绝对路径（用于详情页的原图加载等场景） */
data class FileAccessPath(val path: String)

private class ImageEntityFetcher(
    private val data: ImageEntityModel,
    private val accessManager: AccessManager,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val stream = accessManager.current().readFile(data.entity.path)
        return SourceFetchResult(
            source = ImageSource(stream.source().buffer(), fileSystem = okio.FileSystem.SYSTEM),
            mimeType = data.entity.mime,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val accessManager: AccessManager) : Fetcher.Factory<ImageEntityModel> {
        override fun create(data: ImageEntityModel, options: Options, imageLoader: ImageLoader): Fetcher {
            return ImageEntityFetcher(data, accessManager)
        }
    }
}

private class FilePathFetcher(
    private val data: FileAccessPath,
    private val accessManager: AccessManager,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val stream = accessManager.current().readFile(data.path)
        return SourceFetchResult(
            source = ImageSource(stream.source().buffer(), fileSystem = okio.FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val accessManager: AccessManager) : Fetcher.Factory<FileAccessPath> {
        override fun create(data: FileAccessPath, options: Options, imageLoader: ImageLoader): Fetcher {
            return FilePathFetcher(data, accessManager)
        }
    }
}

/** 让 Coil 用 path 作为缓存 key，避免每次重新解码 */
private object ImageEntityKeyer : Keyer<ImageEntityModel> {
    override fun key(data: ImageEntityModel, options: Options): String =
        "qalbum://${data.entity.path}?size=${data.entity.size}&mt=${data.entity.mtime}"
}

private object FilePathKeyer : Keyer<FileAccessPath> {
    override fun key(data: FileAccessPath, options: Options): String =
        "qalbum-path://${data.path}"
}

/** 构造 QAlbum 全局 ImageLoader，带磁盘缓存 + 自定义 Fetcher + 动画解码 */
fun buildImageLoader(context: Context, accessManager: AccessManager): ImageLoader {
    val platform: PlatformContext = context
    return ImageLoader.Builder(platform)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(platform, 0.20)  // 内存缓存最多占 20% 可用堆
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "image_cache").toOkioPath())
                .maxSizeBytes(256L * 1024 * 1024) // 256 MB 磁盘缓存
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
            add(ImageEntityFetcher.Factory(accessManager))
            add(FilePathFetcher.Factory(accessManager))
        }
        .build()
}
