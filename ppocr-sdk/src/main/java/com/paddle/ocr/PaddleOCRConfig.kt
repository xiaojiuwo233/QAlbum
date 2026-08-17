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

package com.paddle.ocr

data class PaddleOCRConfig(
    val detImgMode: String = "BGR",
    val detLimitSideLen: Int = 64,
    val detLimitType: String = "min",
    // Hard cap on the longer side of the det input. With limitType="min" ordinary photos are
    // never scaled down, so this cap is the only bound on tensor size: a 4000px cap means a
    // single ~144MB Java FloatArray (3*4000*3008 floats) plus native copies — enough to OOM a
    // default 256MB Android heap. 1600px bounds the det tensor to ~30MB worst case; mobile
    // callers should additionally pass limitType="max" with limitSideLen=960 (see PP-OCR
    // mobile deployment defaults).
    val detMaxSideLimit: Int = 1600,
    val detThresh: Float = 0.3f,
    val detBoxThresh: Float = 0.6f,
    val detUnclipRatio: Float = 1.5f,
    val detMaxCandidates: Int = 3000,
    val detUseDilation: Boolean = false,
    val detScoreMode: String = "fast",
    val detBoxType: String = "quad",
    val recScoreThresh: Float = 0.0f,
    val recBatchSize: Int = 1,
)
