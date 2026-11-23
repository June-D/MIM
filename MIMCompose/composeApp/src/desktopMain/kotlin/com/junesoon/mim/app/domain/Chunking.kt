package com.junesoon.mim.app.domain

/**
 * 分片上传的下标与长度计算。
 *
 * 抽成纯函数是因为「最后一片短一点」这类边界最容易写错：
 * 少算一片会传不完，多算一片会被服务端判长度不符。
 */
object Chunking {

    /** 与服务端一致的下限：再小就是拿请求数换流量。 */
    const val MIN_CHUNK = 64 * 1024

    /** 总片数（向上取整）。 */
    fun count(total: Long, chunk: Int): Int {
        require(chunk >= MIN_CHUNK) { "分片太小" }
        if (total <= 0) {
            return 0
        }
        return ((total + chunk - 1) / chunk).toInt()
    }

    /** 第 [index] 片的长度：中间片满长，最后一片是余数。 */
    fun sizeAt(total: Long, chunk: Int, index: Int): Int {
        val offset = index.toLong() * chunk
        require(offset < total) { "分片下标越界" }
        return minOf(chunk.toLong(), total - offset).toInt()
    }

    fun slice(bytes: ByteArray, chunk: Int, index: Int): ByteArray {
        val offset = index * chunk
        require(offset >= 0 && offset < bytes.size) { "分片下标越界" }
        val end = minOf(offset + chunk, bytes.size)
        return bytes.copyOfRange(offset, end)
    }
}
