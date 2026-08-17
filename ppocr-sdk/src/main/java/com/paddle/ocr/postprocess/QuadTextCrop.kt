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

package com.paddle.ocr.postprocess

import com.paddle.ocr.model.OCRBox
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

object QuadTextCrop {
    private const val VERTICAL_CROP_RATIO = 1.5

    /**
     * Estimated width/height ratio of the crop that [crop] would produce, computed from the
     * quad's edge lengths only (no Mat allocation). Mirrors the vertical-rotation rule so
     * near-vertical boxes report their post-rotation ratio. Used to group crops of similar
     * width into the same recognition batch — grouping only, never affects results.
     */
    fun estimatedCropRatio(box: OCRBox): Double {
        val p = box.points
        val widthTop = hypot((p[0].x - p[1].x).toDouble(), (p[0].y - p[1].y).toDouble())
        val widthBottom = hypot((p[2].x - p[3].x).toDouble(), (p[2].y - p[3].y).toDouble())
        val heightLeft = hypot((p[0].x - p[3].x).toDouble(), (p[0].y - p[3].y).toDouble())
        val heightRight = hypot((p[1].x - p[2].x).toDouble(), (p[1].y - p[2].y).toDouble())
        val w = max(widthTop, widthBottom).coerceAtLeast(1.0)
        val h = max(heightLeft, heightRight).coerceAtLeast(1.0)
        return if (h / w >= VERTICAL_CROP_RATIO) h / w else w / h
    }

    fun crop(src: Mat, box: OCRBox): Mat {
        // Align with PaddleX CropByPolys.get_minarea_rect_crop: recompute minAreaRect
        // from the detected quad before perspective transform.
        val rectPoints = box.points.map { Point(it.x.toDouble(), it.y.toDouble()) }
        val rectInput = MatOfPoint2f()
        rectInput.fromList(rectPoints)
        val boundingBox = Imgproc.minAreaRect(rectInput)
        rectInput.release()

        val boxPoints = Array(4) { Point() }
        boundingBox.points(boxPoints)
        val ordered = QuadGeometry.orderMinAreaRectPoints(boxPoints)

        val widthTop = hypot(ordered[0].x - ordered[1].x, ordered[0].y - ordered[1].y)
        val widthBottom = hypot(ordered[2].x - ordered[3].x, ordered[2].y - ordered[3].y)
        val heightLeft = hypot(ordered[0].x - ordered[3].x, ordered[0].y - ordered[3].y)
        val heightRight = hypot(ordered[1].x - ordered[2].x, ordered[1].y - ordered[2].y)

        val dstW = max(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val dstH = max(heightLeft, heightRight).toInt().coerceAtLeast(1)

        val srcPts = MatOfPoint2f()
        srcPts.fromList(ordered)
        val dstPts = MatOfPoint2f()
        dstPts.fromList(
            listOf(
                Point(0.0, 0.0),
                Point(dstW.toDouble(), 0.0),
                Point(dstW.toDouble(), dstH.toDouble()),
                Point(0.0, dstH.toDouble()),
            )
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        srcPts.release()
        dstPts.release()

        val dst = Mat(dstH, dstW, CvType.CV_8UC3)
        Imgproc.warpPerspective(
            src,
            dst,
            m,
            Size(dstW.toDouble(), dstH.toDouble()),
            Imgproc.INTER_CUBIC,
            Core.BORDER_REPLICATE,
        )
        m.release()

        if (dst.rows().toDouble() / dst.cols() >= VERTICAL_CROP_RATIO) {
            val rotated = Mat()
            Core.rotate(dst, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            dst.release()
            return rotated
        }
        return dst
    }
}
