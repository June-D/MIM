package com.junesoon.mim.app.data

/**
 * 服务端地址的准入规则。
 *
 * <p>端到端加密只保护消息内容，不保护口令、JWT 与「谁和谁在通信」——
 * 这些都在 HTTP 头上。所以除本机回环地址外，一律要求 https/wss：
 * 明文连接要么被中间人直接接管身份，要么把元数据暴露给链路上的任何人。
 *
 * <p>内网联调确实需要明文时，用 `MIM_ALLOW_INSECURE=1` 显式开启，
 * 并且只在确认链路可信时使用（例如本机端口转发）。
 */
object ServerUrl {

    class Invalid(message: String) : IllegalArgumentException(message)

    /**
     * 校验并规范化地址：去掉尾部斜杠、小写 scheme。
     *
     * @param allowInsecure 仅用于内网调试；默认只放行回环地址的 http。
     */
    fun normalize(raw: String, allowInsecure: Boolean = false): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            throw Invalid("服务端地址为空")
        }
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            ?: throw Invalid("服务端地址不合法：$raw")
        val scheme = uri.scheme?.lowercase() ?: throw Invalid("地址缺少协议：$raw")
        val host = uri.host?.lowercase() ?: throw Invalid("地址缺少主机名：$raw")
        if (scheme != "http" && scheme != "https") {
            throw Invalid("只支持 http/https 地址：$raw")
        }
        if (scheme == "http" && !isLoopback(host) && !allowInsecure) {
            throw Invalid(
                "拒绝使用明文 HTTP 连接 $host：口令与令牌会被链路上的任何人看到。" +
                    "请改用 https://，或仅在内网调试时设置 MIM_ALLOW_INSECURE=1。",
            )
        }
        // 统一重建：scheme 小写、去掉尾部斜杠；大写协议名直接透传会让 Ktor 判错。
        return buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) {
                append(':').append(uri.port)
            }
            append(uri.rawPath.orEmpty().trimEnd('/'))
        }
    }

    /** 由 HTTP 地址推导 WebSocket 地址；https 必须变成 wss。 */
    fun wsBase(normalized: String): String = when {
        normalized.startsWith("https://", ignoreCase = true) ->
            "wss://" + normalized.substring("https://".length)
        normalized.startsWith("http://", ignoreCase = true) ->
            "ws://" + normalized.substring("http://".length)
        else -> throw Invalid("只支持 http/https 地址：$normalized")
    }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
}
