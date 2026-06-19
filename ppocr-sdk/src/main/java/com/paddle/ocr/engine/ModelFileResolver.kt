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

package com.paddle.ocr.engine

import java.io.File

internal object ModelFileResolver {
    fun readBytes(path: String, readAsset: (String) -> ByteArray): ByteArray {
        val file = File(path)
        return if (file.isAbsolute) {
            file.readBytes()
        } else {
            readAsset(path)
        }
    }

    fun readText(path: String, readAsset: (String) -> String): String {
        val file = File(path)
        return if (file.isAbsolute) {
            file.readText(Charsets.UTF_8)
        } else {
            readAsset(path)
        }
    }
}
