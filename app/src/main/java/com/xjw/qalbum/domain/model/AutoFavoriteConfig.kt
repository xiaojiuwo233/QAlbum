package com.xjw.qalbum.domain.model

import kotlinx.serialization.Serializable

/**
 * 自动收藏配置：分析（评分）阶段，根据 OCR 命中的敏感信息自动把图片加入收藏，避免误删。
 *
 * 这是一个可选特性，[enabled] 默认关闭——只有用户在「设置 → 分析 → 自动收藏」里主动开启后才生效，
 * 且具体命中哪些信息类型由下面各开关自定义。
 *
 * 字段带默认值，保证 JSON 反序列化缺字段时安全回退。
 */
@Serializable
data class AutoFavoriteConfig(
    /** 总开关：关闭时分析阶段完全不自动收藏 */
    val enabled: Boolean = false,
    /** 含证件号（身份证等） */
    val idNumber: Boolean = true,
    /** 含订单号 / 快递单号 */
    val orderNo: Boolean = true,
    /** 含金额 */
    val amount: Boolean = true,
    /** 含手机号 */
    val phone: Boolean = false,
    /** 含地址 */
    val address: Boolean = false,
) {
    /** 是否启用了至少一条命中规则（总开关开启且任一类型开启才真正会收藏） */
    val hasAnyRule: Boolean
        get() = idNumber || orderNo || amount || phone || address

    companion object {
        /** 首次使用的默认配置：关闭，规则沿用历史行为（证件 / 订单 / 金额） */
        val DEFAULT = AutoFavoriteConfig()
    }
}
