package com.xjw.qalbum.service.cleanup

data class PictureDeleteOptions(val linkedVersions: Boolean = true, val includeShared: Boolean = false)

data class PictureDeletePreview(
    val pictures: Int = 0, val files: Int = 0, val bytes: Long = 0, val relatedFiles: Int = 0,
    val protectedPictures: Int = 0, val sharedPictures: Int = 0, val unavailablePictures: Int = 0,
    val unlinkedPictures: Int = 0, val error: String? = null,
    val filePaths: List<String> = emptyList(),
) {
    val removablePictures: Int get() = pictures - protectedPictures - sharedPictures - unavailablePictures
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
    fun message(permanent: Boolean = false): String = buildString {
        append(if (permanent) "已永久删除" else "已移入回收站")
        append(" $pictures 张图片、$files 个文件")
        if (skipped > 0) append("；$skipped 组因保护、共用或文件变化而跳过")
        if (partial > 0) append(if(permanent) "；$partial 组永久删除未完成，文件保留在回收站" else "；$partial 组未完整处理，请检查回收站")
    }
}
