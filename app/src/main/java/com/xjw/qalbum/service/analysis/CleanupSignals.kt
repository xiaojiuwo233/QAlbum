package com.xjw.qalbum.service.analysis

import com.xjw.qalbum.data.db.entity.ImageEntity

object CleanupSignals {
    fun pixelCount(image: ImageEntity): Long =
        if (image.width > 0 && image.height > 0) {
            image.width.toLong() * image.height.toLong()
        } else {
            0L
        }

    fun isVeryLowResolution(image: ImageEntity): Boolean {
        val pixels = pixelCount(image)
        if (pixels <= 0L) return false
        return minOf(image.width, image.height) < VERY_LOW_RES_SHORT_SIDE ||
            pixels < VERY_LOW_RES_PIXELS
    }

    fun isLowResolution(image: ImageEntity): Boolean {
        val pixels = pixelCount(image)
        if (pixels <= 0L) return false
        return !isVeryLowResolution(image) && pixels < LOW_RES_PIXELS
    }

    private const val VERY_LOW_RES_SHORT_SIDE = 160
    private const val VERY_LOW_RES_PIXELS = 80_000L
    private const val LOW_RES_PIXELS = 300_000L
}
