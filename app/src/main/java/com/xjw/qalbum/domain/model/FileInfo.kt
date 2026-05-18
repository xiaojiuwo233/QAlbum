package com.xjw.qalbum.domain.model

/**
 * 文件元信息，由 [com.xjw.qalbum.access.FileAccessProvider] 实现产出
 *
 * Shizuku/Root 两种模式都通过命令行接口拿到这一份结构化结果。
 */
data class FileInfo(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
)
