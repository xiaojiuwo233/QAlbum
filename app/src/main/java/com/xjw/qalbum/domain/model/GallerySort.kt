package com.xjw.qalbum.domain.model

/**
 * 图库排列顺序（持久化在 DataStore，详情页左右滑动与图库网格共用同一排序）。
 *
 * - [DATE_DESC]：新到旧（mtime 降序），默认值，与历史行为一致。
 * - [DATE_ASC]：旧到新（mtime 升序）。
 * - [SIZE_DESC]：大到小（size 降序），网格会按 1MB / 100KB 分桶显示分类标题。
 */
enum class GallerySort(val label: String) {
    DATE_DESC("新到旧"),
    DATE_ASC("旧到新"),
    SIZE_DESC("大到小");

    companion object {
        fun fromName(name: String?): GallerySort =
            entries.firstOrNull { it.name == name } ?: DATE_DESC
    }
}
