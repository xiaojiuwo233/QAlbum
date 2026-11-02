package com.xjw.qalbum

import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.qq.QqCachePaths

// 单元测试共用的数据（设备测试另有一份，见 androidTest 下的同名文件）

/** QQ 默认的图片缓存目录 */
const val CHATPIC = SettingsRepository.DEFAULT_CHATPIC_PATH

/** 一张图在 chatimg、chatraw、chatthumb、chatthumb（_hd）下的缓存路径 */
fun familyPaths(md5: String, root: String = CHATPIC): List<String> = QqCachePaths.relativePaths(md5).map { "$root/$it" }

/** 一行图片记录：文件名、目录与家族键按路径推出，其余取不影响结果的默认值 */
fun imageRow(
    path: String,
    sourceType: String,
    id: Long = 0,
    size: Long = 1,
    variantCount: Int = 0,
    variantBytes: Long = 0,
) = ImageEntity(
    id = id, path = path, filename = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/'),
    sourceType = sourceType, cacheKey = path.substringAfterLast('/').removePrefix("Cache_"),
    variantGroup = QqCachePaths.familyKey(path), variantCount = variantCount, variantBytes = variantBytes,
    size = size, mtime = 1, firstSeen = 1, lastSeen = 1, mime = "image/jpeg", width = 1, height = 1,
)
