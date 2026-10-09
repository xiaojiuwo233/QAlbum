package com.xjw.qalbum.domain.model

/** 网格分组标题覆盖的范围：点标题全选这一组时按它查询 */
sealed interface TimelineSection {
    /** 某一天：本地时间 [from, until)，毫秒 */
    data class Day(val from: Long, val until: Long) : TimelineSection

    /** 某个大小区间：[low, high)，字节 */
    data class Size(val low: Long, val high: Long) : TimelineSection
}
