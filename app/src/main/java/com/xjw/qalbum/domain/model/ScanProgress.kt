package com.xjw.qalbum.domain.model

/** 扫描进度事件，供 UI 层订阅显示 */
sealed class ScanProgress {
    /** 初始化中（统计总文件数） */
    data class Enumerating(val total: Int) : ScanProgress()

    /** 正在写入索引 */
    data class Indexing(val processed: Int, val total: Int) : ScanProgress()

    /** 扫描完成 */
    data class Done(val total: Int) : ScanProgress()

    /** 扫描出错；[code] 是统计用的原因代码（不含路径），出异常时为异常类名 */
    data class Failed(val reason: String, val code: String = "error") : ScanProgress()

    /** 用户主动取消（区别于 Failed，不作为错误展示） */
    object Cancelled : ScanProgress()
}
