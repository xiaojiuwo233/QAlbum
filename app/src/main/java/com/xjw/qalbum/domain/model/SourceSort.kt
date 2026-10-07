package com.xjw.qalbum.domain.model

/** QQ 来源图片的排列方式：日期 / 大小 × 降序 / 升序 */
enum class SourceSort(val label: String, val byDate: Boolean, val descending: Boolean) {
    DATE_DESC("最新优先", byDate = true, descending = true),
    DATE_ASC("最早优先", byDate = true, descending = false),
    SIZE_DESC("最大优先", byDate = false, descending = true),
    SIZE_ASC("最小优先", byDate = false, descending = false);

    fun with(byDate: Boolean = this.byDate, descending: Boolean = this.descending): SourceSort =
        entries.first { it.byDate == byDate && it.descending == descending }

    companion object {
        fun fromName(name: String?): SourceSort = entries.firstOrNull { it.name == name } ?: DATE_DESC
    }
}

/** QQ 来源分类（群聊 / 私聊 / 发送人）列表的排序 */
enum class SourceFacetOrder(val label: String) {
    BYTES("占用空间"),
    COUNT("数量"),
    RECENT("最近活跃"),
    NAME("名称");

    companion object {
        fun fromName(name: String?): SourceFacetOrder = entries.firstOrNull { it.name == name } ?: BYTES
    }
}
