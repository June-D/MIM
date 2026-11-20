package com.junesoon.mim.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/** 统一的 JSON 编解码器，忽略未知字段以兼容服务端新增字段。 */
internal object JsonCodec {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(serializer<T>(), value)

    inline fun <reified T> decode(text: String): T = json.decodeFromString(serializer<T>(), text)
}
