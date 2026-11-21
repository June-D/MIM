package com.junesoon.mim.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 服务端地址准入：端到端只保护内容，口令 / JWT / 元数据全靠 TLS。
 * 这里把「什么时候允许明文」钉死，避免以后有人悄悄放宽。
 */
class ServerUrlTest {

    @Test
    fun https_is_accepted_and_trailing_slash_removed() {
        assertEquals("https://mim.example.com", ServerUrl.normalize("https://mim.example.com/"))
        assertEquals("https://mim.example.com:8443", ServerUrl.normalize(" HTTPS://mim.example.com:8443 "))
    }

    @Test
    fun loopback_http_is_allowed_for_local_development() {
        assertEquals("http://127.0.0.1:8080", ServerUrl.normalize("http://127.0.0.1:8080"))
        assertEquals("http://localhost:8080", ServerUrl.normalize("http://localhost:8080"))
        assertEquals("http://[::1]:8080", ServerUrl.normalize("http://[::1]:8080"))
    }

    @Test
    fun remote_http_is_rejected_by_default() {
        val error = assertFailsWith<ServerUrl.Invalid> {
            ServerUrl.normalize("http://mim.example.com")
        }
        assertTrue(error.message.orEmpty().contains("明文 HTTP"), error.message.orEmpty())
    }

    @Test
    fun remote_http_needs_an_explicit_opt_in() {
        assertEquals(
            "http://10.0.0.5:8080",
            ServerUrl.normalize("http://10.0.0.5:8080", allowInsecure = true),
        )
    }

    @Test
    fun malformed_or_unsupported_urls_fail_loudly() {
        assertFailsWith<ServerUrl.Invalid> { ServerUrl.normalize("") }
        assertFailsWith<ServerUrl.Invalid> { ServerUrl.normalize("mim.example.com") }
        assertFailsWith<ServerUrl.Invalid> { ServerUrl.normalize("ftp://mim.example.com") }
    }

    @Test
    fun websocket_scheme_follows_http_scheme() {
        assertEquals(
            "wss://mim.example.com",
            ServerUrl.wsBase(ServerUrl.normalize("https://mim.example.com")),
        )
        assertEquals(
            "ws://127.0.0.1:8080",
            ServerUrl.wsBase(ServerUrl.normalize("http://127.0.0.1:8080")),
        )
    }
}
