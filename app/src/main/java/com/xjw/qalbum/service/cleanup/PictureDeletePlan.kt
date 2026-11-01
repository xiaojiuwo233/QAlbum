package com.xjw.qalbum.service.cleanup

/**
 * [includeArchived]：只在归档页删除时为 true——用户在归档里明确删除，归档不再挡住；其余入口归档的图一律跳过。
 * 删除后归档标记保留，从回收站恢复会回到归档。
 */
data class PictureDeleteOptions(
    val linkedVersions: Boolean = true,
    val includeShared: Boolean = false,
    val includeArchived: Boolean = false,
)

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

/** 批量删除各步骤累计用时（毫秒，并行的步骤按墙钟算），结束时写进日志，从用户日志里就能看出慢在哪一步 */
internal class StepTimer {
    private val totals = LinkedHashMap<String, Long>()

    inline fun <T> measure(step: String, block: () -> T): T {
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            add(step, android.os.SystemClock.elapsedRealtime() - started)
        }
    }

    @Synchronized
    fun add(step: String, ms: Long) {
        totals[step] = (totals[step] ?: 0) + ms
    }

    @Synchronized
    override fun toString(): String = totals.entries.joinToString(" ") { "${it.key}=${it.value}" }
}

internal data class PictureAsset(
    val id: Long?, val path: String, val sourceType: String, val size: Long, val mtime: Long, val stamp: String,
)

internal data class PictureFileGroup(
    val key: String, val selectedIds: Set<Long>, val assets: List<PictureAsset>, val linked: Boolean, val blocked: Block? = null,
) { enum class Block { PROTECTED, SHARED, UNAVAILABLE } }

/** [bytes] 处理掉的文件大小（统计用） */
data class PictureDeleteResult(
    val pictures: Int = 0, val files: Int = 0, val selectedProcessed: Int = 0, val skipped: Int = 0, val partial: Int = 0, val bytes: Long = 0,
) {
    operator fun plus(other: PictureDeleteResult) = PictureDeleteResult(
        pictures + other.pictures, files + other.files, selectedProcessed + other.selectedProcessed, skipped + other.skipped, partial + other.partial,
        bytes + other.bytes,
    )

    fun message(permanent: Boolean = false): String = buildString {
        append(if (permanent) "已永久删除" else "已移入回收站")
        append(" $pictures 张图片、$files 个文件")
        if (skipped > 0) append("；$skipped 组已归档、共用或文件有变化，跳过")
        if (partial > 0) append(if(permanent) "；$partial 组永久删除未完成，文件保留在回收站" else "；$partial 组未完整处理，请检查回收站")
    }
}
