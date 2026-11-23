package com.junesoon.mim.app.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 分片边界：最后一片短一截、刚好整除、以及越界。
 *
 * 这几条算错的结果不是「慢一点」，而是服务端判长度不符、整次上传白传。
 */
class ChunkingTest {

    private val chunk = 64 * 1024

    @Test
    fun counts_round_up() {
        assertEquals(0, Chunking.count(0, chunk))
        assertEquals(1, Chunking.count(1, chunk))
        assertEquals(1, Chunking.count(chunk.toLong(), chunk))
        assertEquals(2, Chunking.count(chunk.toLong() + 1, chunk))
        assertEquals(3, Chunking.count(2L * chunk + 500, chunk))
    }

    @Test
    fun last_chunk_carries_remainder() {
        val total = 2L * chunk + 500
        assertEquals(chunk, Chunking.sizeAt(total, chunk, 0))
        assertEquals(chunk, Chunking.sizeAt(total, chunk, 1))
        assertEquals(500, Chunking.sizeAt(total, chunk, 2))
    }

    @Test
    fun slices_match_sizes() {
        val bytes = ByteArray(2 * chunk + 500) { index -> (index % 251).toByte() }
        val count = Chunking.count(bytes.size.toLong(), chunk)
        val rebuilt = (0 until count).flatMap { index ->
            Chunking.slice(bytes, chunk, index).toList()
        }
        assertContentEquals(bytes, rebuilt.toByteArray())
    }

    @Test
    fun rejects_chunk_below_minimum() {
        var rejected = false
        try {
            Chunking.count(1024, 1024)
        } catch (error: IllegalArgumentException) {
            rejected = true
        }
        assertEquals(true, rejected, "小于服务端下限的分片应直接拒绝")
    }
}
