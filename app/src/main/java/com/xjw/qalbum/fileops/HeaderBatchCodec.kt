package com.xjw.qalbum.fileops

import java.io.ByteArrayOutputStream

/**
 * [IFileOps.readHeaders] 的批量结果编解码。
 *
 * AIDL 不支持 `List<byte[]>`，这里把 N 个「可空字节数组」打包进一个 byte[]：
 * 每项 = 4 字节大端长度（-1 表示该文件不可读）+ 实际负载字节。
 * 服务端 [pack]，客户端 [unpack]，两端共用本对象保证格式一致。
 */
object HeaderBatchCodec {

    fun pack(headers: List<ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream(headers.size * 40)
        for (h in headers) {
            if (h == null) {
                writeInt(out, -1)
            } else {
                writeInt(out, h.size)
                out.write(h)
            }
        }
        return out.toByteArray()
    }

    /**
     * @param count 期望的条目数（即请求的 paths 数量）
     * @throws IllegalArgumentException 数据截断或长度非法时
     */
    fun unpack(blob: ByteArray, count: Int): List<ByteArray?> {
        val result = ArrayList<ByteArray?>(count)
        var pos = 0
        repeat(count) {
            require(pos + 4 <= blob.size) { "header batch truncated at entry ${result.size}" }
            val len = readInt(blob, pos)
            pos += 4
            if (len < 0) {
                result.add(null)
            } else {
                require(pos + len <= blob.size) { "header batch payload truncated at entry ${result.size}" }
                result.add(blob.copyOfRange(pos, pos + len))
                pos += len
            }
        }
        return result
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun readInt(b: ByteArray, pos: Int): Int =
        ((b[pos].toInt() and 0xFF) shl 24) or
            ((b[pos + 1].toInt() and 0xFF) shl 16) or
            ((b[pos + 2].toInt() and 0xFF) shl 8) or
            (b[pos + 3].toInt() and 0xFF)
}
