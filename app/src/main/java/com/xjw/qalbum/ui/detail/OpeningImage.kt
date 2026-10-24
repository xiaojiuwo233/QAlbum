package com.xjw.qalbum.ui.detail

import com.xjw.qalbum.data.db.entity.ImageEntity

/**
 * 刚从网格点开的那张图。看图页第一帧就用它把图画出来，不等数据库：共享元素转场从第一帧就有终点，
 * 格子里的图不会先停在原地盖在页面上、等查完数据库再一下跳成大图。
 */
object OpeningImage {
    @Volatile
    private var image: ImageEntity? = null

    fun set(image: ImageEntity) {
        this.image = image
    }

    fun peek(id: Long): ImageEntity? = image?.takeIf { it.id == id }
}
