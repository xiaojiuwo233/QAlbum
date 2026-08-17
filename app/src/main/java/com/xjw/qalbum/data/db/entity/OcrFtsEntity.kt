package com.xjw.qalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * OCR 文本的全文搜索表（FTS4 虚拟表）
 *
 * 设计要点：
 * - Room 仅原生支持 @Fts3 / @Fts4。FTS5 与 FTS4 在 OCR 全文搜索场景下差别极小，
 *   选 FTS4 以获得官方 Room 工具链支持（自动表创建 + 触发器同步）
 * - rowid 必须是 INTEGER，我们让它等于 [OcrTextEntity.imageId]，方便 JOIN
 * - 仅索引 text 字段；其它过滤（手机号/金额等）通过 [OcrTextEntity] 上的布尔列
 * - 默认 simple 分词器无法切分中文（整行中文会成为单个巨型 token），故 [text] 存的是
 *   [com.xjw.qalbum.data.db.FtsTokenize.tokenizeCjkForIndex] 逐字切分后的文本，
 *   查询侧必须配套用 [com.xjw.qalbum.data.db.FtsTokenize.buildFtsMatchQuery] 构造 MATCH；
 *   原文以 [OcrTextEntity.text] 为准
 */
@Fts4
@Entity(tableName = "ocr_fts")
data class OcrFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid") val rowid: Long,
    @ColumnInfo(name = "text") val text: String,
)
