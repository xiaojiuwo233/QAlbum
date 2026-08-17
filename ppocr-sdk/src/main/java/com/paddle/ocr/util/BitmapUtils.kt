// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

object BitmapUtils {

    // Bounds the memory peak of the platform-decoder fallback (2048px max side ≈ 16MB
    // ARGB bitmap). Detection downscales further anyway and recognition crops keep enough
    // resolution at this size.
    private const val FALLBACK_MAX_SIDE = 2048

    fun imdecodeBGR(imageBytes: ByteArray): Mat {
        val encoded = MatOfByte(*imageBytes)
        return try {
            Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        } finally {
            encoded.release()
        }
    }

    /**
     * Decodes to a BGR Mat, falling back to the platform decoder when imdecode fails:
     * Android's opencv_java4 has no HEIF/AVIF/GIF codecs while BitmapFactory decodes HEIF
     * and the first GIF frame. Returns an empty Mat when both decoders fail.
     */
    fun decodeBGRWithFallback(imageBytes: ByteArray): Mat {
        val decoded = imdecodeBGR(imageBytes)
        if (!decoded.empty()) return decoded
        decoded.release()
        val bitmap = decodeDownsampledBitmap(imageBytes) ?: return Mat()
        return try {
            bitmapToBGRMat(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeDownsampledBitmap(imageBytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sampleSize > FALLBACK_MAX_SIDE) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
    }

    fun bitmapToBGRMat(bitmap: Bitmap): Mat {
        return bitmapToMat(bitmap, Imgproc.COLOR_RGBA2BGR)
    }

    fun bitmapToRGBMat(bitmap: Bitmap): Mat {
        return bitmapToMat(bitmap, Imgproc.COLOR_RGBA2RGB)
    }

    fun bgrMatToBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        return try {
            Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
            Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also { bitmap ->
                Utils.matToBitmap(rgba, bitmap)
            }
        } finally {
            rgba.release()
        }
    }

    private fun bitmapToMat(bitmap: Bitmap, colorConversionCode: Int): Mat {
        val bmp = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat(bmp.height, bmp.width, CvType.CV_8UC4)
        val dst = Mat()
        return try {
            Utils.bitmapToMat(bmp, rgba)
            Imgproc.cvtColor(rgba, dst, colorConversionCode)
            dst
        } catch (t: Throwable) {
            dst.release()
            throw t
        } finally {
            bmp.recycle()
            rgba.release()
        }
    }
}
