package com.xjw.qalbum.ui.imageloading

import android.util.LruCache
import coil3.memory.MemoryCache

/**
 * 网格格子里已经解码好的小图在内存缓存里的 key（按图片 id）。看图页先拿它当占位图，
 * 打开的放大动画一开始就有图，原图加载出来再淡入替换。
 */
object TileImageKeys {
    private val keys = LruCache<Long, MemoryCache.Key>(512)

    fun put(imageId: Long, key: MemoryCache.Key) {
        keys.put(imageId, key)
    }

    operator fun get(imageId: Long): MemoryCache.Key? = keys.get(imageId)
}
