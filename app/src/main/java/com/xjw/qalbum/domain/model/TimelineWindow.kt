package com.xjw.qalbum.domain.model

/**
 * 详情页时间线的键集窗口：以某张图为锚点的一段连续 id 片段，替代全量 id 装载
 * （200 万行时全量装载查询秒级、装箱 List 常驻数十 MB）。
 *
 * [total] 与 [rank] 来自独立 COUNT 查询，与 [ids] 非同一快照——时间线并发增删时
 * 可能有个位数漂移，仅供「第 x / N 张」类展示，不得当作精确游标使用。
 *
 * @param total 当前时间线总数
 * @param rank 锚点图在全序中的 0-based 序号
 * @param ids 以锚点为中心的连续 id 窗口（已按时间线顺序排列）
 * @param anchorIndex 锚点在 [ids] 中的下标
 */
data class TimelineWindow(
    val total: Int,
    val rank: Int,
    val ids: List<Long>,
    val anchorIndex: Int,
)
