package com.junesoon.mim.app.utils

/** 用户名只用于拼 URL 片段；这里做最保守的字符集检查，异常值直接不发请求。 */
fun isSafeUsername(name: String): Boolean =
    name.length in 2..32 && name.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
