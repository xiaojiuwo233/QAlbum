package com.xjw.qalbum.service.cleanup

data class PictureDeleteOptions(val linkedVersions: Boolean = true, val includeShared: Boolean = false)

/**
 * 删除确认弹窗用的事实（见 QuarantineManager.deleteFacts）。[bytes] 是所选文件自身大小，[versionBytes] 连同
 * 同一张图的其他版本；[shared] 只算未受保护的图片。[accessError] 不为 null 时文件访问未就绪。
 */
data class PictureDeleteFacts(
    val count: Int, val bytes: Long, val versionBytes: Long, val withVersions: Int,
    val protectedCount: Int, val shared: Int, val accessError: String?,
) {
    /** 按当前选项会真正处理的张数 */
    fun removable(options: PictureDeleteOptions): Int = count - protectedCount - if (options.includeShared) 0 else shared

    fun bytes(options: PictureDeleteOptions): Long = if (options.linkedVersions) versionBytes else bytes
}

internal data class PictureAsset(
    val id: Long?, val path: String, val sourceType: String, val size: Long, val mtime: Long, val stamp: String,
)

internal data class PictureFileGroup(
    val key: String, val selectedIds: Set<Long>, val assets: List<PictureAsset>, val linked: Boolean, val blocked: Block? = null,
) { enum class Block { PROTECTED, SHARED, UNAVAILABLE } }

data class PictureDeleteResult(
    val pictures: Int = 0, val files: Int = 0, val selectedProcessed: Int = 0, val skipped: Int = 0, val partial: Int = 0,
) {
    operator fun plus(other: PictureDeleteResult) = PictureDeleteResult(
        pictures + other.pictures, files + other.files, selectedProcessed + other.selectedProcessed, skipped + other.skipped, partial + other.partial,
    )

    fun message(permanent: Boolean = false): String = buildString {
        append(if (permanent) "已永久删除" else "已移入回收站")
        append(" $pictures 张图片、$files 个文件")
        if (skipped > 0) append("；$skipped 组因保护、共用或文件变化而跳过")
        if (partial > 0) append(if(permanent) "；$partial 组永久删除未完成，文件保留在回收站" else "；$partial 组未完整处理，请检查回收站")
    }
}
