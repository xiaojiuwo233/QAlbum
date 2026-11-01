package com.xjw.qalbum.service.cleanup

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.xjw.qalbum.access.AccessManager
import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.analytics.savedToAlbum
import com.xjw.qalbum.analytics.shared
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * 图片文件操作。
 *
 * 负责把缓存图片导出到系统相册，或生成一个可交给第三方应用预览的临时文件。
 */
class ImageFileManager(
    private val context: Context,
    private val accessManager: AccessManager,
    private val imageDao: ImageDao,
    private val analytics: Analytics? = null,
) {

    private val externalViewDir: File by lazy {
        File(context.cacheDir, "external_view").apply { if (!exists()) mkdirs() }
    }

    private val shareDir: File by lazy {
        File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
    }

    /** 串行化临时目录的「清理 + 写入」：并发调用会把对方刚写好的文件当旧文件删掉 */
    private val tempDirMutex = Mutex()

    /**
     * 是否具备导出到相册的权限。Q 起走 MediaStore scoped 写入无需权限恒 true；
     * API 26-28 需 WRITE_EXTERNAL_STORAGE 运行时授权——未授予时 [exportToGallery] 的
     * contentResolver.insert 会抛 SecurityException 被静默吞掉，UI 应先用本方法判断并发起
     * 运行时请求。
     */
    fun canExportPreQ(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** 逐张导出到系统相册，返回成功的 id；[from] 与 [thenTrash]（保存后移入回收站）只用于统计 */
    suspend fun exportAll(ids: Collection<Long>, subdir: String?, from: String, thenTrash: Boolean = false): List<Long> =
        ids.filter { exportToGallery(it, subdir) }.also { saved ->
            if (ids.isNotEmpty()) analytics?.savedToAlbum(from, "image", ids.size, saved.size, thenTrash)
        }

    /** 将图片导出到系统相册 Pictures/QAlbum[/子目录]。[subdir] 为 null/空时存到 QAlbum 根目录。 */
    suspend fun exportToGallery(imageId: Long, subdir: String? = null): Boolean = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext false
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext false

        var uri: Uri? = null
        try {
            val relativePath = buildString {
                append("Pictures/QAlbum")
                subdir?.let { SettingsRepository.sanitizeExportSubdir(it) }?.let { append("/").append(it) }
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, image.filename.ifBlank { "qalbum_${image.id}.jpg" })
                put(MediaStore.Images.Media.MIME_TYPE, image.mime.ifBlank { "image/jpeg" })
                // chatpic 缓存图普遍无 EXIF，不写 DATE_TAKEN 则批量导出的老照片在系统相册
                // 全部堆在导出当天；mtime 是 app 内唯一时间源，透传给相册排序
                put(MediaStore.Images.Media.DATE_TAKEN, image.mtime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext false

            context.contentResolver.openOutputStream(uri)?.use { out ->
                provider.readFile(image.path).use { input ->
                    input.copyTo(out)
                }
            } ?: throw IOException("openOutputStream returned null for $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            Timber.i("ImageFileManager: exported $imageId to gallery")
            true
        } catch (t: Throwable) {
            // 回滚已插入的 pending 记录：否则 MediaStore 残留 IS_PENDING=1 的孤儿项与半截文件
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            Timber.e(t, "ImageFileManager.exportToGallery failed for $imageId")
            false
        }
    }

    /** 准备一个可交给第三方 App 打开的临时 content Uri。 */
    suspend fun prepareExternalView(imageId: Long): ExternalViewTarget? = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext null
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext null

        try {
            tempDirMutex.withLock {
                val safeName = image.filename.ifBlank { "qalbum_${image.id}.jpg" }
                val dest = File(externalViewDir, "${image.id}_$safeName")
                cleanStaleTempFiles(externalViewDir, keep = dest)
                provider.readFile(image.path).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    dest,
                )
                ExternalViewTarget(uri = uri, mimeType = image.mime.ifBlank { "image/*" })
            }
        } catch (t: Throwable) {
            Timber.e(t, "ImageFileManager.prepareExternalView failed for $imageId")
            null
        }
    }.also { analytics?.shared(openWith = true, ok = it != null) }

    /**
     * 准备一个可分享的临时文件（content Uri）。chatpic 原文件名是无后缀的 `Cache_xxx`，
     * 这里按 MIME 补上正确扩展名（部分 App 必须靠后缀识别图片类型）。
     */
    suspend fun prepareShare(imageId: Long): ExternalViewTarget? = withContext(Dispatchers.IO) {
        val image = imageDao.getById(imageId) ?: return@withContext null
        val provider = accessManager.current()
        if (!provider.isAvailable()) return@withContext null
        try {
            tempDirMutex.withLock {
                val ext = extensionForMime(image.mime)
                val dest = File(shareDir, "qalbum_${image.id}.$ext")
                cleanStaleTempFiles(shareDir, keep = dest)
                provider.readFile(image.path).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
                ExternalViewTarget(uri = uri, mimeType = image.mime.ifBlank { "image/*" })
            }
        } catch (t: Throwable) {
            Timber.e(t, "ImageFileManager.prepareShare failed for $imageId")
            null
        }
    }.also { analytics?.shared(openWith = false, ok = it != null) }

    /**
     * 清理临时目录中过期（> [TEMP_FILE_TTL_MS]）的旧文件。不立即清空整目录：上一个交给
     * 外部 App 的 content Uri 可能仍被读取（旋转/恢复重读），立刻删除会让对方显示失败；
     * 按 age 淘汰即可控住累积（连续分享数百张原图不再无限占用缓存）。
     */
    private fun cleanStaleTempFiles(dir: File, keep: File) {
        val cutoff = System.currentTimeMillis() - TEMP_FILE_TTL_MS
        dir.listFiles()?.forEach { f ->
            if (f.absolutePath != keep.absolutePath && f.lastModified() < cutoff) {
                runCatching { f.delete() }
            }
        }
    }

    private fun extensionForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/bmp" -> "bmp"
        else -> "jpg"
    }

    companion object {
        /** 临时文件保留时长：超过即视为外部 App 不再持有，可安全淘汰 */
        private const val TEMP_FILE_TTL_MS = 60 * 60 * 1000L
    }
}

data class ExternalViewTarget(
    val uri: Uri,
    val mimeType: String,
)
