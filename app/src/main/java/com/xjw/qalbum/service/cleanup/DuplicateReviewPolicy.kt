package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.data.db.entity.ImageEntity

/**
 * 重复组的「保留 / 勾选」决策（纯函数）。
 *
 * 不依赖 DB 里的 `is_duplicate_best`（它在 [com.xjw.qalbum.service.analysis.DuplicateDetector.clusterVisual]
 * 中存在「最优图已属其他组时整组 isBest 丢失」的 bug，会导致整组被误判）。改为审核时按组重算：
 *
 * - **保留图**：组内质量最高（chatraw 原图 > 其他非 chatimg > chatimg 压缩图；再比 分辨率 > 清晰度 > 文件大小；平手取 id 小）。
 * - **默认勾选删除**：仅非 QQ 缓存的劣质副本；QQ 缓存未验证聊天引用时不预选。
 * - 质量与保留图相当且非精确副本 → 不勾，交给用户判断（避免误删）。
 */
object DuplicateReviewPolicy {

    data class Decision(val keepId: Long, val deleteIds: Set<Long>)

    /**
     * 组内保留图：质量最高的一张。比较优先级（高→低）：来源（chatraw>其他>chatimg）、
     * 分辨率、清晰度（null 视为最低）、文件大小；全部平手取 id 最小（稳定）。
     */
    fun pickKeeper(group: List<ImageEntity>): ImageEntity? =
        group.maxWithOrNull(
            compareBy<ImageEntity> { sourceRank(it.sourceType) }
                .thenBy { it.width.toLong() * it.height }
                .thenBy { it.blurScore ?: Float.NEGATIVE_INFINITY }
                .thenBy { it.size }
                .thenByDescending { it.id },
        )

    /**
     * 决策：在组内（仅 active）选出保留图，并给出默认勾选删除的「劣质副本」集合。
     * 组为空返回 null。
     */
    fun decide(group: List<ImageEntity>): Decision? {
        val active = group.filter { it.status == "active" }
        val keeper = pickKeeper(active) ?: return null
        val keeperPixels = keeper.width.toLong() * keeper.height
        val deleteIds = active.asSequence()
            .filter { it.id != keeper.id && isInferiorCopy(it, keeper, keeperPixels) }
            .map { it.id }
            .toSet()
        return Decision(keeper.id, deleteIds)
    }

    private fun sourceRank(source: String): Int = when (source) {
        "chatraw" -> 2   // 原图最优，优先保留
        "chatimg" -> 0   // 聊天压缩图最劣
        else -> 1
    }

    /** 画质不能证明 QQ 路径可删；仅为非 QQ 缓存计算默认选择。 */
    private fun isInferiorCopy(img: ImageEntity, keeper: ImageEntity, keeperPixels: Long): Boolean {
        // QQ 的缓存路径可能各自被聊天记录引用，画质或字节相同不足以证明可以删除。
        if (img.sourceType in setOf("chatimg", "chatraw", "chatthumb", "temp")) return false
        if (img.xxhash != null && img.xxhash == keeper.xxhash) return true
        val pixels = img.width.toLong() * img.height
        if (pixels in 1 until keeperPixels) return true                    // 分辨率更低
        val ib = img.blurScore
        val kb = keeper.blurScore
        if (ib != null && kb != null && ib < kb) return true               // 更模糊
        return false
    }
}
